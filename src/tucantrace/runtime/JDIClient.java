package tucantrace.runtime;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Cliente JDI para conectarse a una JVM en ejecución y escuchar eventos
 * de ejecución en tiempo real.
 * <p>
 * Usa la API estándar JDI (com.sun.jdi) disponible en el JDK.
 * No requiere modificar el código del estudiante; solo requiere que la JVM
 * objetivo se inicie con:
 * <pre>
 * -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005
 * </pre>
 * </p>
 */
public class JDIClient implements AutoCloseable {

    private final String host;
    private final int port;
    private final String classFilter;
    private VirtualMachine vm;
    private final BlockingQueue<JDIEvent> eventQueue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private volatile boolean terminated = false;

    /**
     * @param host        host de la JVM objetivo
     * @param port        puerto JDWP (ej. 5005)
     * @param classFilter prefijo de paquete a observar (ej. "co.edu.uniamazonia.logica2")
     */
    public JDIClient(String host, int port, String classFilter) {
        this.host = host;
        this.port = port;
        this.classFilter = classFilter;
    }

    public JDIClient(String host, int port) {
        this(host, port, "");
    }

    /**
     * Conecta a la JVM objetivo y registra las solicitudes de eventos.
     */
    public void connect() throws Exception {
        VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
        List<AttachingConnector> connectors = vmm.attachingConnectors();

        AttachingConnector connector = connectors.stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("SocketAttach connector no disponible"));

        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue(this.host);
        arguments.get("port").setValue(String.valueOf(this.port));

        this.vm = connector.attach(arguments);
        System.out.println("✅ JDI conectado a " + host + ":" + port);
        System.out.println("   Filtro de clases: " + (classFilter.isEmpty() ? "(todas)" : classFilter + ".*"));

        EventRequestManager erm = vm.eventRequestManager();

        // Métodos: entrada y salida
        MethodEntryRequest mer = erm.createMethodEntryRequest();
        if (!classFilter.isEmpty()) mer.addClassFilter(classFilter + ".*");
        mer.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        mer.enable();

        MethodExitRequest mexr = erm.createMethodExitRequest();
        if (!classFilter.isEmpty()) mexr.addClassFilter(classFilter + ".*");
        mexr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        mexr.enable();

        // Clases cargadas (para crear watchpoints de campos correctamente)
        ClassPrepareRequest cpr = erm.createClassPrepareRequest();
        if (!classFilter.isEmpty()) cpr.addClassFilter(classFilter + ".*");
        cpr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        cpr.enable();

        // Hilo de procesamiento de eventos
        Thread t = new Thread(this::eventLoop, "JDI-EventLoop");
        t.setDaemon(true);
        t.start();

        // Reanudar la JVM objetivo (necesario si se inició con suspend=y)
        vm.resume();
    }

    private void eventLoop() {
        try {
            while (running && !terminated) {
                EventSet eventSet = vm.eventQueue().remove();
                for (Event event : eventSet) {
                    // Al prepararse una clase, creamos watchpoints de sus campos
                    if (event instanceof ClassPrepareEvent cpe) {
                        createFieldWatchpoints(cpe.referenceType());
                    }
                    // Detección de terminación de la JVM objetivo
                    if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        terminated = true;
                    }
                    eventQueue.offer(new JDIEvent(event));
                }
                eventSet.resume();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (VMDisconnectedException e) {
            terminated = true;
            System.out.println("JVM objetivo desconectada.");
        } catch (Exception e) {
            if (running) {
                System.err.println("Error en event loop: " + e.getMessage());
            }
        }
    }

    /**
     * Crea watchpoints de modificación para cada campo de la clase preparada.
     */
    private void createFieldWatchpoints(ReferenceType type) {
        EventRequestManager erm = vm.eventRequestManager();
        for (Field field : type.fields()) {
            try {
                ModificationWatchpointRequest mwr =
                    erm.createModificationWatchpointRequest(field);
                mwr.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                mwr.enable();
            } catch (Exception ignored) {
                // Algunos campos (estáticos finales, sintéticos) no admiten watchpoint
            }
        }
    }

    /**
     * Obtiene el siguiente evento (bloqueante).
     */
    public JDIEvent nextEvent() throws InterruptedException {
        return eventQueue.take();
    }

    /**
     * Obtiene el siguiente evento esperando como máximo {@code timeoutMillis}.
     *
     * @return el evento, o {@code null} si se agotó el tiempo
     */
    public JDIEvent pollEvent(long timeoutMillis) throws InterruptedException {
        return eventQueue.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Obtiene el siguiente evento sin bloquear (o {@code null} si no hay).
     */
    public JDIEvent pollEventNow() {
        return eventQueue.poll();
    }

    /**
     * Indica si la JVM objetivo terminó o se desconectó.
     */
    public boolean isTerminated() {
        return terminated;
    }

    public VirtualMachine getVM() {
        return vm;
    }

    @Override
    public void close() {
        running = false;
        try {
            if (vm != null) {
                vm.dispose();
            }
        } catch (Exception ignored) {
            // La VM ya pudo haberse desconectado; no es un error.
        }
    }

    /**
     * Encapsula un evento JDI.
     * <p>
     * IMPORTANTE: toda la información (nombres, valor, descripción) se captura
     * en el constructor, mientras la VM objetivo está viva. Así no se hacen
     * consultas JDI después de que la VM se desconecta (lo que lanzaría
     * {@code VMDisconnectedException}).
     * </p>
     */
    public static class JDIEvent {
        private final Event event;
        private final String kind;
        private final String className;
        private final String memberName;
        private final String valueText;
        private final String describe;

        public JDIEvent(Event event) {
            this.event = event;
            String k = "OTHER";
            String cn = "";
            String mn = "";
            String vt = "";
            String de = "[...] " + event.getClass().getSimpleName();
            try {
                if (event instanceof MethodEntryEvent e) {
                    Method m = e.method();
                    k = "ENTER";
                    cn = simpleName(m.declaringType().name());
                    mn = m.name();
                    de = "[ENTER] " + m.declaringType().name() + "." + m.name() + "()";
                } else if (event instanceof MethodExitEvent e) {
                    Method m = e.method();
                    k = "EXIT";
                    cn = simpleName(m.declaringType().name());
                    mn = m.name();
                    de = "[EXIT]  " + m.declaringType().name() + "." + m.name() + "()";
                } else if (event instanceof ModificationWatchpointEvent e) {
                    Field f = e.field();
                    k = "FIELD";
                    cn = simpleName(f.declaringType().name());
                    mn = f.name();
                    vt = valueToString(f, e.valueToBe());
                    de = "[CAMPO] " + f.declaringType().name() + "." + f.name() + " = " + vt;
                } else if (event instanceof ClassPrepareEvent e) {
                    k = "CLASS";
                    cn = simpleName(e.referenceType().name());
                    de = "[CLASE] " + e.referenceType().name() + " cargada";
                }
            } catch (Exception ex) {
                // VM desconectada durante la captura: dejar valores seguros
            }
            this.kind = k;
            this.className = cn;
            this.memberName = mn;
            this.valueText = vt;
            this.describe = de;
        }

        public Event getEvent() {
            return event;
        }

        public boolean isMethodEntry() {
            return event instanceof MethodEntryEvent;
        }

        public boolean isMethodExit() {
            return event instanceof MethodExitEvent;
        }

        public boolean isFieldModification() {
            return event instanceof ModificationWatchpointEvent;
        }

        public boolean isClassPrepare() {
            return event instanceof ClassPrepareEvent;
        }

        public String kind() {
            return kind;
        }

        public String className() {
            return className;
        }

        public String memberName() {
            return memberName;
        }

        public String valueText() {
            return valueText;
        }

        public String describe() {
            return describe;
        }

        /**
         * Convierte un valor JDI a texto SIN provocar round-trips a la VM.
         * <p>Evita {@code ObjectReference.toString()} (que consulta la VM).</p>
         */
        private static String valueToString(Field field, Value v) {
            try {
                if (v == null) {
                    return "null";
                }
                if (v instanceof StringReference sr) {
                    return "\"" + sr.value() + "\"";
                }
                if (v instanceof PrimitiveValue pv) {
                    return pv.toString();
                }
                // Objetos: no llamar toString(); mostrar el tipo declarado del campo
                return "(" + field.typeName() + ")";
            } catch (Exception e) {
                return "?";
            }
        }

        private static String simpleName(String fullName) {
            int i = fullName.lastIndexOf('.');
            return i >= 0 ? fullName.substring(i + 1) : fullName;
        }
    }
}
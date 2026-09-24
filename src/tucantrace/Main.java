package tucantrace;

import tucantrace.parser.JavaParserAdapter;
import tucantrace.parser.PlantUMLGenerator;
import tucantrace.runtime.JDIClient;
import tucantrace.ui.LiveController;
import tucantrace.ui.LiveSession;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Punto de entrada principal de TucanTrace.
 * <p>
 * Modo 1 (por defecto): parsea un directorio de código Java y genera el UML estático
 * (JavaParser → PlantUML).
 * Modo 2 ({@code --jdi}): se conecta vía JDI a una JVM en ejecución y muestra en
 * consola los eventos de ejecución (método invocado, campo modificado) en tiempo real.
 * </p>
 *
 * <p>Uso: {@code java tucantrace.Main [directorio-fuente] [--jdi]}</p>
 *
 * @author Equipo TucanTrace
 * @version 0.1.0 (MVP)
 */
public class Main {

    private static final String DEFAULT_SOURCE = "case-study/tucango-model/src";
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5005;

    /** Modo silencioso: menos salida técnica (para el modo interactivo). */
    private static boolean quiet = false;

    /** Imprime solo si no estamos en modo silencioso. */
    private static void log(String s) {
        if (!quiet) {
            System.out.println(s);
        }
    }

    public static void main(String[] args) {
        // (La bienvenida se imprime después de leer los argumentos, salvo en modo --quiet)

        // 1. Argumentos
        String sourceDir = DEFAULT_SOURCE;
        boolean enableJDI = false;
        boolean live = false;
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        int httpPort = 8077;
        long delay = 150;
        String execMain = null;
        String execCp = null;
        long keepAlive = -1; // segundos; <= 0 = mantener vivo indefinidamente

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jdi" -> enableJDI = true;
                case "--live" -> { live = true; enableJDI = true; }
                case "--host" -> { if (i + 1 < args.length) host = args[++i]; }
                case "--port" -> { if (i + 1 < args.length) port = Integer.parseInt(args[++i]); }
                case "--http-port" -> { if (i + 1 < args.length) httpPort = Integer.parseInt(args[++i]); }
                case "--delay" -> { if (i + 1 < args.length) delay = Long.parseLong(args[++i]); }
                case "--exec" -> { if (i + 1 < args.length) execMain = args[++i]; }
                case "--exec-cp" -> { if (i + 1 < args.length) execCp = args[++i]; }
                case "--keep-alive" -> { if (i + 1 < args.length) keepAlive = Long.parseLong(args[++i]); }
                case "--quiet" -> quiet = true;
                default -> sourceDir = args[i];
            }
        }

        if (!quiet) {
            System.out.println("=========================================================");
            System.out.println("  TUCANTRACE v0.1 - Live UML Visualization for Java     ");
            System.out.println("  Universidad de la Amazonia - Ingeniería de Sistemas   ");
            System.out.println("=========================================================\n");
            System.out.println("Directorio de código fuente: " + sourceDir);
            System.out.println("Modo JDI en vivo: " + (enableJDI ? "SÍ (" + host + ":" + port + ")" : "NO"));
            System.out.println("Visor navegador: " + (live ? "SÍ (puerto " + httpPort + ")" : "NO"));
            if (execMain != null) {
                System.out.println("Programa a ejecutar: " + execMain + "  [cp: " + execCp + "]");
            }
            System.out.println();
        }

        // 2. Análisis estático: código → UML
        log("--- 1. Análisis estático (JavaParser → PlantUML) ---");
        String plantUML;
        String rootPackage;
        try {
            Path srcPath = Paths.get(sourceDir).toAbsolutePath().normalize();
            if (!srcPath.toFile().exists()) {
                System.err.println("[X] Directorio no encontrado: " + srcPath);
                System.err.println("Uso: java tucantrace.Main [directorio-fuente] [--jdi]");
                return;
            }

            JavaParserAdapter parser = new JavaParserAdapter();
            List<JavaParserAdapter.UMLClassInfo> classes = parser.extractClassInfo(parser.parseProject(srcPath));
            log("  Clases parseadas: " + classes.size());
            if (!quiet) {
                for (JavaParserAdapter.UMLClassInfo c : classes) {
                    System.out.println("    · " + c.name
                            + "  [" + c.attributes.size() + " atributos, "
                            + c.methods.size() + " métodos]"
                            + (c.extendsTypes.isEmpty() ? "" : "  extiende " + c.extendsTypes));
                }
            }

            PlantUMLGenerator generator = new PlantUMLGenerator();
            plantUML = generator.generate(classes);
            log("  PlantUML generado: " + plantUML.length() + " caracteres");

            Path pumlOut = Paths.get("build/tucantrace-diagram.puml");
            generator.saveToFile(plantUML, pumlOut.toString());
            log("  [OK] Diagrama guardado: " + pumlOut.toAbsolutePath());

            // Intentar renderizar SVG (si PlantUML lo permite sin GraphViz)
            try {
                Path svgOut = Paths.get("build/tucantrace-diagram.svg");
                java.nio.file.Files.createDirectories(svgOut.getParent());
                java.nio.file.Files.writeString(svgOut, generator.generateSVG(plantUML));
                log("  [OK] SVG renderizado: " + svgOut.toAbsolutePath());
            } catch (Throwable t) {
                log("  [!] No se pudo renderizar SVG: " + t.getMessage());
            }

            rootPackage = commonPackagePrefix(classes);

        } catch (Exception e) {
            System.err.println("[X] Error en analisis estatico: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // 3. Visor en navegador (--live) o trazado por consola (--jdi)
        if (live) {
            runLiveViewer(plantUML, rootPackage, host, port, httpPort, delay, execMain, execCp, keepAlive);
            return;
        }

        if (!enableJDI) {
            System.out.println("\nTip: usa '--jdi' para trazar en consola, o '--live' para el visor en el navegador.");
            System.out.println("\n=========================================================");
            System.out.println("  TUCANTRACE - Analisis estatico finalizado             ");
            System.out.println("=========================================================");
            return;
        }

        System.out.println("\n--- 2. Conexion JDI en vivo (filtro: " + rootPackage + ".*) ---");
        try (JDIClient client = new JDIClient(host, port, rootPackage)) {
            client.connect();
            System.out.println("  Escuchando eventos... (Ctrl+C para salir)\n");

            int count = 0;
            while (!client.isTerminated()) {
                JDIClient.JDIEvent ev = client.pollEvent(500);
                if (ev != null) {
                    System.out.println("  " + ev.describe());
                    count++;
                }
            }
            System.out.println("\n  Total eventos capturados: " + count);

        } catch (Exception e) {
            System.err.println("[X] Error JDI: " + e.getMessage());
            System.err.println("   Verifica que la JVM objetivo este corriendo con:");
            System.err.println("   -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + port);
        }

        System.out.println("\n=========================================================");
        System.out.println("  TUCANTRACE - Demo finalizado                           ");
        System.out.println("=========================================================");
    }

    /**
     * Visor en vivo: sirve el diagrama, lanza el programa objetivo y transmite
     * los eventos JDI y la salida del programa en dos pestañas del navegador.
     */
    private static void runLiveViewer(String plantUML, String rootPackage,
                                      String host, int jdiPort, int httpPort, long delay,
                                      String execMain, String execCp, long keepAlive) {
        if (execMain == null) {
            System.err.println("[X] El modo --live requiere --exec <clase> [--exec-cp <cp>].");
            return;
        }
        try {
            PlantUMLGenerator generator = new PlantUMLGenerator();
            String svg = generator.generateSVG(plantUML);

            LiveSession session = new LiveSession(svg, httpPort, delay);
            try {
                LiveController controller = new LiveController(
                        session, host, jdiPort, rootPackage, execMain, execCp);
                session.setRunHandler(controller::ejecutarAsync);

                log("\n--- 2. Visor en vivo (navegador) ---");
                log("  Programa  : " + execMain + "  [cp: " + execCp + "]");
                log("  Visor UML : " + session.getUrl());
                log("  Terminal  : " + session.getUrlTerminal());
                session.abrirDosPestanas();

                // Esperar al navegador (hasta 12s)
                for (int i = 0; i < 48 && !session.hayNavegador(); i++) {
                    Thread.sleep(250);
                }
                log(session.hayNavegador() ? "  [OK] Navegador conectado." : "  [!] Sin navegador.");

                // Guía clara para el usuario (esto es lo importante)
                guiaConsola();

                // Primera ejecución
                controller.ejecutarAsync();

                // Mantener el servidor vivo para re-ejecutar desde el navegador
                if (keepAlive <= 0) {
                    while (true) {
                        Thread.sleep(1000);
                    }
                } else {
                    Thread.sleep(keepAlive * 1000);
                }
            } finally {
                session.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[X] Error en el visor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Imprime una guía clara para que el usuario sepa qué escribir en la consola.
     */
    private static void guiaConsola() {
        System.out.println();
        System.out.println("########################################################################");
        System.out.println("#                                                                      #");
        System.out.println("#                   >>>   ESCRIBI EN ESTA VENTANA   <<<                #");
        System.out.println("#                                                                      #");
        System.out.println("#   El programa ya arrancó y espera que le escribas ABAJO.             #");
        System.out.println("#   Escribí el número y presioná ENTER.                                 #");
        System.out.println("#                                                                      #");
        System.out.println("#     >>>  EMPEZÁ ESCRIBIENDO:   1   y ENTER                          #");
        System.out.println("#          (registra al estudiante)                                    #");
        System.out.println("#          Luego responde: nombre, cédula, teléfono, correo, código    #");
        System.out.println("#                                                                      #");
        System.out.println("#   Menú rápido (escribí el número + ENTER):                           #");
        System.out.println("#     1 = registrar estudiante    2 = registrar motorista + moto       #");
        System.out.println("#     3 = pedir viaje             4 = aceptar viaje                    #");
        System.out.println("#     5 = iniciar viaje           6 = finalizar viaje                  #");
        System.out.println("#     7 = reportar pago           8 = confirmar pago                   #");
        System.out.println("#     9 = calificar              10 = ver estado                       #");
        System.out.println("#     0 = salir                                                        #");
        System.out.println("#                                                                      #");
        System.out.println("#   Mientras escribís, mirá el NAVEGADOR: el UML se ilumina en vivo.   #");
        System.out.println("#                                                                      #");
        System.out.println("########################################################################");
        System.out.println();
    }

    /**
     * Calcula el prefijo de paquete común a todas las clases parseadas.
     */
    private static String commonPackagePrefix(List<JavaParserAdapter.UMLClassInfo> classes) {
        String prefix = null;
        for (JavaParserAdapter.UMLClassInfo c : classes) {
            if (c.packageName == null || c.packageName.isEmpty()) {
                continue;
            }
            if (prefix == null) {
                prefix = c.packageName;
            } else {
                prefix = commonPrefix(prefix, c.packageName);
            }
        }
        return prefix == null ? "" : prefix;
    }

    private static String commonPrefix(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        StringBuilder sb = new StringBuilder();
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            if (!pa[i].equals(pb[i])) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(pa[i]);
        }
        return sb.toString();
    }
}
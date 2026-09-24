package tucantrace;

import tucantrace.parser.JavaParserAdapter;
import tucantrace.parser.PlantUMLGenerator;
import tucantrace.runtime.JDIClient;

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

    public static void main(String[] args) {
        System.out.println("=========================================================");
        System.out.println("  TUCANTRACE v0.1 - Live UML Visualization for Java     ");
        System.out.println("  Universidad de la Amazonia - Ingeniería de Sistemas   ");
        System.out.println("=========================================================\n");

        // 1. Argumentos
        String sourceDir = DEFAULT_SOURCE;
        boolean enableJDI = false;
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jdi" -> enableJDI = true;
                case "--host" -> { if (i + 1 < args.length) host = args[++i]; }
                case "--port" -> { if (i + 1 < args.length) port = Integer.parseInt(args[++i]); }
                default -> sourceDir = args[i];
            }
        }

        System.out.println("Directorio de código fuente: " + sourceDir);
        System.out.println("Modo JDI en vivo: " + (enableJDI ? "SÍ (" + host + ":" + port + ")" : "NO"));
        System.out.println();

        // 2. Análisis estático: código → UML
        System.out.println("--- 1. Análisis estático (JavaParser → PlantUML) ---");
        String plantUML;
        String rootPackage;
        try {
            Path srcPath = Paths.get(sourceDir).toAbsolutePath().normalize();
            if (!srcPath.toFile().exists()) {
                System.err.println("❌ Directorio no encontrado: " + srcPath);
                System.err.println("Uso: java tucantrace.Main [directorio-fuente] [--jdi]");
                return;
            }

            JavaParserAdapter parser = new JavaParserAdapter();
            List<JavaParserAdapter.UMLClassInfo> classes = parser.extractClassInfo(parser.parseProject(srcPath));
            System.out.println("  Clases parseadas: " + classes.size());
            for (JavaParserAdapter.UMLClassInfo c : classes) {
                System.out.println("    · " + c.name
                        + "  [" + c.attributes.size() + " atributos, "
                        + c.methods.size() + " métodos]"
                        + (c.extendsTypes.isEmpty() ? "" : "  extiende " + c.extendsTypes));
            }

            PlantUMLGenerator generator = new PlantUMLGenerator();
            plantUML = generator.generate(classes);
            System.out.println("  PlantUML generado: " + plantUML.length() + " caracteres");

            Path pumlOut = Paths.get("build/tucantrace-diagram.puml");
            generator.saveToFile(plantUML, pumlOut.toString());
            System.out.println("  ✅ Diagrama guardado: " + pumlOut.toAbsolutePath());

            // Intentar renderizar SVG (si PlantUML lo permite sin GraphViz)
            try {
                Path svgOut = Paths.get("build/tucantrace-diagram.svg");
                java.nio.file.Files.createDirectories(svgOut.getParent());
                java.nio.file.Files.writeString(svgOut, generator.generateSVG(plantUML));
                System.out.println("  ✅ SVG renderizado: " + svgOut.toAbsolutePath());
            } catch (Throwable t) {
                System.out.println("  ⚠️ No se pudo renderizar SVG (¿falta GraphViz?): " + t.getMessage());
            }

            rootPackage = commonPackagePrefix(classes);

        } catch (Exception e) {
            System.err.println("❌ Error en análisis estático: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // 3. Modo JDI (opcional)
        if (!enableJDI) {
            System.out.println("\n💡 Tip: usa '--jdi' para ver la ejecución en vivo (requiere la JVM objetivo en modo debug).");
            System.out.println("\n=========================================================");
            System.out.println("  TUCANTRACE - Análisis estático finalizado              ");
            System.out.println("=========================================================");
            return;
        }

        System.out.println("\n--- 2. Conexión JDI en vivo (filtro: " + rootPackage + ".*) ---");
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
            System.err.println("❌ Error JDI: " + e.getMessage());
            System.err.println("   Verifica que la JVM objetivo esté corriendo con:");
            System.err.println("   -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=" + port);
        }

        System.out.println("\n=========================================================");
        System.out.println("  TUCANTRACE - Demo finalizado                           ");
        System.out.println("=========================================================");
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
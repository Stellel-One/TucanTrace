# TucanTrace

**Live UML visualization for Java execution.**

Transforma código Java de estudiantes en diagramas UML interactivos y **resalta clases, métodos y atributos en tiempo real** mientras el código se ejecuta.

---

## 🎯 Objetivo

Herramienta de apoyo docente para **Lógica & Algoritmos II** (Universidad de la Amazonia) que permite:

1. **Transformar** código fuente Java → diagrama UML de clases (estático)
2. **Ejecutar** el código y ver el diagrama **vivo**: clases, métodos y atributos se iluminan/cambian de color al invocarse/acceder
3. **Integrarse** con Apache NetBeans (ant-based) sin modificar el código del estudiante

---

## 🏗️ Arquitectura

```
┌─────────────────────────────────────────────────────────────┐
│  Estudiante aprieta "Run with TucanTrace" en NetBeans       │
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  JVM + agente JDI / -javaagent                              │
│  • JavaParser → AST → PlantUML → diagrama base (estático)   │
│  • JDI (JDI/JPDA) → MethodEntryEvent, FieldModificationEvent│
└───────────────────────────┬─────────────────────────────────┘
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Visor (Swing/JavaFX o navegador vía WebSocket)             │
│  • Diagrama UML renderizado (PlantUML/SVG)                  │
│  • Highlight en vivo: clase activa, método invocado,        │
│    atributo leído/escrito                                    │
└─────────────────────────────────────────────────────────────┘
```

---

## 🛠️ Stack

| Capa | Tecnología |
|------|------------|
| **Lenguaje** | Java 21 |
| **Build** | Apache Ant (NetBeans compatible) |
| **Parseo estático** | [JavaParser](https://javaparser.org/) |
| **Diagramas** | [PlantUML](https://plantuml.com/) (programático) / [Kroki](https://kroki.io/) |
| **Runtime capture** | JDI / JPDA (`com.sun.jdi`) — sin modificar código estudiante |
| **UI** | Swing / JavaFX (visor integrado) o navegador (WebSocket) |
| **IDE** | Apache NetBeans (Ant) |

---

## 📁 Estructura del proyecto

```
TucanTrace/
├── build.xml                 # Script Ant (compile, run, jar, clean)
├── build.properties          # Propiedades de usuario (no versionar)
├── README.md                 # Este archivo
├── LICENSE                   # Licencia MIT
├── lib/                      # JARs de dependencias (PlantUML, JavaParser, etc.)
├── nbproject/                # Metadatos NetBeans (generado al abrir)
├── src/
│   └── tucantrace/
│       ├── Main.java                 # Punto de entrada (demo)
│       ├── agent/
│       │   └── TraceAgent.java       # Java Agent (-javaagent) opcional
│       ├── parser/
│       │   ├── JavaParserAdapter.java    # Wrapper JavaParser → AST
│       │   └── PlantUMLGenerator.java    # AST → PlantUML text
│       ├── runtime/
│       │   ├── JDIClient.java            # Cliente JDI (attach a JVM)
│       │   ├── EventListener.java        # Escucha MethodEntry/FieldMod
│       │   └── EventBus.java             # Distribuye eventos a UI
│       ├── ui/
│       │   ├── DiagramViewer.java        # Visor Swing/JavaFX del UML
│       │   └── HighlightManager.java     # Maneja colores/resaltado
│       └── model/
│           ├── UMLClass.java
│           ├── UMLMethod.java
│           ├── UMLAttribute.java
│           └── UMLRelation.java
├── test/
│   └── tucantrace/               # Tests JUnit 5 (opcional)
├── docs/
│   ├── arquitectura.md
│   ├── jdi-guia.md
│   └── plantuml-guia.md
└── case-study/                   # Casos de prueba (ej. TucanGo)
    └── tucango-model/
        └── src/...
```

---

## 🚀 Cómo compilar y ejecutar

### Prerrequisitos
- JDK 21
- Apache NetBeans 18+ (o Ant 1.10+)
- GraphViz instalado (para PlantUML renderizado local)

### En NetBeans
1. `File → Open Project` → selecciona la carpeta `TucanTrace`
2. NetBeans detecta el proyecto Ant (`build.xml`)
3. `Right-click project → Build` → `Run`

### Línea de comandos (Ant)
```bash
# Compilar
ant compile

# Ejecutar demo
ant run

# Generar JAR
ant jar

# Limpiar
ant clean
```

---

## ✅ Estado verificado (MVP funcional)

Probado el **23/09/2026** con JDK 21:

| Componente | Estado | Evidencia |
|------------|--------|-----------|
| Compilación | ✅ | `javac -encoding UTF-8 -cp "lib/*" -d build/classes src/...` → exit 0 |
| Parseo estático | ✅ | 12 clases de TucanGo detectadas (atributos, métodos, herencia) |
| Generación PlantUML | ✅ | `build/tucantrace-diagram.puml` (5.7 KB) |
| Render SVG | ✅ | `build/tucantrace-diagram.svg` (50 KB) — sin GraphViz (motor Smetana) |
| Conexión JDI | ✅ | attach a `127.0.0.1:5005` |
| Eventos en vivo | ✅ | `ClassPrepare`, `MethodEntry`, `MethodExit`, `ModificationWatchpoint` con valores |

### Comandos verificados (CLI, sin Ant)

```powershell
cd TucanTrace
$javac = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\javac.exe"
$java  = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\java.exe"

# 1. Compilar TucanTrace
$srcs = Get-ChildItem -Recurse -Filter *.java -Path "src" | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -cp "lib/*" -d "build/classes" $srcs

# 2. Modo estático: código → UML
& $java "-Dfile.encoding=UTF-8" "-cp" "build/classes;lib/*" "tucantrace.Main"

# 3. Modo en vivo (JDI):
#    3a. Compilar el caso de prueba
& $javac -encoding UTF-8 -d "build/case-study-classes" `
    (Get-ChildItem -Recurse -Filter *.java "case-study\tucango-model\src").FullName
#    3b. Lanzar el objetivo en modo debug (espera al debugger)
Start-Process $java -ArgumentList @(
    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
    "-Dfile.encoding=UTF-8", "-cp", "build/case-study-classes",
    "co.edu.uniamazonia.logica2.Main") -WindowStyle Hidden
#    3c. Adjuntar TucanTrace
& $java "-Dfile.encoding=UTF-8" "-cp" "build/classes;lib/*" "tucantrace.Main" "--jdi" "--host" "127.0.0.1"
```

### Salida en vivo (muestra real)

```
▶ ENTER  co.edu.uniamazonia.logica2.modelo.Estudiante.<init>()
▶ ENTER  co.edu.uniamazonia.logica2.modelo.Persona.<init>()
✎ CAMPO  co.edu.uniamazonia.logica2.modelo.Persona.identificacion  →  "1117540001"
✎ CAMPO  co.edu.uniamazonia.logica2.modelo.Persona.nombre  →  "Gian Marco Castañeda"
◀ EXIT   co.edu.uniamazonia.logica2.modelo.Persona.<init>()
✎ CAMPO  co.edu.uniamazonia.logica2.modelo.Estudiante.codigoEstudiantil  →  "EST-2026-042"
```

> **Nota Windows:** si el puerto 5005 no responde, usar `address=*:5005` (todas las interfaces) y conectarse con `--host 127.0.0.1`. El `address=5005` solo puede quedar en el loopback IPv6 y dar `Connection refused`.

---

## 🔧 Configuración de ejecución en NetBeans (para el agente)

En `Project Properties → Run → VM Options` agregar:

```properties
# Opción A: JDI (debug mode) — recomendado para MVP
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005

# Opción B: Java Agent (instrumentación) — más rápido
# -javaagent:${project.dir}/build/agent/TucanTraceAgent.jar
```

---

## 🧪 Caso de prueba: TucanGo

El modelo de dominio **TucanGo v5.0** (5 clases, herencia, enums, servicios) sirve como caso de prueba real.

Ver repo hermano: https://github.com/Stellel-One/TucanGo

---

## 🗺️ Roadmap (MVP → Futuro)

| Fase | Entregable |
|------|------------|
| **1** | Parser estático: JavaParser → PlantUML → diagrama UML estático |
| **2** | Cliente JDI: attach a JVM, escuchar `MethodEntryEvent`, `FieldModificationEvent` |
| **3** | Visor Swing/JavaFX: render PlantUML SVG + highlight live (clase/método/atributo) |
| **4** | Integración NetBeans: VM Options + ventana `TopComponent` (opcional NBM) |
| **5** | Secuencia UML en vivo + exportar a PDF/PNG |

---

## 📄 Licencia

MIT License — libre para uso académico y comercial.

---

## 👥 Autores

- Equipo TucanGo — Universidad de la Amazonia, Ingeniería de Sistemas
- Lógica & Algoritmos II — 2026

---

> **Nota:** Este proyecto es un **plus** del proyecto principal **TucanGo** (movilidad estudiantil segura). Repositorio principal: https://github.com/Stellel-One/TucanGo
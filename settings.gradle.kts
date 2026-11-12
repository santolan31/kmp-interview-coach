plugins {
    // Descarga automáticamente el JDK 21 si no lo tienes.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kmp-interview-coach"

// La lógica del Mentor (pasos, TODO, prompts, clientes de IA…) en Kotlin Multiplatform: la usan el plugin de
// Android Studio (JVM) y la extensión de VS Code (JavaScript).
include(":core")

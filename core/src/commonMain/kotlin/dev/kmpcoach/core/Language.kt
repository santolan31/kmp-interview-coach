package dev.kmpcoach.core

/**
 * Lenguaje del archivo, sacado de su extensión. El Mentor nació para Android/KMP, pero en VS Code tus amigos pueden
 * programar en cualquier lenguaje: así se presenta como mentor de lo que de verdad están escribiendo.
 * En Kotlin las instrucciones quedan exactamente como antes.
 */
enum class CodeLanguage(val label: String, val fence: String, private val extensions: Set<String>) {
    KOTLIN("Kotlin", "kotlin", setOf("kt", "kts")),
    JAVA("Java", "java", setOf("java")),
    SWIFT("Swift", "swift", setOf("swift")),
    TYPESCRIPT("TypeScript", "typescript", setOf("ts", "tsx", "mts", "cts")),
    JAVASCRIPT("JavaScript", "javascript", setOf("js", "jsx", "mjs", "cjs")),
    PYTHON("Python", "python", setOf("py")),
    GO("Go", "go", setOf("go")),
    RUST("Rust", "rust", setOf("rs")),
    DART("Dart", "dart", setOf("dart")),
    CSHARP("C#", "csharp", setOf("cs")),
    CPP("C++", "cpp", setOf("cpp", "cc", "cxx", "hpp", "hh", "hxx")),
    C("C", "c", setOf("c", "h")),
    RUBY("Ruby", "ruby", setOf("rb")),
    PHP("PHP", "php", setOf("php")),
    SCALA("Scala", "scala", setOf("scala")),
    OTHER("programación", "", emptySet());

    /** De qué es mentor el Mentor en este archivo: «Android y Kotlin Multiplatform» o «programación en Python». */
    val domain: String
        get() = when (this) {
            KOTLIN -> "Android y Kotlin Multiplatform"
            OTHER -> "programación"
            else -> "programación en $label"
        }

    companion object {
        /** Por la extensión del archivo; sin archivo o con una extensión desconocida, [OTHER]. Sin archivo, Kotlin (el IDE). */
        fun of(path: String?): CodeLanguage {
            if (path.isNullOrBlank()) return KOTLIN
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions } ?: OTHER
        }
    }
}

package dev.kmpcoach.core

/**
 * Reglas del proyecto (su `AGENTS.md` o `CLAUDE.md`): con ellas el tutor sabe cómo se hacen las cosas en ESE proyecto
 * (capas, colores del tema, componentes…) y no sugiere algo que las contradiga. Solo se envían las secciones de
 * arquitectura y convenciones, que son las que afectan al código; lo de "cómo trabajar conmigo" es para la otra IA.
 */
object ProjectRules {
    /** [source]: nombre del archivo leído, para mostrar en el panel qué contexto usó el tutor. */
    data class Found(val source: String, val text: String)

    private const val MAX_CHARS = 6000
    private val HEADING = Regex("^##\\s+(.+)$")
    private val RELEVANT = Regex("arquitectura|architecture|convenci|convention|estilo|style|c[oó]digo|code", RegexOption.IGNORE_CASE)

    /** Las secciones `##` de arquitectura y convenciones; si no hay ninguna con esos nombres, el archivo entero. Recortado. */
    fun extract(markdown: String, maxChars: Int = MAX_CHARS): String? {
        val lines = markdown.lines()
        val sections = ArrayList<List<String>>()
        var current: MutableList<String>? = null
        var keep = false
        for (l in lines) {
            val h = HEADING.matchEntire(l.trim())
            if (h != null) {
                if (keep) current?.let(sections::add)
                keep = RELEVANT.containsMatchIn(h.groupValues[1])
                current = mutableListOf(l)
            } else {
                current?.add(l)
            }
        }
        if (keep) current?.let(sections::add)
        val text = (if (sections.isEmpty()) lines else sections.flatten()).joinToString("\n").trim()
        if (text.isBlank()) return null
        if (text.length <= maxChars) return text
        val cut = text.substring(0, maxChars).substringBeforeLast('\n')
        return "$cut\n… (recortado)"
    }

    /** Bloque para el prompt. Vacío si el proyecto no tiene reglas. */
    fun context(rules: Found?): String {
        if (rules == null || rules.text.isBlank()) return ""
        return """

REGLAS DEL PROYECTO (de su ${rules.source}; las decidió el equipo y mandan sobre tus preferencias):
---
${rules.text.trim()}
---
No sugieras nada que contradiga estas reglas ni el plan que le dio la otra IA. Si crees que algo del plan está mal, dilo como "pregunta", sin exigir el cambio. No propongas estructura nueva (clases, archivos, capas o patrones) que el paso no pida: eso lo decide el plan.
""".trimEnd()
    }
}

/**
 * Un comentario de la revisión habla de un código que ya cambió: nombraba algo (`isEnable`, `textPlaceholder`,
 * `primary`) que estaba en su línea cuando se escribió y ya no está. Entonces se da por resuelto solo, sin gastar una
 * llamada a la IA.
 */
object StaleComments {
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val BACKTICKS = Regex("`([^`]+)`")

    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")
    private fun word(t: String) = Regex("(?<![A-Za-z0-9_])${Regex.escape(t)}(?![A-Za-z0-9_])")

    /**
     * [original]: las líneas alrededor del comentario cuando se escribió (la suya, una antes y dos después).
     * [current]: las mismas líneas ahora.
     */
    fun outdated(message: String, original: List<String>, current: List<String>): Boolean {
        if (original.isEmpty()) return false
        if (original.map(::norm) == current.map(::norm)) return false
        val code = original.joinToString("\n")
        val now = current.joinToString("\n")
        val quoted = BACKTICKS.findAll(message).flatMap { IDENT.findAll(it.groupValues[1]).map { m -> m.value } }.toSet()
        val tokens = IDENT.findAll(message).map { it.value }.filter { it.length >= 4 }.toSet()
        // Solo cuentan los nombres que estaban en el código: así las palabras normales del mensaje no influyen.
        val named = tokens.filter { t -> word(t).containsMatchIn(code) && codeLike(t, t in quoted, code) }
        if (named.any { t -> !word(t).containsMatchIn(now) }) return true
        // Fragmentos de código sin nombre que el mensaje cita tal cual («recibe {}»): si ya no están, habla de otro código.
        val atoms = ATOM.findAll(code).map { it.value.trimEnd(',', ';') }
            .filter { a -> a.length >= 2 && a !in STOP && a.any { !it.isLetterOrDigit() && it != '_' } }
            .toSet()
        // Se cuenta cuántas veces aparece: la línea de al lado puede tener el mismo `{}` y no por eso sigue igual.
        return atoms.any { a -> a in message && count(now, a) < count(code, a) }
    }

    private fun count(text: String, part: String): Int = text.windowed(part.length).count { it == part }

    private val ATOM = Regex("[^\\s]+")
    private val STOP = setOf("()", "})", "){", "->", "==", "!=", "&&", "||", "?:", "?.", "::", "//", "/*", "*/")
    private val LITERALS = setOf("null", "true", "false")

    /** `isEnable` o `text_placeholder` son código; `color` solo si va entre backticks o se usa como código (`.primary`, `color =`). */
    private fun codeLike(t: String, quoted: Boolean, code: String): Boolean {
        if (quoted || t in LITERALS || t.drop(1).any { it.isUpperCase() } || '_' in t || t.any { it.isDigit() }) return true
        val e = Regex.escape(t)
        return Regex("\\.$e(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])$e\\s*[(=]").containsMatchIn(code)
    }
}

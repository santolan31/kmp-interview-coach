package dev.kmpcoach.core

/**
 * Planes de Antigravity. Sus conversaciones se guardan en un formato binario (`.pb`) que no se puede leer, pero los
 * planes son Markdown: `~/.gemini/antigravity…/brain/<id>/implementation_plan.md` (y otros `.md` del mismo plan).
 *
 * Su formato no usa «## Paso N»: cada archivo que cambia es un encabezado como
 * `#### [MODIFY] [AuthService.kt](file:///…/AuthService.kt)` o `#### [NEW] \`MainScreen.kt\``, seguido de la
 * explicación y, a veces, un bloque ```diff. [toSteps] lo traduce al formato de la guía para reusar todo lo demás
 * (ubicar los TODO, poner el esqueleto, «¿Cumple el paso?»).
 */
object AntigravityPlans {
    /** Dónde guarda Antigravity sus conversaciones, desde el HOME. La copia de respaldo (`antigravity-backup`) se omite: duplica las otras. */
    val BRAIN_DIRS = listOf(".gemini/antigravity/brain", ".gemini/antigravity-ide/brain", ".gemini/antigravity-cli/brain")

    private val CHANGE = Regex("^#{2,4}\\s*\\[(MODIFY|NEW|DELETE)\\]\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")
    private val TICKS = Regex("`([^`]+)`")
    private val TITLE = Regex("^#\\s+(.+)$")

    /**
     * Archivos del cerebro de Antigravity que pueden traer el plan. `walkthrough.md` no: también usa `[MODIFY]`, pero es
     * el resumen de lo que ya se hizo.
     */
    fun isPlanFile(name: String): Boolean = name.endsWith(".md") && name != "walkthrough.md"

    /** Tiene cambios por archivo (formato de Antigravity) o pasos (formato de la guía). */
    fun isPlan(markdown: String): Boolean = StepParser.hasSteps(markdown) || markdown.lineSequence().any { CHANGE.matches(it.trim()) }

    /** El título del plan (su primer «# …»), para decir de dónde salen los pasos. */
    fun title(markdown: String): String? = markdown.lineSequence().firstNotNullOfOrNull { TITLE.matchEntire(it.trim())?.groupValues?.get(1)?.trim() }

    /** El plan habla de este archivo (por su nombre sin extensión, como en las conversaciones de Claude Code). */
    fun mentions(markdown: String, fileName: String): Boolean {
        val stem = fileName.substringAfterLast('/').substringBeforeLast('.')
        return stem.length >= 3 && markdown.contains(stem)
    }

    /**
     * El plan en el formato de la guía: cada `[MODIFY]`/`[NEW]`/`[DELETE]` pasa a ser «## Paso N: Modificar X» con el
     * enlace al archivo justo debajo, para que su ```diff cuente como el cambio de ese paso. Si el plan ya usa
     * «## Paso N», queda igual.
     */
    fun toSteps(markdown: String): String {
        if (StepParser.hasSteps(markdown)) return markdown
        var n = 0
        var inCode = false
        return markdown.lines().joinToString("\n") { raw ->
            val t = raw.trim()
            if (t.startsWith("```")) inCode = !inCode
            val m = if (inCode) null else CHANGE.matchEntire(t)
            if (m == null) return@joinToString raw
            n++
            val kind = when (m.groupValues[1].uppercase()) { "NEW" -> "Crear"; "DELETE" -> "Borrar"; else -> "Modificar" }
            val rest = m.groupValues[2]
            val link = LINK.find(rest)
            val name = link?.groupValues?.get(1)?.replace("`", "") ?: TICKS.find(rest)?.groupValues?.get(1) ?: rest.substringBefore(" ").ifBlank { "archivo" }
            val heading = "## Paso $n: $kind ${name.trim()}"
            if (link != null) "$heading\n[${name.trim()}](${link.groupValues[2]})" else heading
        }
    }

    /** Extracto para el Mentor cuando el plan viene de Antigravity: el plan mismo, recortado. */
    fun excerpt(markdown: String, maxChars: Int = 12_000): String {
        val title = title(markdown)
        val body = if (markdown.length <= maxChars) markdown else markdown.take(maxChars - 1).trimEnd() + "…"
        return "IA (plan de Antigravity" + (title?.let { ": «$it»" } ?: "") + "):\n" + body
    }
}

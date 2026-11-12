package dev.kmpcoach.core

/** Un mensaje de texto de una conversación con otra IA: lo que pidió el estudiante o lo que respondió la IA. */
data class SessionTurn(val role: String, val text: String, val at: String)

/**
 * Lee las conversaciones que Claude Code guarda por proyecto (`~/.claude/projects/<carpeta>/<sesión>.jsonl`), para que
 * el tutor sepa en qué paso va el estudiante sin que tenga que contárselo ni escribir un plan.
 */
object ClaudeSessions {
    /** Nombre de la carpeta de un proyecto: su ruta con todo lo que no sea letra o número cambiado por «-». */
    fun projectDirName(basePath: String): String = basePath.replace(Regex("[^A-Za-z0-9]"), "-")

    // Bloques que el IDE añade al mensaje (contexto del editor, recordatorios del sistema…): no los escribió el estudiante.
    // [\s\S] en vez de «.» con DOT_MATCHES_ALL, que no existe en JavaScript: también cruza saltos de línea.
    private val TAGS = Regex("<([a-zA-Z_][\\w-]*)(\\s[^>]*)?>[\\s\\S]*?</\\1>")

    /**
     * Mensajes de texto de la conversación, en orden. Ignora llamadas a herramientas y sus resultados, las conversaciones
     * de subagentes y las líneas incompletas (al leer solo el final del archivo, la primera puede venir cortada).
     */
    fun parse(lines: Sequence<String>): List<SessionTurn> {
        val out = ArrayList<SessionTurn>()
        for (line in lines) {
            if (line.isBlank()) continue
            val e = runCatching { Json.parse(line) }.getOrNull().asMap()
            val role = e.str("type")
            if (role != "user" && role != "assistant") continue
            if (e["isSidechain"] == true || e["isMeta"] == true) continue
            val content = e["message"].asMap()["content"]
            val texts = when (content) {
                is String -> listOf(content)
                else -> content.asList().map { it.asMap() }.filter { it.str("type") == "text" }.mapNotNull { it.str("text") }
            }
            val text = texts.joinToString("\n") { TAGS.replace(it, "") }.trim()
            if (text.isEmpty() || text.startsWith("<")) continue
            out += SessionTurn(role, text, e.str("timestamp").orEmpty())
        }
        return out
    }

    /** Momento del último mensaje de la IA que menciona el archivo, o null si no lo menciona. Sirve para elegir la sesión. */
    fun lastMention(turns: List<SessionTurn>, fileName: String): String? {
        val stem = fileName.substringBeforeLast('.')
        if (stem.length < 3) return null
        return turns.lastOrNull { it.role == "assistant" && it.text.contains(stem) }?.at
    }

    /**
     * Extracto para el Mentor. La IA que dio el plan conoce todo el proyecto, así que su plan es la fuente de verdad.
     * Si la conversación tiene un mensaje con pasos («## Paso N»), se arma por prioridad hasta [maxChars]:
     * 1. ese plan (el más reciente que menciona el archivo), 2. el pedido que lo originó, 3. lo que se habló después
     * (aclaraciones, «ahora el paso 5.2»), 4. lo anterior que menciona el archivo. Se muestra en orden cronológico.
     * Sin plan: el último pedido y su respuesta (o el último intercambio sobre el archivo).
     */
    fun excerpt(turns: List<SessionTurn>, fileName: String?, maxChars: Int = 12_000): String? {
        if (turns.isEmpty()) return null
        val stem = fileName?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.length >= 3 }
        val withSteps = { t: SessionTurn -> t.role == "assistant" && StepParser.hasSteps(t.text) }
        val plan = turns.indexOfLast { withSteps(it) && (stem == null || it.text.contains(stem)) }.takeIf { it >= 0 }
            ?: turns.indexOfLast(withSteps).takeIf { it >= 0 }
            ?: return lastExchange(turns, stem, maxChars)
        val request = turns.subList(0, plan).indexOfLast { it.role == "user" }

        class Part(val index: Int, val text: String)
        val chosen = ArrayList<Part>()
        var left = maxChars
        fun take(i: Int, cap: Int, label: String = if (turns[i].role == "user") "Estudiante" else "IA") {
            if (left < 200 || chosen.any { it.index == i }) return
            val text = "$label: " + clip(turns[i].text, minOf(cap, left - label.length - 4))
            chosen += Part(i, text)
            left -= text.length + 2
        }
        take(plan, (maxChars * 0.6).toInt(), "IA (el plan con los pasos)")
        if (request >= 0) take(request, 800, "Estudiante (pidió el plan)")
        ((plan + 1) until turns.size).reversed().forEach { take(it, 1500) }
        (0 until maxOf(request, 0)).reversed().filter { stem != null && turns[it].text.contains(stem) }.forEach { take(it, 1200) }
        return chosen.sortedBy { it.index }.joinToString("\n\n") { it.text }.let { clip(it, maxChars) }.ifEmpty { null }
    }

    /** Sin plan con pasos: el último pedido y lo que siguió; si no habla del archivo, el último intercambio que sí. */
    private fun lastExchange(turns: List<SessionTurn>, stem: String?, maxChars: Int): String? {
        var window = turns.subList(turns.indexOfLast { it.role == "user" }.coerceAtLeast(0), turns.size)
        if (stem != null && window.none { it.text.contains(stem) }) {
            val i = turns.indexOfLast { it.role == "assistant" && it.text.contains(stem) }
            if (i >= 0) window = turns.subList(turns.subList(0, i).indexOfLast { it.role == "user" }.coerceAtLeast(0), i + 1)
        }
        val request = window.firstOrNull { it.role == "user" }?.text?.let { clip(it, 600) }
        val answers = window.filter { it.role == "assistant" }.joinToString("\n\n") { it.text }
        val text = buildString {
            if (request != null) append("Estudiante: ").append(request).append("\n\n")
            if (answers.isNotBlank()) append("IA: ").append(clip(answers, (maxChars - length - 5).coerceAtLeast(400)))
        }.trim()
        return text.ifEmpty { null }
    }

    /** Último pedido del estudiante, corto, para mostrar en el panel qué sesión se está leyendo. */
    fun lastRequest(turns: List<SessionTurn>): String? =
        turns.lastOrNull { it.role == "user" }?.text?.replace(Regex("\\s+"), " ")?.let { clip(it, 90) }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
}

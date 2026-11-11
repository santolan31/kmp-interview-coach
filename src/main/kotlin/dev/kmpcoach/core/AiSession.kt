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
    private val TAGS = Regex("<([a-zA-Z_][\\w-]*)(\\s[^>]*)?>.*?</\\1>", RegexOption.DOT_MATCHES_ALL)

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
     * Extracto para el tutor: el último pedido del estudiante y lo que la IA respondió después. Si eso no tiene que ver con
     * el archivo abierto, usa el último intercambio que sí lo menciona. Se recorta a [maxChars] para no gastar tokens.
     */
    fun excerpt(turns: List<SessionTurn>, fileName: String?, maxChars: Int = 5000): String? {
        if (turns.isEmpty()) return null
        var window = turns.subList(turns.indexOfLast { it.role == "user" }.coerceAtLeast(0), turns.size)
        val stem = fileName?.substringBeforeLast('.')?.takeIf { it.length >= 3 }
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

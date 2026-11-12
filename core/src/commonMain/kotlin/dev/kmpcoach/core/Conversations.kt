package dev.kmpcoach.core

/**
 * De qué IA viene una conversación o un plan. Todas se convierten a la misma lista de [SessionTurn] que una sesión de
 * Claude Code, así el extracto (el plan completo primero) y la guía de pasos funcionan igual para todas.
 */
enum class SourceKind(val id: String, val label: String, val brand: Brand) {
    CLAUDE_CODE("claude", "Claude Code", Brand.CLAUDE),
    ANTIGRAVITY("antigravity", "Antigravity", Brand.ANTIGRAVITY),
    COPILOT("copilot", "GitHub Copilot", Brand.COPILOT),
    PASTED("pasted", "Plan pegado", Brand.CUSTOM);

    companion object {
        /** Por la ruta del archivo: cada IA guarda sus conversaciones en su propia carpeta. */
        fun ofPath(path: String): SourceKind {
            val p = path.replace('\\', '/')
            return when {
                p == PASTED_PATH -> PASTED
                "/.gemini/" in p -> ANTIGRAVITY
                "/chatSessions/" in p -> COPILOT
                else -> CLAUDE_CODE
            }
        }

        /** «Ruta» del plan que pegaste a mano (no es un archivo). */
        const val PASTED_PATH = "mentor:plan-pegado"
    }
}

/**
 * Conversaciones de Antigravity (IDE, app y CLI). Sus `.pb` no se pueden leer, pero cada conversación deja una
 * transcripción en texto: `~/.gemini/antigravity…/brain/<id>/.system_generated/logs/transcript.jsonl`, un evento JSON por
 * línea. Es un formato interno de Google: se lee con tolerancia (lo que no se entiende se salta).
 *
 * Leer estos archivos de tu disco no usa el servicio de Antigravity, así que no choca con sus términos.
 */
object AntigravityTranscripts {
    /** Ruta del archivo dentro de cada conversación del «cerebro». */
    const val FILE = ".system_generated/logs/transcript.jsonl"

    private val REQUEST = Regex("<USER_REQUEST>([\\s\\S]*?)</USER_REQUEST>")
    private val METADATA = Regex("<ADDITIONAL_METADATA>[\\s\\S]*?</ADDITIONAL_METADATA>")

    /**
     * Tus mensajes (`USER_INPUT`, solo lo que escribiste) y las respuestas de Gemini (`PLANNER_RESPONSE` con texto).
     * Las llamadas a herramientas, el razonamiento interno y los avisos del sistema se ignoran.
     */
    fun parse(lines: Sequence<String>): List<SessionTurn> = lines.mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val e = runCatching { Json.parse(line) }.getOrNull().asMap()
        val content = e.str("content")?.trim().orEmpty()
        if (content.isEmpty()) return@mapNotNull null
        val at = e.str("created_at").orEmpty()
        when (e.str("type")) {
            "USER_INPUT" -> {
                val text = (REQUEST.find(content)?.groupValues?.get(1) ?: METADATA.replace(content, "")).trim()
                text.takeIf { it.isNotEmpty() }?.let { SessionTurn("user", it, at) }
            }
            "PLANNER_RESPONSE" -> SessionTurn("assistant", content, at)
            else -> null
        }
    }.toList()

    /** La conversación es de este proyecto: menciona su carpeta (el archivo que tenías abierto, las rutas que leyó). */
    fun belongsTo(text: String, basePath: String): Boolean {
        if (basePath.isBlank()) return false
        // Tildes escritas de las dos formas (la «ó» de «Facturación»): se comparan normalizadas.
        val t = nfc(text)
        return t.contains(nfc(basePath).trimEnd('/') + "/")
    }
}

/**
 * Conversaciones de GitHub Copilot en VS Code: `…/User/workspaceStorage/<proyecto>/chatSessions/<id>.json`. El
 * `workspace.json` de esa misma carpeta dice a qué proyecto pertenece. Formato interno de VS Code (versión 3): se lee
 * con tolerancia y, si cambia, simplemente no se encuentra nada.
 */
object CopilotSessions {
    /** Tus mensajes y las respuestas de Copilot (solo su texto: los bloques de código citados y referencias se saltan). */
    fun parse(json: String): List<SessionTurn> {
        val root = runCatching { Json.parse(json) }.getOrNull().asMap()
        return root["requests"].asList().map { it.asMap() }.flatMap { r ->
            val at = (r["timestamp"] as? Number)?.toLong()?.let(::isoFromMillis).orEmpty()
            val ask = r["message"].asMap().str("text")?.trim().orEmpty()
            val answer = r["response"].asList().map { it.asMap() }
                .filter { it["kind"] == null || it.str("kind") == "markdownContent" }
                .mapNotNull { it.str("value") ?: it["content"].asMap().str("value") }
                .joinToString("").trim()
            listOfNotNull(
                ask.takeIf { it.isNotEmpty() }?.let { SessionTurn("user", it, at) },
                answer.takeIf { it.isNotEmpty() }?.let { SessionTurn("assistant", it, at) },
            )
        }
    }

    /** La carpeta del proyecto según `workspace.json` (`{"folder": "file:///Users/…"}`), sin el `file://`. */
    fun folderOf(workspaceJson: String): String? =
        runCatching { Json.parse(workspaceJson) }.getOrNull().asMap().str("folder")
            ?.removePrefix("file://")?.let(::decodeUri)?.trimEnd('/')?.let(::nfc)

    private fun decodeUri(s: String): String {
        val out = StringBuilder()
        val bytes = ArrayList<Byte>()
        var i = 0
        fun flush() { if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().decodeToString()); bytes.clear() } }
        while (i < s.length) {
            if (s[i] == '%' && i + 2 <= s.lastIndex) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { bytes += v.toByte(); i += 3; continue }
            }
            flush(); out.append(s[i]); i++
        }
        flush()
        return out.toString()
    }
}

/** `2026-11-12T13:44:06.123Z` a partir de milisegundos desde 1970, sin depender de la plataforma. */
internal fun isoFromMillis(ms: Long): String {
    val day = IsoDate.of(1970, 1, 1).plusDays(floorDiv(ms, 86_400_000L))
    val rest = ms - floorDiv(ms, 86_400_000L) * 86_400_000L
    val h = rest / 3_600_000; val m = rest / 60_000 % 60; val s = rest / 1000 % 60; val milli = rest % 1000
    fun p(n: Long, w: Int) = n.toString().padStart(w, '0')
    return "${day}T${p(h, 2)}:${p(m, 2)}:${p(s, 2)}.${p(milli, 3)}Z"
}

private fun floorDiv(a: Long, b: Long): Long = if (a >= 0) a / b else -((-a + b - 1) / b)

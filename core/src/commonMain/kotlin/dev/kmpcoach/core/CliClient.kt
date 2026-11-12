package dev.kmpcoach.core

/**
 * Usar la suscripción en vez de una clave de API: el Mentor le pasa su prompt al programa de terminal de cada
 * servicio (Claude Code, Cursor) en modo «una pregunta, una respuesta» y lee su respuesta en JSON.
 * Es más lento que la API (el programa arranca un agente completo en cada llamada) y gasta la cuota del plan.
 *
 * Antigravity no está: sus términos (sección 6 y FAQ) prohíben en planes personales envolver `agy` dentro de otras
 * aplicaciones, con riesgo de suspender la cuenta. Para Gemini se usa una clave de Google AI Studio (lo que recomienda
 * su FAQ). Leer los planes de Antigravity del disco ([AntigravityPlans]) no está afectado: no se conecta a nada.
 *
 * Seguridad: el programa corre en una carpeta temporal vacía, nunca en tu proyecto, y sin permiso para editar ni
 * ejecutar comandos. Todo el código que necesita va dentro del prompt.
 */
enum class CliTool(
    val id: String,
    val label: String,
    val executable: String,
    /** Dónde suele instalarse, por si el IDE no ve el PATH de la terminal (pasa al abrirlo desde el Dock). */
    val usualPaths: List<String>,
    val loginHint: String,
) {
    CLAUDE(
        "claude", "Claude Code (suscripción Claude Pro/Max)", "claude",
        listOf("/opt/homebrew/bin/claude", "/usr/local/bin/claude", "~/.local/bin/claude", "~/.claude/local/claude"),
        "Abre una terminal, ejecuta `claude` una vez e inicia sesión con tu cuenta.",
    ),
    CURSOR(
        "cursor", "Cursor (suscripción de Cursor) · experimental", "cursor-agent",
        listOf("~/.local/bin/cursor-agent", "/opt/homebrew/bin/cursor-agent", "/usr/local/bin/cursor-agent"),
        "Abre una terminal, ejecuta `cursor-agent login` e inicia sesión con tu cuenta de Cursor.",
    ),
}

/** Lo que devolvió un programa: código de salida, salida estándar y salida de errores. */
class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Ejecuta programas en la plataforma: `ProcessBuilder` en el IDE (jvmMain), `child_process` en VS Code (jsMain).
 * [files] se escriben en [cwd] antes de arrancar (por ejemplo, el archivo con las instrucciones del Mentor).
 * Si la corrutina se cancela, el proceso se termina.
 */
interface ProcessRunner {
    suspend fun run(command: List<String>, stdin: String, files: Map<String, String>, timeoutSeconds: Long): ProcessResult

    /** Ruta del programa: la que configuraste, la del PATH o la de [CliTool.usualPaths]. Null si no está instalado. */
    fun find(tool: CliTool, configuredPath: String?): String?
}

expect fun defaultProcessRunner(): ProcessRunner

/** Cómo se llama a cada programa y cómo se lee lo que responde. Sin E/S: se prueba con un ejecutor falso. */
object CliProtocol {

    /** Archivo con las instrucciones del Mentor para `claude --system-prompt-file` (se escribe en la carpeta temporal). */
    const val SYSTEM_FILE = "mentor-system.md"

    /** Herramientas de Claude Code que se desactivan: el Mentor solo conversa, nunca toca archivos ni la terminal. */
    private val CLAUDE_NO_TOOLS = listOf(
        "Bash", "Edit", "MultiEdit", "Write", "Read", "Glob", "Grep", "LS", "WebFetch", "WebSearch", "Task", "NotebookEdit", "TodoWrite",
    )

    class Call(val args: List<String>, val stdin: String, val files: Map<String, String>)

    /** La conversación como texto, para los programas que reciben un solo mensaje. */
    fun transcript(messages: List<ChatMessage>): String {
        if (messages.size == 1) return messages.single().content
        return buildString {
            appendLine("Conversación hasta ahora (responde al último mensaje del estudiante):")
            messages.forEach { m ->
                appendLine()
                appendLine(if (m.role == "user") "ESTUDIANTE:" else "MENTOR (tú):")
                appendLine(m.content.trim())
            }
        }.trim()
    }

    fun call(tool: CliTool, exe: String, model: String, system: String, messages: List<ChatMessage>): Call {
        val m = model.trim()
        return when (tool) {
            CliTool.CLAUDE -> Call(
                listOf(exe, "-p", "--output-format", "json", "--no-session-persistence", "--system-prompt-file", SYSTEM_FILE,
                    "--disallowed-tools", CLAUDE_NO_TOOLS.joinToString(",")) + (if (m.isNotEmpty()) listOf("--model", m) else emptyList()),
                transcript(messages),
                mapOf(SYSTEM_FILE to system),
            )
            // Sin verificar en una máquina real: mismas convenciones que Claude Code, todo por stdin.
            CliTool.CURSOR -> Call(
                listOf(exe, "-p", "--output-format", "json") + (if (m.isNotEmpty()) listOf("--model", m) else emptyList()),
                "$system\n\n---\n\n${transcript(messages)}",
                emptyMap(),
            )
        }
    }

    /** Lee la respuesta del programa. Lanza [AiException] con un mensaje claro si falló o no inició sesión. */
    fun parse(tool: CliTool, r: ProcessResult): AiReply {
        val out = r.stdout.trim()
        return when (tool) {
            CliTool.CLAUDE, CliTool.CURSOR -> {
                val root = runCatching { Json.extract(out).asMap() }.getOrNull()
                if (root == null) {
                    // Cursor (sin verificar) podría responder en texto: si salió bien, se usa tal cual.
                    if (tool == CliTool.CURSOR && r.exitCode == 0 && out.isNotEmpty()) return AiReply(out, null, null)
                    fail(tool, r)
                }
                val text = root.str("result").orEmpty().trim()
                if (root["is_error"] == true || text.isEmpty()) fail(tool, r, text.ifEmpty { root.str("subtype") ?: "sin respuesta" })
                val u = root["usage"].asMap()
                val input = (u.int("input_tokens") ?: 0) + (u.int("cache_creation_input_tokens") ?: 0) + (u.int("cache_read_input_tokens") ?: 0)
                AiReply(text, AiUsage(input, u.int("output_tokens") ?: 0), null, truncated = root.str("stop_reason") == "max_tokens")
            }
        }
    }

    private fun fail(tool: CliTool, r: ProcessResult, detail: String? = null): Nothing {
        val all = (detail.orEmpty() + "\n" + r.stderr + "\n" + r.stdout).lowercase()
        val needsLogin = listOf("login", "log in", "sign in", "authenticat", "not logged", "unauthorized", "credential").any { it in all }
        val why = when {
            needsLogin -> "${tool.executable} no tiene una sesión iniciada. ${tool.loginHint}"
            !detail.isNullOrBlank() -> "${tool.executable} respondió con un error: ${detail.take(300)}"
            r.exitCode != 0 -> "${tool.executable} terminó con error (código ${r.exitCode}): ${r.stderr.trim().ifEmpty { r.stdout.trim() }.take(300)}"
            else -> "${tool.executable} no devolvió una respuesta que el Mentor pueda leer."
        }
        throw AiException(why)
    }

    /** `claude` no tiene lista de modelos, así que se ofrecen sus alias; para Cursor se escribe el nombre. */
    fun models(tool: CliTool): List<String> = when (tool) {
        CliTool.CLAUDE -> listOf("haiku", "sonnet", "opus")
        CliTool.CURSOR -> emptyList()
    }
}

/** Cliente de IA que usa la suscripción a través del programa de terminal. [AiConfig.baseUrl] es la ruta del programa (vacía = buscarlo). */
class CliClient(
    private val tool: CliTool,
    private val cfg: AiConfig,
    private val runner: ProcessRunner,
) : AiClient {

    private fun exe(): String = runner.find(tool, cfg.baseUrl.takeIf { it.isNotBlank() })
        ?: throw AiException("No encontré `${tool.executable}`. Instálalo o escribe su ruta en Ajustes › Mentor. ${tool.loginHint}")

    // El modelo «ignora» maxTokens: los CLI no lo exponen. completeFull solo reintenta si el CLI avisa de un corte.
    override suspend fun complete(system: String, messages: List<ChatMessage>, maxTokens: Int): AiReply {
        val call = CliProtocol.call(tool, exe(), cfg.model, system, messages)
        val r = runner.run(call.args, call.stdin, call.files, cfg.timeoutSeconds.coerceAtLeast(CLI_TIMEOUT))
        return CliProtocol.parse(tool, r)
    }

    override suspend fun listModels(): List<String> = CliProtocol.models(tool)

    companion object {
        /** Un agente completo tarda más que la API: unos segundos en arrancar más lo que piense. */
        const val CLI_TIMEOUT = 180L
    }
}

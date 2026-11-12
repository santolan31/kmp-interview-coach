package dev.kmpcoach.core

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private external fun require(module: String): dynamic

private val childProcess: dynamic by lazy { require("child_process") }
private val fs: dynamic by lazy { require("fs") }
private val pathMod: dynamic by lazy { require("path") }
private val os: dynamic by lazy { require("os") }
private val nodeProcess: dynamic by lazy { js("process") }

actual fun defaultProcessRunner(): ProcessRunner = NodeProcessRunner

/** Lo mismo que en el IDE, con `child_process.spawn` de Node: carpeta temporal vacía, stdin cerrado, se mata al cancelar. */
object NodeProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>, stdin: String, files: Map<String, String>, timeoutSeconds: Long): ProcessResult {
        val dir: String = fs.mkdtempSync(pathMod.join(os.tmpdir(), "mentor-cli-")) as String
        files.forEach { (name, text) -> fs.writeFileSync(pathMod.join(dir, name), text, "utf8") }
        try {
            return suspendCancellableCoroutine { cont ->
                val opts: dynamic = js("({})")
                opts.cwd = dir
                // En Windows los .cmd necesitan la consola para arrancar.
                opts.shell = (nodeProcess.platform as String) == "win32"
                val child = childProcess.spawn(command.first(), command.drop(1).toTypedArray(), opts)
                val out = StringBuilder()
                val err = StringBuilder()
                var done = false
                val timer = js("setTimeout")({
                    if (!done) {
                        done = true
                        child.kill()
                        cont.resumeWithException(AiNetworkException(AiNetworkException.Kind.TIMEOUT, "${command.first()} tardó más de $timeoutSeconds s"))
                    }
                }, (timeoutSeconds * 1000).toInt())
                // Texto, no bytes: así Node decodifica UTF-8 aunque una «ñ» quede partida entre dos trozos. (Con
                // chunk.toString() Kotlin/JS ve un arreglo de bytes y devuelve "[...]".)
                child.stdout.setEncoding("utf8")
                child.stderr.setEncoding("utf8")
                child.stdout.on("data") { chunk: String -> out.append(chunk) }
                child.stderr.on("data") { chunk: String -> err.append(chunk) }
                child.on("error") { e: dynamic ->
                    if (!done) {
                        done = true
                        js("clearTimeout")(timer)
                        cont.resumeWithException(AiException("No pude ejecutar ${command.first()}: ${e.message}"))
                    }
                }
                child.on("close") { code: dynamic ->
                    if (!done) {
                        done = true
                        js("clearTimeout")(timer)
                        cont.resume(ProcessResult((code as? Int) ?: -1, out.toString(), err.toString()))
                    }
                }
                child.stdin.end(stdin, "utf8")
                cont.invokeOnCancellation { if (!done) { done = true; child.kill() } }
            }
        } finally {
            runCatching { fs.rmSync(dir, js("({ recursive: true, force: true })")) }
        }
    }

    override fun find(tool: CliTool, configuredPath: String?): String? {
        val home = os.homedir() as String
        fun expand(p: String) = if (p.startsWith("~/")) home + p.drop(1) else p
        fun executable(p: String): Boolean = runCatching { fs.accessSync(p, fs.constants.X_OK); fs.statSync(p).isFile() as Boolean }.getOrDefault(false)
        configuredPath?.let { expand(it) }?.takeIf(::executable)?.let { return it }
        val windows = (nodeProcess.platform as String) == "win32"
        val names = if (windows) listOf("${tool.executable}.exe", "${tool.executable}.cmd", tool.executable) else listOf(tool.executable)
        val sep = if (windows) ";" else ":"
        val dirs = ((nodeProcess.env.PATH as String?) ?: "").split(sep).filter { it.isNotBlank() }
        val candidates = dirs.flatMap { d -> names.map { pathMod.join(d, it) as String } } + tool.usualPaths.map(::expand)
        return candidates.firstOrNull(::executable)
    }
}

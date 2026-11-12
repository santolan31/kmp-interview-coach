package dev.kmpcoach.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

actual fun defaultProcessRunner(): ProcessRunner = JvmProcessRunner

/**
 * Ejecuta el programa en una carpeta temporal vacía (nunca en el proyecto) y la borra al terminar. Lee la salida y los
 * errores a la vez para que el programa no se bloquee con un búfer lleno. Si la corrutina se cancela (por ejemplo,
 * «Detener» la revisión) o se pasa del tiempo, el proceso se termina.
 */
object JvmProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>, stdin: String, files: Map<String, String>, timeoutSeconds: Long): ProcessResult =
        runInterruptible(Dispatchers.IO) {
            val dir = Files.createTempDirectory("mentor-cli-").toFile()
            var process: Process? = null
            try {
                files.forEach { (name, text) -> File(dir, name).writeText(text) }
                val p = ProcessBuilder(command).directory(dir).start()
                process = p
                val out = StringBuilder()
                val err = StringBuilder()
                val readers = listOf(
                    thread(isDaemon = true) { out.append(p.inputStream.bufferedReader().readText()) },
                    thread(isDaemon = true) { err.append(p.errorStream.bufferedReader().readText()) },
                )
                // Escribir y cerrar stdin: si queda abierto, claude espera 3 s por si llega más.
                p.outputStream.bufferedWriter().use { it.write(stdin) }
                if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    throw AiNetworkException(AiNetworkException.Kind.TIMEOUT, "${command.first()} tardó más de $timeoutSeconds s")
                }
                readers.forEach { it.join(5_000) }
                ProcessResult(p.exitValue(), out.toString(), err.toString())
            } finally {
                process?.takeIf { it.isAlive }?.destroyForcibly()
                dir.deleteRecursively()
            }
        }

    override fun find(tool: CliTool, configuredPath: String?): String? {
        val home = System.getProperty("user.home")
        fun expand(p: String) = if (p.startsWith("~/")) home + p.drop(1) else p
        configuredPath?.let { p -> File(expand(p)).takeIf { it.canExecute() }?.let { return it.path } }
        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val names = if (windows) listOf("${tool.executable}.exe", "${tool.executable}.cmd", tool.executable) else listOf(tool.executable)
        val fromPath = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
            .flatMap { dir -> names.map { File(dir, it) } }
        return (fromPath + tool.usualPaths.map { File(expand(it)) }).firstOrNull { it.isFile && it.canExecute() }?.path
    }
}

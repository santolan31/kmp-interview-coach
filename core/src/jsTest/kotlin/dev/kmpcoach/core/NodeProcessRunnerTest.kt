package dev.kmpcoach.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** El ejecutor de procesos de VS Code: lo que entra por stdin debe llegar al programa, y su salida volver completa. */
class NodeProcessRunnerTest {
    private val node: String = js("process.execPath") as String

    @Test
    fun stdinReachesTheProgramAndStdoutComesBack() = runTest {
        // Un «eco»: devuelve en mayúsculas lo que recibe, y lee el archivo que el ejecutor escribió en la carpeta temporal.
        val script = "let s='';process.stdin.on('data',d=>s+=d);process.stdin.on('end',()=>{" +
            "process.stdout.write(s.toUpperCase()+'|'+require('fs').readFileSync('extra.txt','utf8'))})"
        val r = NodeProcessRunner.run(listOf(node, "-e", script), "hola ñandú", mapOf("extra.txt" to "archivo"), 30)
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("HOLA ÑANDÚ|archivo", r.stdout)
    }

    @Test
    fun aLongPromptArrivesWhole() = runTest {
        val big = "x".repeat(200_000)
        val r = NodeProcessRunner.run(listOf(node, "-e", "let n=0;process.stdin.on('data',d=>n+=d.length);process.stdin.on('end',()=>process.stdout.write(String(n)))"), big, emptyMap(), 30)
        assertTrue(r.stdout.trim() == "200000", "llegaron ${r.stdout} caracteres")
    }
}

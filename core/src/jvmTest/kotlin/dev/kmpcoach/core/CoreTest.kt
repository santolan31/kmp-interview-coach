package dev.kmpcoach.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Los retos que trae el plugin de Android Studio: solo JVM, porque el paquete vive en los recursos del plugin. */
class CoreTest {
    @Test
    fun bundledPackAndAnalyzer() {
        // Gradle corre los tests con el módulo :core como carpeta de trabajo; el paquete está en el plugin (la raíz).
        val json = File("../src/main/resources/coach/challenges.json").readText()
        val fails = CoreChecks.run(json)
        assertTrue(fails.isEmpty(), fails.joinToString("\n"))
    }
}

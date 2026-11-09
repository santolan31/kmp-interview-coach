package dev.kmpcoach.core

import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test
    fun bundledPackAndAnalyzer() {
        val json = javaClass.getResourceAsStream("/coach/challenges.json")!!.bufferedReader().readText()
        val fails = CoreChecks.run(json)
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }
}

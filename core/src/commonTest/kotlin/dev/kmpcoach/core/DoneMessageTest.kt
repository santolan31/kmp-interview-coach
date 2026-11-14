package dev.kmpcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** El aviso a la IA del plan cuando terminas todos los pasos de la guía. */
class DoneMessageTest {

    private fun edit(path: String) = StepEdit(path, 1, emptyList())
    private val s1 = GuideStep("5.1", "quitar el color fijo", listOf(edit("/p/MacoTopBar.kt")), null)
    private val s2 = GuideStep("5.2", "correr la app", emptyList(), "mira el Home")
    private val s3 = GuideStep("5.4", "borrar la acción que ya nadie usa", listOf(edit("/p/HomeAction.kt"), edit("/p/HomeViewModel.kt")), null)

    @Test
    fun startsWithRevisaAndSaysHowEachStepWasChecked() {
        val m = StepPrompts.doneMessage(listOf(s1, s2, s3), confirmed = setOf(s3.id), manual = setOf(s2.id))
        assertTrue(m.startsWith("Revisa: terminé los 3 pasos de tu plan:"))
        assertTrue("- Paso 5.1: quitar el color fijo (el Mentor no ve nada pendiente, pero no lo confirmó)" in m)
        assertTrue("- Paso 5.2: correr la app (lo marqué como hecho)" in m)
        assertTrue("- Paso 5.4: borrar la acción que ya nadie usa (el Mentor lo confirmó)" in m)
        assertTrue("Archivos que cambié: MacoTopBar.kt, HomeAction.kt, HomeViewModel.kt." in m)
        // Pide validar el código de forma explícita (sirve con cualquier IA) y no pregunta por el siguiente paso.
        assertTrue("valida mi trabajo: compila" in m && m.endsWith("No lo corrijas por mí: dame pistas."))
        assertFalse("siguiente paso" in m)
    }

    @Test
    fun oneStepIsSingular() {
        assertTrue(StepPrompts.doneMessage(listOf(s1), emptySet(), emptySet()).startsWith("Revisa: terminé el paso de tu plan:"))
    }

    @Test
    fun theConversationIsNamedByTheRequestThatAskedForThePlan() {
        val turns = listOf(
            SessionTurn("user", "Dame los pasos   para limpiar el Home", "2026-11-12T10:00:00Z"),
            SessionTurn("assistant", "## Paso 5.1 …", "2026-11-12T10:01:00Z"),
            SessionTurn("user", "ya hice el 5.1", "2026-11-12T11:00:00Z"),
        )
        assertEquals("Dame los pasos para limpiar el Home", ClaudeSessions.requestBefore(turns, "2026-11-12T10:01:00Z"))
        assertNull(ClaudeSessions.requestBefore(turns, "2026-11-12T09:00:00Z"))
    }
}

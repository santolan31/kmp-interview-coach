package dev.kmpcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Planes de Antigravity, con el formato real de sus implementation_plan.md. */
class PlansTest {

    private val plan = """
        # Implement Login Logic (JWT)

        ## Proposed Changes

        ### user
        #### [NEW] [LoginRequest.kt](file:///p/user/LoginRequest.kt)
        DTO con email y contraseña.

        #### [MODIFY] [AuthService.kt](file:///p/user/AuthService.kt)
        ```diff
         fun login(email: String, password: String): String {
        -    TODO()
        +    // TODO 1: busca el usuario y compara la contraseña
        +    val user = TODO()
         }
        ```

        #### [MODIFY] `app/build.gradle.kts`
        ```kotlin
        // #### [MODIFY] dentro de un bloque de código no es un cambio
        ```

        ## Verification Plan
        ### Manual Verification
    """.trimIndent()

    @Test
    fun everyChangedFileBecomesAStepAndItsDiffItsEdit() {
        assertTrue(AntigravityPlans.isPlan(plan))
        val steps = StepParser.parse(AntigravityPlans.toSteps(plan), "/p")
        assertEquals(listOf("1", "2", "3"), steps.map { it.number })
        assertEquals(listOf("Crear LoginRequest.kt", "Modificar AuthService.kt", "Modificar app/build.gradle.kts"), steps.map { it.title })
        val edit = steps[1].edits.single()
        assertEquals("/p/user/AuthService.kt", edit.path)
        assertTrue(edit.isSkeleton)
        assertEquals(listOf("TODO 1"), edit.todos.map { it.first })
        assertTrue(steps[0].edits.isEmpty() && steps[2].edits.isEmpty(), "sin diff: un paso para marcar a mano")
    }

    @Test
    fun aPlanAlreadyInStepsStaysTheSame() {
        val md = "## Paso 5.1: algo\n[A.kt:1](file:///p/A.kt#L1)"
        assertEquals(md, AntigravityPlans.toSteps(md))
    }

    @Test
    fun titleMentionsAndExcerpt() {
        assertEquals("Implement Login Logic (JWT)", AntigravityPlans.title(plan))
        assertTrue(AntigravityPlans.mentions(plan, "user/AuthService.kt"))
        assertFalse(AntigravityPlans.mentions(plan, "MacoTopBar.kt"))
        assertFalse(AntigravityPlans.isPlan("# Notas\nnada que cambiar"))
        val e = AntigravityPlans.excerpt(plan, maxChars = 100)
        assertTrue(e.startsWith("IA (plan de Antigravity: «Implement Login Logic (JWT)»):") && e.endsWith("…"))
    }
}

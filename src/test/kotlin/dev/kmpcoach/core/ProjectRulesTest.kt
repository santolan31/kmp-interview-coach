package dev.kmpcoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRulesTest {

    private val agents = """
        # Maco — Reglas para agentes

        ## Cómo trabajar conmigo

        ### Idioma y tono
        - Responde siempre en español.

        ## Arquitectura

        - Kotlin Multiplatform + Compose Multiplatform.

        ## Convenciones de UI

        - Colores del tema: `MaterialTheme.colorScheme.extended`.

        ## Git y seguridad

        - No hacer commits sin una orden explícita.
    """.trimIndent()

    @Test
    fun keepsOnlyArchitectureAndConventions() {
        val text = ProjectRules.extract(agents)!!
        assertTrue("## Arquitectura" in text)
        assertTrue("colorScheme.extended" in text)
        assertFalse("lo de cómo trabajar es para la otra IA", "Responde siempre" in text)
        assertFalse("No hacer commits" in text)
    }

    @Test
    fun withoutThoseSectionsUsesTheWholeFileAndCrops() {
        assertEquals("Usa Room.", ProjectRules.extract("Usa Room."))
        assertNull(ProjectRules.extract("   "))
        val long = (1..200).joinToString("\n") { "- regla número $it del proyecto" }
        val cropped = ProjectRules.extract(long, maxChars = 300)!!
        assertTrue(cropped.length < 330)
        assertTrue(cropped.endsWith("… (recortado)"))
    }

    @Test
    fun promptSaysTheRulesAndThePlanCommand() {
        assertEquals("", ProjectRules.context(null))
        val c = ProjectRules.context(ProjectRules.Found("AGENTS.md", "- Colores del tema."))
        assertTrue("AGENTS.md" in c && "Colores del tema" in c)
        assertTrue("No propongas estructura nueva" in c)
    }

    // ---------- comentarios que hablan de un código que ya cambió ----------

    @Test
    fun renamingTheVariableResolvesTheComment() {
        val msg = "`isEnable` suena a verbo. Para un booleano de estado se lee mejor `isEnabled` o `isNotificationEnabled`."
        val before = listOf("        }", "        val isEnable = onNotificationClick != null", "        Icon(", "            imageVector = Icons.Default.Notifications,")
        val after = before.map { it.replace("isEnable ", "isNotificationEnabled ") }
        assertTrue(StaleComments.outdated(msg, before, after))
        assertFalse("sin cambios sigue vigente", StaleComments.outdated(msg, before, before))
    }

    @Test
    fun changingTheColorResolvesTheQuestionAboutIt() {
        // Sin backticks: `primary` cuenta porque en el código se usa como `.primary`.
        val msg = "Antes la campana activa usaba textPlaceholder y ahora usa primary. ¿Por qué ese cambio?"
        val before = listOf("contentDescription = \"Notificaciones\",", "tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.extended.textDisabled,")
        val after = listOf(before[0], "tint = if (on) MaterialTheme.colorScheme.extended.textPlaceholder else MaterialTheme.colorScheme.extended.textDisabled,")
        assertTrue(StaleComments.outdated(msg, before, after))
    }

    @Test
    fun anUnrelatedEditKeepsTheComment() {
        val msg = "`textPlaceholder` es el color correcto aquí."
        val before = listOf("tint = MaterialTheme.colorScheme.extended.textPlaceholder,", "modifier = Modifier.size(16.dp)")
        val after = listOf(before[0], "modifier = Modifier.size(20.dp)")
        assertFalse(StaleComments.outdated(msg, before, after))
        assertFalse("sin el código original no se decide nada", StaleComments.outdated(msg, emptyList(), after))
    }
}

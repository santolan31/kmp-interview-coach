package dev.kmpcoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HintsTest {

    // El ejercicio real que motivó el plugin.
    private val student = """
        data class User(val name: String, val age: Int, val active: Boolean)

        fun activeAdultNames(users: List<User>): List<String> {
            if (edad <= 18 && active) {
            }
        }
    """.trimIndent()

    @Test
    fun guardAllowsNamesButNotCodeInEarlyLevels() {
        assertFalse(CodeGuard.leaks("¿De dónde sale `edad`? Dentro de la función solo existe `users`.", student, HintLevel.QUESTION))
        assertTrue(CodeGuard.leaks("Prueba con `users.filter { it.active }`.", student, HintLevel.QUESTION))
        assertTrue(CodeGuard.leaks("Mira:\n```kotlin\nval x = 1\n```", student, HintLevel.CONCEPT))
        assertTrue(CodeGuard.leaks("1. Filtra con `it.age >= 18`.", student, HintLevel.STEPS))
        assertFalse("citar su propio código no es regalarle código", CodeGuard.leaks("¿Qué compara `edad <= 18`?", student, HintLevel.QUESTION))
    }

    @Test
    fun guardAllowsAnalogousExampleButNotTheSolution() {
        val analog = "Busca `filter`. Ejemplo: `listOf(1, 5, 8).filter { it > 4 }` da `[5, 8]`."
        assertFalse(CodeGuard.leaks(analog, student, HintLevel.TOOL))
        assertTrue(CodeGuard.leaks("Usa `users.filter { it.active && it.age >= 18 }`.", student, HintLevel.TOOL))
        assertTrue(CodeGuard.leaks("```kotlin\nusers\n  .filter { it.active }\n  .map { it.name }\n```", student, HintLevel.TOOL))
    }

    @Test
    fun redactHidesOnlyTheLeak() {
        val out = CodeGuard.redact("Busca `filter`: `users.filter { it.active && it.age >= 18 }`.", student, HintLevel.TOOL)
        assertEquals("Busca `filter`: ${CodeGuard.HIDDEN}.", out)
        assertEquals("Sin código.", CodeGuard.redact("Sin código.", student, HintLevel.QUESTION))
    }

    @Test
    fun stuckTrackerSpeaksOncePerStuckEpisode() {
        val t = StuckTracker(45_000)
        assertNull("sin errores no hay nada que hacer", t.update(0, 0))
        assertEquals(45_000L, t.update(3, 0))
        assertEquals(15_000L, t.update(3, 30_000))
        assertEquals(0L, t.update(3, 45_000))
        t.markSpoken()
        assertNull("ya habló en este atasco", t.update(3, 50_000))
        assertEquals("bajar los errores es avanzar: el reloj empieza de nuevo", 45_000L, t.update(2, 60_000))
        assertEquals("subir los errores no cuenta como avance", 35_000L, t.update(5, 70_000))
        assertNull(t.update(0, 80_000))
        assertEquals("tras resolverlo, el siguiente atasco empieza de cero", 45_000L, t.update(1, 90_000))
    }

    @Test
    fun parseHintJsonAndPlainText() {
        val h = HintParser.parse("""{"linea":4,"problema":"edad no existe","pista":"¿De dónde sale `edad`?","concepto":"parámetros"}""", 6, HintLevel.QUESTION, 1)
        assertEquals(Hint(HintLevel.QUESTION, 4, "edad no existe", "¿De dónde sale `edad`?", "parámetros"), h)
        val plain = HintParser.parse("¿Qué necesitas hacer con cada usuario?", 6, HintLevel.CONCEPT, 3)
        assertEquals(3, plain.line)
        assertEquals("¿Qué necesitas hacer con cada usuario?", plain.text)
        assertEquals(6, HintParser.parse("""{"linea":99,"pista":"x"}""", 6, HintLevel.QUESTION, 1).line)
        assertEquals(listOf("¿Qué devuelve?", "¿Y con una lista vacía?"), HintParser.questions("""{"preguntas":["¿Qué devuelve?","¿Y con una lista vacía?"]}"""))
    }

    @Test
    fun ladderOrder() {
        assertEquals(HintLevel.CONCEPT, HintLevel.QUESTION.next)
        assertEquals(HintLevel.STEPS, HintLevel.TOOL.next)
        assertNull(HintLevel.STEPS.next)
        assertEquals(4, HintLevel.STEPS.number)
    }

    @Test
    fun ideProblemsAndTodo() {
        val problems = listOf(IdeProblem(1, "Unresolved reference 'ViewModel'."), IdeProblem(4, "Expecting '}'"))
        assertEquals(listOf(problems[1]), IdeProblems.relevant(problems, isScratch = true))
        assertEquals(problems, IdeProblems.relevant(problems, isScratch = false))
        assertTrue(IdeProblems.hasTodo("fun a(): Int = TODO()"))
        assertFalse(IdeProblems.hasTodo("// TODO() pendiente\nval s = \"TODO()\""))
    }

    @Test
    fun handoffCarriesEverythingClaudeNeeds() {
        val hints = listOf(Hint(HintLevel.QUESTION, 4, "", "¿De dónde sale `edad`?", null))
        val msg = HintPrompts.handoff("feature/home/Users.kt", student, listOf(IdeProblem(4, "Unresolved reference 'edad'.")), hints, null)
        assertTrue(msg.contains("Archivo: feature/home/Users.kt"))
        assertTrue(msg.contains("- Línea 4: Unresolved reference 'edad'."))
        assertTrue(msg.contains("- Pregunta (línea 4): ¿De dónde sale `edad`?"))
        assertTrue(msg.contains("   4|     if (edad <= 18 && active) {"))
    }

    @Test
    fun excerptOfLongFileCentersOnTheProblem() {
        val code = (1..200).joinToString("\n") { "val v$it = $it" }
        val ex = HintPrompts.excerpt(code, 100).lines()
        assertEquals(31, ex.size)
        assertTrue(ex.first().startsWith("  85|"))
        assertTrue(ex.last().startsWith(" 115|"))
    }
}

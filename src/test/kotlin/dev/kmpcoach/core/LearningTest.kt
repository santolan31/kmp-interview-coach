package dev.kmpcoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LearningTest {
    private val today = LocalDate.of(2026, 11, 10)

    @Test
    fun nudgeOnlyWhenNothingChangedRecently() {
        assertFalse("la primera pista nunca se frena", HelpPolicy.shouldNudge(null, "a", 0, 1_000))
        assertTrue(HelpPolicy.shouldNudge("val a = 1", "val a  =  1", 0, 10_000))
        assertFalse("si cambió el código, ya lo intentó", HelpPolicy.shouldNudge("val a = 1", "val a = 2", 0, 10_000))
        assertFalse("si pasó un rato pensando, se le da", HelpPolicy.shouldNudge("val a = 1", "val a = 1", 0, HelpPolicy.THINK_MS))
    }

    @Test
    fun masteryNeedsSolvingWithLittleHelp() {
        var s = ConceptStats()
        s = Mastery.record(s, hintsUsed = 4, today)
        assertFalse(Mastery.isMastered(s))
        s = Mastery.record(s, hintsUsed = 1, today)
        assertFalse(Mastery.isMastered(s))
        s = Mastery.record(s, hintsUsed = 0, today)
        assertTrue(Mastery.isMastered(s))
        assertEquals(ConceptStats(5, 3, 2, "2026-11-10"), s)
        assertEquals(s, ConceptStats.fromMap(Json.parse(Json.write(s.toMap())).asMap()))
        assertEquals("filter", Mastery.key(" `Filter`. "))
    }

    @Test
    fun leitnerSchedulesReviews() {
        var cards = Leitner.add(emptyList(), "Filter", today)
        cards = Leitner.add(cards, "filter", today)
        assertEquals(listOf(ReviewCard("filter", 1, "2026-11-11")), cards)
        assertNull("nada vence hoy", Leitner.due(cards, today))
        val card = Leitner.due(cards, today.plusDays(1))!!
        val right = Leitner.answer(card, correct = true, today.plusDays(1))
        assertEquals(ReviewCard("filter", 2, "2026-11-13"), right)
        assertEquals(ReviewCard("filter", 1, "2026-11-14"), Leitner.answer(right.copy(box = 5), correct = false, LocalDate.of(2026, 11, 13)))
        assertEquals(6, Leitner.answer(right.copy(box = 6), correct = true, today).box)
        assertEquals(right, ReviewCard.fromMap(Json.parse(Json.write(right.toMap())).asMap()))
    }

    @Test
    fun parseTraceAndEvaluation() {
        val q = LearningParser.trace("""{"pregunta":"¿Qué devuelve con []?","respuesta":"[]","porque":"filter no agrega nada"}""")
        assertEquals(TraceQuestion("¿Qué devuelve con []?", "[]", "filter no agrega nada"), q)
        assertEquals("texto suelto", LearningParser.trace("texto suelto").question)
        val ok = LearningParser.evaluation("""{"correcto":true,"parcial":false,"feedback":"Exacto.","pista":""}""")
        assertTrue(ok.correct)
        val partial = LearningParser.evaluation("""{"correcto":"false","parcial":true,"feedback":"Vas bien.","pista":"¿Y el orden?"}""")
        assertEquals(Evaluation(false, true, "Vas bien.", "¿Y el orden?"), partial)
    }

    @Test
    fun hintPromptCarriesGoalAndMastery() {
        val p = HintPrompts.hintUser("val a = 1", "A.kt", emptyList(), emptyList(), null, HintLevel.QUESTION, "Plan «X», paso 1 de 2: Repositorio", listOf("filter"))
        assertTrue(p.contains("Lo que el estudiante intenta hacer ahora: Plan «X», paso 1 de 2: Repositorio"))
        assertTrue(p.contains("ya domina (si la pista es sobre uno de ellos, sé más breve y exigente): filter"))
    }
}

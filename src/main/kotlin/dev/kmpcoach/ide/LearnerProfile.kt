package dev.kmpcoach.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import dev.kmpcoach.core.ConceptStats
import dev.kmpcoach.core.Json
import dev.kmpcoach.core.Leitner
import dev.kmpcoach.core.Mastery
import dev.kmpcoach.core.ReviewCard
import dev.kmpcoach.core.asList
import dev.kmpcoach.core.asMap
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lo que el plugin sabe de ti como estudiante, en todos tus proyectos: qué conceptos dominas (para retirar la ayuda)
 * y qué conceptos te toca repasar. Se guarda en el IDE, no en el proyecto.
 */
@Service(Service.Level.APP)
@State(name = "KmpInterviewCoachLearner", storages = [Storage("kmpInterviewCoachLearner.xml")])
class LearnerProfile : SimplePersistentStateComponent<LearnerProfile.Data>(Data()) {

    class Data : BaseState() {
        /** JSON {concepto: ConceptStats} */
        var concepts by string("{}")
        /** JSON [ReviewCard] */
        var cards by string("[]")
        var lastReviewDay by string("")
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(parent: Disposable, l: () -> Unit) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    private fun fire() = listeners.forEach { it() }

    fun concepts(): Map<String, ConceptStats> = runCatching {
        Json.parse(state.concepts ?: "{}").asMap().mapValues { (_, v) -> ConceptStats.fromMap(v.asMap()) }
    }.getOrDefault(emptyMap())

    fun cards(): List<ReviewCard> = runCatching {
        Json.parse(state.cards ?: "[]").asList().mapNotNull { ReviewCard.fromMap(it.asMap()) }
    }.getOrDefault(emptyList())

    fun mastered(): List<String> = concepts().filterValues(Mastery::isMastered).keys.sorted()

    /** Resolviste un problema sobre [concept] usando [hintsUsed] pistas. */
    @Synchronized
    fun recordSolved(concept: String, hintsUsed: Int) {
        val k = Mastery.key(concept).ifEmpty { return }
        val all = concepts().toMutableMap()
        all[k] = Mastery.record(all[k] ?: ConceptStats(), hintsUsed, LocalDate.now())
        state.concepts = Json.write(all.mapValues { it.value.toMap() })
        fire()
    }

    /** Agrega [concept] al repaso espaciado (si ya está, no cambia nada). */
    @Synchronized
    fun addReview(concept: String) {
        val updated = Leitner.add(cards(), concept, LocalDate.now())
        state.cards = Json.write(updated.map { it.toMap() })
        fire()
    }

    fun dueReview(): ReviewCard? = Leitner.due(cards(), LocalDate.now())
    fun dueCount(): Int = cards().count { !LocalDate.parse(it.due).isAfter(LocalDate.now()) }
    fun reviewedToday(): Boolean = state.lastReviewDay == LocalDate.now().toString()

    @Synchronized
    fun answerReview(card: ReviewCard, correct: Boolean) {
        val today = LocalDate.now()
        val updated = cards().map { if (it.concept == card.concept) Leitner.answer(it, correct, today) else it }
        state.cards = Json.write(updated.map { it.toMap() })
        state.lastReviewDay = today.toString()
        fire()
    }

    companion object {
        fun getInstance(): LearnerProfile = service()
    }
}

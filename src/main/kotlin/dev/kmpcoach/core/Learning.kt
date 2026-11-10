package dev.kmpcoach.core

import java.time.LocalDate

/**
 * Abuso de ayuda (Aleven y Koedinger): pedir pistas seguidas hasta llegar a la respuesta sin intentar nada.
 * Si el código no cambió desde la última pista y pasó poco tiempo, antes de dar otra se pide un intento.
 */
object HelpPolicy {
    const val THINK_MS = 45_000L

    fun shouldNudge(textAtLastHint: String?, currentText: String, lastHintAt: Long, now: Long): Boolean =
        textAtLastHint != null && squash(textAtLastHint) == squash(currentText) && now - lastHintAt < THINK_MS

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }
}

/** Historial de un concepto: cuántas veces lo resolviste y cuánta ayuda necesitaste. */
data class ConceptStats(val hints: Int = 0, val solved: Int = 0, val solvedAlone: Int = 0, val lastSeen: String = "") {
    fun toMap(): Map<String, Any?> = mapOf("hints" to hints, "solved" to solved, "alone" to solvedAlone, "last" to lastSeen)

    companion object {
        fun fromMap(m: Map<String, Any?>) = ConceptStats(m.int("hints") ?: 0, m.int("solved") ?: 0, m.int("alone") ?: 0, m.str("last").orEmpty())
    }
}

/**
 * Ayuda que se retira (efecto de inversión de la experiencia): lo que ya dominas recibe pistas más breves.
 * "Resolver solo" es resolver el problema con una pista como mucho (la pregunta inicial).
 */
object Mastery {
    fun record(s: ConceptStats, hintsUsed: Int, today: LocalDate): ConceptStats = s.copy(
        hints = s.hints + hintsUsed,
        solved = s.solved + 1,
        solvedAlone = s.solvedAlone + if (hintsUsed <= 1) 1 else 0,
        lastSeen = today.toString(),
    )

    fun isMastered(s: ConceptStats): Boolean = s.solvedAlone >= 2 || (s.solved >= 3 && s.hints <= s.solved)

    /** Normaliza el nombre del concepto que devuelve el modelo (`filter`, «Filter», "la función filter") para agrupar. */
    fun key(concept: String): String = concept.trim().trim('`', '«', '»', '"', '\'', '.').lowercase().take(40)
}

/** Tarjeta de repaso de un concepto. [box] va de 1 a 6; cuanto más alta, más días hasta el siguiente repaso. */
data class ReviewCard(val concept: String, val box: Int, val due: String) {
    fun toMap(): Map<String, Any?> = mapOf("concept" to concept, "box" to box, "due" to due)

    companion object {
        fun fromMap(m: Map<String, Any?>): ReviewCard? {
            val c = m.str("concept") ?: return null
            return ReviewCard(c, (m.int("box") ?: 1).coerceIn(1, Leitner.INTERVALS.size), m.str("due") ?: LocalDate.now().toString())
        }
    }
}

/**
 * Repaso espaciado con cajas de Leitner: si aciertas, el concepto vuelve a preguntarse en más días (1, 2, 4, 8, 16, 32);
 * si fallas, vuelve a la primera caja y aparece mañana.
 */
object Leitner {
    val INTERVALS = listOf(1, 2, 4, 8, 16, 32)

    fun add(cards: List<ReviewCard>, concept: String, today: LocalDate): List<ReviewCard> {
        val k = Mastery.key(concept)
        if (k.isEmpty() || cards.any { it.concept == k }) return cards
        return cards + ReviewCard(k, 1, today.plusDays(1).toString())
    }

    /** El repaso pendiente más antiguo, o null si hoy no toca ninguno. */
    fun due(cards: List<ReviewCard>, today: LocalDate): ReviewCard? =
        cards.filter { !LocalDate.parse(it.due).isAfter(today) }.minByOrNull { it.due }

    fun answer(card: ReviewCard, correct: Boolean, today: LocalDate): ReviewCard {
        val box = if (correct) (card.box + 1).coerceAtMost(INTERVALS.size) else 1
        val days = if (correct) INTERVALS[box - 1] else 1
        return card.copy(box = box, due = today.plusDays(days.toLong()).toString())
    }
}

/** Pregunta de "predecir" sobre un bloque: una entrada concreta y lo que debería devolver o hacer. */
data class TraceQuestion(val question: String, val answer: String, val why: String, val concept: String = "")

/** Evaluación de la respuesta del estudiante. Si no es correcta, [hint] lo orienta sin darle la respuesta. */
data class Evaluation(val correct: Boolean, val partial: Boolean, val feedback: String, val hint: String)

object LearningParser {
    fun trace(text: String): TraceQuestion {
        val m = runCatching { Json.extract(text).asMap() }.getOrDefault(emptyMap())
        val q = m.str("pregunta")?.trim().orEmpty().ifEmpty { text.trim() }
        return TraceQuestion(q, m.str("respuesta")?.trim().orEmpty(), m.str("porque")?.trim().orEmpty(), m.str("concepto")?.trim().orEmpty())
    }

    fun evaluation(text: String): Evaluation {
        val m = runCatching { Json.extract(text).asMap() }.getOrDefault(emptyMap())
        val correct = m["correcto"] == true || m.str("correcto").equals("true", ignoreCase = true)
        val partial = !correct && (m["parcial"] == true || m.str("parcial").equals("true", ignoreCase = true))
        return Evaluation(correct, partial, m.str("feedback")?.trim().orEmpty().ifEmpty { text.trim() }, m.str("pista")?.trim().orEmpty())
    }
}

object LearningPrompts {
    private fun base(o: PromptOptions) =
        "Eres un tutor de programación de un estudiante de nivel ${o.level.label} de Android y Kotlin Multiplatform. " +
            "Tu objetivo es comprobar que ENTIENDE el código, incluido el que le generó una IA, sin darle las respuestas."

    /** Trace-and-Predict: una entrada concreta y una pregunta de respuesta corta sobre lo que hace el bloque. */
    fun traceSystem(o: PromptOptions) = """
${base(o)}

Escribe UNA pregunta para que el estudiante prediga qué hace el bloque de código con una entrada concreta y pequeña
(por ejemplo: "si `items` es [A(precio 10), B(precio 0)], ¿qué devuelve?" o "¿qué valor tiene `state.isLoading` después de la línea 14?").
- La respuesta debe ser corta: un valor, una lista o una frase.
- Si el bloque es de UI o de configuración, pregunta qué se ve o qué pasa cuando ocurre algo (un clic, un error de red).
- No des pistas sobre la respuesta en la pregunta.
- Código entre backticks. ${Prompts.lang(o)}

Responde solo con JSON: {"pregunta":"...","respuesta":"la respuesta correcta","porque":"una o dos frases que expliquen por qué","concepto":"el concepto clave que pone a prueba, por ejemplo StateFlow"}
""".trim()

    fun traceUser(block: CodeBlock, goal: String?) = buildString {
        if (goal != null) appendLine("Contexto: $goal\n")
        appendLine("Archivo ${block.path}, líneas ${block.startLine}–${block.endLine}:")
        appendLine("```kotlin"); appendLine(block.text); appendLine("```")
        append("Escribe la pregunta y responde solo con el JSON.")
    }

    /** Evalúa una respuesta libre comparándola con el código (y con la respuesta esperada, si la hay). */
    fun evaluateSystem(o: PromptOptions) = """
${base(o)}

Evalúa la respuesta del estudiante con generosidad en la forma y rigor en el fondo: si la idea es correcta aunque la explique con
otras palabras, es correcta. Si le falta algo importante, es parcial. Si es incorrecta o parcial:
- "feedback" dice qué parte va bien, sin revelar la respuesta correcta.
- "pista" es una pregunta o una pista corta que lo acerque, sin darle la respuesta.
Si es correcta, "feedback" confirma en una frase y añade un detalle que profundice. ${Prompts.lang(o)}

Responde solo con JSON: {"correcto":true,"parcial":false,"feedback":"...","pista":"..."}
""".trim()

    fun evaluateTraceUser(block: CodeBlock, q: TraceQuestion, studentAnswer: String) = buildString {
        appendLine("Código:")
        appendLine("```kotlin"); appendLine(block.text); appendLine("```")
        appendLine("Pregunta: ${q.question}")
        appendLine("Respuesta correcta: ${q.answer}")
        appendLine("Respuesta del estudiante: $studentAnswer")
        append("Evalúa y responde solo con el JSON.")
    }

    /** Lead-and-Reveal: el bloque está plegado y el estudiante explica qué debería hacer antes de verlo. */
    fun evaluateExplainUser(block: CodeBlock, goal: String?, studentAnswer: String) = buildString {
        if (goal != null) appendLine("Contexto: $goal\n")
        appendLine("El estudiante no ve este bloque (está plegado en su editor). Le pedimos que explique qué hace y por qué está ahí.")
        appendLine("Archivo ${block.path}, líneas ${block.startLine}–${block.endLine}:")
        appendLine("```kotlin"); appendLine(block.text); appendLine("```")
        appendLine("Explicación del estudiante: $studentAnswer")
        append("Evalúa si su explicación captura qué hace el bloque y para qué, y responde solo con el JSON.")
    }

    /** Repaso espaciado: una pregunta de 30 segundos sobre un concepto, sin depender del código del proyecto. */
    fun reviewSystem(o: PromptOptions) = """
${base(o)}

Haz UNA pregunta corta de repaso sobre el concepto indicado, que se responda en una frase o un valor en unos 30 segundos.
Prefiere predecir el resultado de una línea de código con datos sencillos, por ejemplo: "¿qué devuelve `listOf(3, 1, 2).sorted().first()`?".
Código entre backticks. ${Prompts.lang(o)}

Responde solo con JSON: {"pregunta":"...","respuesta":"...","porque":"..."}
""".trim()

    fun reviewUser(concept: String) = "Concepto: $concept\nResponde solo con el JSON."

    fun evaluateReviewUser(q: TraceQuestion, studentAnswer: String) = buildString {
        appendLine("Pregunta: ${q.question}")
        appendLine("Respuesta correcta: ${q.answer}")
        appendLine("Respuesta del estudiante: $studentAnswer")
        append("Evalúa y responde solo con el JSON.")
    }
}

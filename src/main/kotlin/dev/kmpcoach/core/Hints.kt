package dev.kmpcoach.core

/** Error real que marca el IDE (compilador o sintaxis). [line] empieza en 1. */
data class IdeProblem(val line: Int, val message: String)

object IdeProblems {
    private val UNRESOLVED = Regex("(?i)unresolved reference")
    private val TODO_CALL = Regex("\\bTODO\\s*\\(")

    /**
     * Errores que cuentan para el detector de "atascado". En los borradores de los retos, las referencias sin resolver
     * suelen ser bibliotecas que el borrador no tiene (ViewModel, Flow…), así que no cuentan.
     */
    fun relevant(problems: List<IdeProblem>, isScratch: Boolean): List<IdeProblem> =
        if (isScratch) problems.filterNot { UNRESOLVED.containsMatchIn(it.message) } else problems

    /** El código tiene un `TODO()` pendiente fuera de comentarios y textos: está incompleto aunque compile. */
    fun hasTodo(code: String): Boolean = TODO_CALL.containsMatchIn(Analyzer.scan(code).clean)
}

/** Escalera de pistas: cada nivel ayuda un poco más que el anterior, sin llegar nunca a la solución. */
enum class HintLevel(val label: String, val instruction: String) {
    QUESTION(
        "Pregunta",
        "Haz UNA pregunta guía que lleve al estudiante a descubrir el problema por sí mismo. No nombres funciones ni APIs y no des la respuesta.",
    ),
    CONCEPT(
        "Concepto",
        "Explica en una o dos frases la idea que necesita (qué tiene que lograr), sin nombrar la función o API exacta y sin código.",
    ),
    TOOL(
        "Herramienta",
        "Nombra la función o API de Kotlin que le sirve y muestra UN ejemplo de una línea con OTROS datos (números, frutas, nombres de ciudades), " +
            "nunca con sus variables, sus clases ni su tema. Formato del ejemplo: `listOf(1, 5, 8).filter { it > 4 }` da `[5, 8]`.",
    ),
    STEPS(
        "Pasos",
        "Describe en lenguaje natural de 2 a 4 pasos numerados para resolverlo. Sin código: como mucho, nombres de funciones entre backticks.",
    );

    val number: Int get() = ordinal + 1
    val next: HintLevel? get() = entries.getOrNull(ordinal + 1)
}

data class Hint(val level: HintLevel, val line: Int, val problem: String, val text: String, val concept: String?, val redacted: Boolean = false)

object HintParser {
    /** Lee la pista del modelo. Los modelos pequeños a veces responden sin JSON: en ese caso, el texto completo es la pista. */
    fun parse(text: String, lineCount: Int, level: HintLevel, fallbackLine: Int): Hint {
        val root = runCatching { Json.extract(text).asMap() }.getOrDefault(emptyMap())
        val n = lineCount.coerceAtLeast(1)
        val hint = root.str("pista")?.trim().orEmpty().ifEmpty { text.trim() }
        val line = (root.int("linea") ?: fallbackLine).coerceIn(1, n)
        return Hint(level, line, root.str("problema")?.trim().orEmpty(), hint, root.str("concepto")?.trim()?.ifEmpty { null })
    }

    /** Preguntas de comprensión de «Explícame esto». */
    fun questions(text: String): List<String> {
        val list = runCatching { Json.extract(text).asMap()["preguntas"].asList() }.getOrDefault(emptyList())
            .mapNotNull { (it as? String)?.trim()?.ifEmpty { null } }
        return list.ifEmpty { listOf(text.trim()) }.take(3)
    }
}

/**
 * Filtro anti-código: comprueba que una pista no le regale al estudiante el código de su ejercicio.
 * - En los niveles Pregunta, Concepto y Pasos no se permite código: solo nombres sueltos entre backticks (`users`, `filter`).
 * - En el nivel Herramienta se permite un ejemplo corto, siempre que use otros datos: si menciona dos o más
 *   identificadores propios del estudiante (sus variables, clases o campos), está resolviendo su ejercicio.
 */
object CodeGuard {
    private val FENCE = Regex("```[^\\n]*\\n?([\\s\\S]*?)(```|$)")
    private val INLINE = Regex("`([^`\\n]+)`")
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val CODE_LIKE = Regex("[{}=;]|->|&&|\\|\\||[<>]=|\\w\\s*\\(\\s*[^)\\s]")
    const val HIDDEN = "«código oculto: este paso lo piensas tú»"

    /** Palabras clave y biblioteca estándar común: pueden aparecer en un ejemplo sin revelar nada del ejercicio. */
    private val COMMON = setOf(
        "val", "var", "fun", "if", "else", "when", "for", "while", "do", "in", "is", "as", "return", "true", "false", "null",
        "it", "this", "class", "data", "object", "private", "override", "suspend", "import", "package",
        "String", "Int", "Long", "Double", "Float", "Boolean", "Char", "Unit", "Any", "List", "MutableList", "Set", "Map", "Pair",
        "listOf", "mutableListOf", "setOf", "mapOf", "emptyList", "arrayOf", "to", "println", "print", "TODO", "require", "check",
        "filter", "filterNot", "map", "mapNotNull", "flatMap", "forEach", "sorted", "sortedBy", "sortedDescending", "sortedByDescending",
        "first", "firstOrNull", "last", "lastOrNull", "find", "any", "all", "none", "count", "sum", "sumOf", "maxOf", "minOf",
        "groupBy", "associate", "associateBy", "distinct", "take", "drop", "reversed", "joinToString", "zip", "fold", "reduce",
        "let", "also", "apply", "run", "with", "takeIf", "size", "length", "isEmpty", "isNotEmpty", "isBlank", "trim",
        "uppercase", "lowercase", "contains", "startsWith", "toString", "toInt", "and", "or", "not",
        "assertEquals", "assertTrue", "assertFalse", "assertNull", "Test", "remember", "mutableStateOf", "launch", "flow", "emit",
    )

    /** Identificadores propios del estudiante: los de su código que no son vocabulario común de Kotlin. */
    fun studentIdentifiers(code: String): Set<String> =
        IDENT.findAll(Analyzer.scan(code).clean).map { it.value }.filter { it.length > 1 && it !in COMMON }.toSet()

    fun leaks(hint: String, studentCode: String, level: HintLevel): Boolean =
        leakingRanges(hint, studentCode, level).isNotEmpty()

    /** Reemplaza los fragmentos que regalan código por un aviso. */
    fun redact(hint: String, studentCode: String, level: HintLevel): String {
        val ranges = leakingRanges(hint, studentCode, level)
        if (ranges.isEmpty()) return hint
        val sb = StringBuilder(hint)
        ranges.sortedByDescending { it.first }.forEach { sb.replace(it.first, it.last + 1, HIDDEN) }
        return sb.toString().trim()
    }

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }

    private fun leakingRanges(hint: String, studentCode: String, level: HintLevel): List<IntRange> {
        val ids = studentIdentifiers(studentCode)
        val own = squash(studentCode)
        val out = ArrayList<IntRange>()
        val fences = FENCE.findAll(hint).toList()
        fences.forEach { m -> if (fragmentLeaks(m.groupValues[1], ids, own, level, fenced = true)) out += m.range }
        val outside = { i: Int -> fences.none { i in it.range } }
        INLINE.findAll(hint).filter { outside(it.range.first) }.forEach { m ->
            if (fragmentLeaks(m.groupValues[1], ids, own, level, fenced = false)) out += m.range
        }
        return out
    }

    private fun fragmentLeaks(code: String, ids: Set<String>, ownCode: String, level: HintLevel, fenced: Boolean): Boolean {
        val lines = code.lines().count { it.isNotBlank() }
        if (fenced && (level != HintLevel.TOOL || lines > 2)) return true
        if (!CODE_LIKE.containsMatchIn(code)) return false
        // Citar una parte de tu propio código («¿qué compara `edad <= 18`?») no te regala nada.
        if (squash(code).let { it.length >= 3 && it in ownCode }) return false
        if (level != HintLevel.TOOL) return true
        val own = IDENT.findAll(code).map { it.value }.filter { it in ids }.toSet()
        return own.size >= 2
    }
}

/**
 * Decide cuándo debe hablar el tutor en vivo: solo si hay errores o código incompleto y llevas un rato sin avanzar.
 * Avanzar es bajar el número de problemas por debajo del mejor valor que tuviste. Habla una vez por atasco;
 * si quieres más ayuda, pides otra pista.
 */
class StuckTracker(val stuckAfterMs: Long) {
    private var best = Int.MAX_VALUE
    private var progressAt = 0L
    private var spoke = false

    /**
     * Registra cuántos problemas hay ahora. Devuelve null si no hay nada que hacer (sin problemas o ya habló en este atasco),
     * 0 si hay que hablar ya, o los ms que faltan para considerarte atascado.
     */
    fun update(problems: Int, now: Long): Long? {
        if (problems <= 0) {
            best = Int.MAX_VALUE; progressAt = 0L; spoke = false
            return null
        }
        if (problems < best) {
            best = problems; progressAt = now; spoke = false
        }
        if (spoke) return null
        return (progressAt + stuckAfterMs - now).coerceAtLeast(0)
    }

    fun markSpoken() {
        spoke = true
    }
}

object HintPrompts {
    private fun common(o: PromptOptions) =
        "Eres un tutor de programación que acompaña a un estudiante de nivel ${o.level.label} de Android y Kotlin Multiplatform dentro de su IDE. " +
            "Tu objetivo es que ENTIENDA su código y lo resuelva él mismo, como en una prueba técnica: no le das la solución."

    fun hintSystem(o: PromptOptions, level: HintLevel) = """
${common(o)}

Esta vez das una pista de nivel ${level.number} de ${HintLevel.entries.size} («${level.label}»):
${level.instruction}

Reglas:
- Una sola pista, de 1 a 3 frases cortas, sobre el problema más importante. Si el IDE marca errores, empieza por ellos.
- Prohibido escribir código que use las variables, clases o funciones del estudiante, o que resuelva su ejercicio. No uses bloques ```.
- Si ya le diste pistas, sigue con el mismo problema y avanza desde ellas sin repetirlas, salvo que ya lo haya resuelto.
- Si el código no tiene problemas, haz una pregunta de comprensión (qué pasa con una lista vacía, por qué ese tipo, qué devuelve).
- En los borradores de retos, las referencias sin resolver a clases de bibliotecas (ViewModel, Flow…) se deben a dependencias que faltan: ignóralas.
- Código y nombres entre backticks. ${Prompts.lang(o)}

Responde solo con JSON con esta forma:
{"linea":4,"problema":"frase corta que nombra el problema sin resolverlo","pista":"...","concepto":"nombre corto del concepto, por ejemplo filter"}
""".trim()

    fun hintUser(code: String, fileName: String, problems: List<IdeProblem>, previous: List<Hint>, focusLine: Int?, level: HintLevel) = buildString {
        appendLine("ARCHIVO: $fileName")
        appendLine()
        problemsBlock(this, problems)
        if (previous.isNotEmpty()) {
            appendLine("Pistas que ya le diste, de la más antigua a la más reciente:")
            previous.forEach { appendLine("- línea ${it.line}, ${it.level.label}: ${it.text}") }
            appendLine()
        }
        if (focusLine != null) appendLine("El cursor del estudiante está en la línea $focusLine.\n")
        appendLine("Código actual (el número antes de \"|\" es la línea):")
        appendLine("```kotlin"); appendLine(Prompts.numbered(code)); appendLine("```")
        appendLine()
        append("Da la pista de nivel ${level.number} («${level.label}») y responde solo con el JSON.")
    }

    const val LEAK_RETRY =
        "Tu pista incluía código que resuelve el ejercicio del estudiante. Reescríbela sin ese código y respetando el nivel. Responde solo con el JSON."

    fun giveUpSystem(o: PromptOptions) = """
${common(o)}

El estudiante se rindió con un problema después de intentarlo. Ahora SÍ le muestras la solución, pero enseñando:
1. Una frase con la idea clave.
2. Solo el código que cambia, en un bloque ```kotlin.
3. Qué hace cada línea de ese código, en frases cortas.
4. Una pregunta final para comprobar que lo entendió, o un ejercicio parecido para practicar.
Máximo 250 palabras. Código entre backticks. ${Prompts.lang(o)}
""".trim()

    fun giveUpUser(code: String, fileName: String, problems: List<IdeProblem>, hints: List<Hint>, focusLine: Int?) = buildString {
        appendLine("ARCHIVO: $fileName")
        appendLine()
        problemsBlock(this, problems)
        if (hints.isNotEmpty()) {
            appendLine("Pistas que ya recibió sin lograrlo:")
            hints.forEach { appendLine("- línea ${it.line}, ${it.level.label}: ${it.text}") }
            appendLine()
        }
        val line = hints.lastOrNull()?.line ?: focusLine
        if (line != null) appendLine("El problema está alrededor de la línea $line.\n")
        appendLine("Código actual:")
        appendLine("```kotlin"); appendLine(Prompts.numbered(code)); appendLine("```")
        append("Muéstrale la solución explicada.")
    }

    fun explainSystem(o: PromptOptions) = """
${common(o)}

El estudiante seleccionó un fragmento de código (puede haberlo escrito una IA) y quiere comprobar que lo entiende.
Haz 2 o 3 preguntas cortas, de la más simple a la más profunda, que solo pueda responder si entiende qué hace ese código y por qué:
qué devuelve, qué pasa en un caso borde, por qué se eligió esa función o ese tipo. No expliques el código ni des las respuestas.
Código entre backticks. ${Prompts.lang(o)}

Responde solo con JSON: {"preguntas":["...","..."]}
""".trim()

    fun explainUser(selection: String, fileName: String, firstLine: Int) = buildString {
        appendLine("ARCHIVO: $fileName, desde la línea $firstLine")
        appendLine("```kotlin"); appendLine(selection.trimEnd()); appendLine("```")
        append("Haz las preguntas de comprensión y responde solo con el JSON.")
    }

    /**
     * Mensaje listo para pegar en un chat con Claude cuando el estudiante se rinde: archivo, errores, pistas recibidas y el código
     * alrededor del problema.
     */
    fun handoff(path: String, code: String, problems: List<IdeProblem>, hints: List<Hint>, focusLine: Int?): String = buildString {
        appendLine("Me rendí con este problema en el tutor del IDE y quiero entenderlo. Explícame la solución paso a paso, como profesor.")
        appendLine()
        appendLine("Archivo: $path")
        if (problems.isNotEmpty()) {
            appendLine()
            appendLine("Errores que marca el IDE:")
            problems.take(8).forEach { appendLine("- Línea ${it.line}: ${it.message}") }
        }
        if (hints.isNotEmpty()) {
            appendLine()
            appendLine("Pistas que ya me dio el tutor:")
            hints.forEach { appendLine("- ${it.level.label} (línea ${it.line}): ${it.text}") }
        }
        val center = hints.lastOrNull()?.line ?: problems.firstOrNull()?.line ?: focusLine
        appendLine()
        appendLine(if (center == null) "Código:" else "Código alrededor de la línea $center:")
        appendLine("```kotlin"); appendLine(excerpt(code, center)); append("```")
    }

    /** El archivo completo si es corto; si no, 15 líneas antes y después de [center], numeradas como en el editor. */
    fun excerpt(code: String, center: Int?, radius: Int = 15, maxWhole: Int = 80): String {
        val lines = code.trimEnd().lines()
        val range = if (lines.size <= maxWhole || center == null) lines.indices
        else ((center - 1 - radius).coerceAtLeast(0))..((center - 1 + radius).coerceAtMost(lines.lastIndex))
        return range.joinToString("\n") { i -> (i + 1).toString().padStart(4) + "| " + lines[i] }
    }

    private fun problemsBlock(sb: StringBuilder, problems: List<IdeProblem>) {
        sb.appendLine("Errores que marca el IDE ahora mismo:")
        if (problems.isEmpty()) sb.appendLine("- (ninguno)")
        problems.take(8).forEach { sb.appendLine("- línea ${it.line}: ${it.message}") }
        sb.appendLine()
    }
}

package dev.kmpcoach.core

/** Una línea de un bloque ```diff: ' ' se queda igual, '+' entra, '-' sale. */
data class DiffLine(val kind: Char, val text: String)

/** Un cambio de un paso: el archivo, la línea que citó la IA y el diff que propuso. */
data class StepEdit(val path: String, val line: Int, val diff: List<DiffLine>) {
    val fileName: String get() = path.substringAfterLast('/')
    /** El código como está antes del cambio (lo que se queda y lo que sale). */
    val before: List<String> get() = diff.filter { it.kind != '+' }.map { it.text }
    /** El código después del cambio (lo que se queda y lo que entra). */
    val after: List<String> get() = diff.filter { it.kind != '-' }.map { it.text }
    val added: List<String> get() = diff.filter { it.kind == '+' }.map { it.text }
    val removed: List<String> get() = diff.filter { it.kind == '-' }.map { it.text }

    /** Los TODO numerados que deja el esqueleto, por ejemplo «TODO 3: si está activa, el color de siempre…». */
    val todos: List<Pair<String, String>>
        get() = added.mapNotNull { l -> TODO_N.find(l)?.let { m -> "TODO ${m.groupValues[1]}" to m.groupValues[2].trim() } }

    /** Es un esqueleto (deja huecos con TODO para que los completes) y no la solución escrita. */
    val isSkeleton: Boolean get() = added.any { TODO_WORD.containsMatchIn(it) }

    /** Solo borra código. */
    val isDeletion: Boolean get() = added.all { it.isBlank() } && removed.any { it.isNotBlank() }

    /** Identifica el cambio aunque el mensaje se vuelva a leer. */
    val key: String get() = "$path#$line#${diff.hashCode()}"

    companion object {
        internal val TODO_N = Regex("TODO\\s+(\\d+)\\s*:?\\s*(.*)")
        internal val TODO_WORD = Regex("\\bTODO\\b")
    }
}

/** Un paso del plan que dio la IA: «## Paso 5.1: el componente acepta un callback nulo». */
data class GuideStep(val number: String, val title: String, val edits: List<StepEdit>, val verify: String?) {
    val id: String get() = "$number $title"
    val label: String get() = "Paso $number: $title"
}

/**
 * Lee los pasos de un mensaje de la IA. Espera lo que piden las reglas del proyecto: un título «## Paso N» por paso y,
 * antes de cada bloque ```diff, un enlace al archivo con sus líneas (`[Archivo.kt:27-32](ruta#L27-L32)`).
 */
object StepParser {
    private val HEADING = Regex("^#{2,4}\\s*(?:Paso|Step)\\s+(\\d+(?:\\.\\d+)*)\\s*[:.\\-–—]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val LINK = Regex("\\[[^\\]]*]\\(((?:file://)?[^)#\\s]+\\.[A-Za-z]+)(?:#L(\\d+)(?:-L?(\\d+))?)?\\)")
    private val VERIFY = Regex("^\\W*\\**\\s*C[oó]mo verificarlo\\s*:?\\**\\s*:?\\s*(.*)$", RegexOption.IGNORE_CASE)

    fun hasSteps(markdown: String): Boolean = markdown.lineSequence().any { HEADING.matches(it.trim()) }

    fun parse(markdown: String, basePath: String?): List<GuideStep> {
        val steps = ArrayList<GuideStep>()
        var number: String? = null
        var title = ""
        var edits = ArrayList<StepEdit>()
        var verify: String? = null
        var link: Pair<String, Int>? = null
        var inDiff = false
        var inOtherBlock = false
        var diff = ArrayList<DiffLine>()
        var readingVerify = false

        fun close() {
            number?.let { steps += GuideStep(it, title, edits.toList(), verify) }
            edits = ArrayList(); verify = null; link = null
        }

        for (raw in markdown.lines()) {
            val trimmed = raw.trim()
            if (inDiff) {
                if (trimmed.startsWith("```")) {
                    inDiff = false
                    if (number != null && link != null && diff.isNotEmpty()) edits += StepEdit(link!!.first, link!!.second, diff.toList())
                    link = null
                } else {
                    diff += diffLine(raw)
                }
                continue
            }
            if (inOtherBlock) {
                if (trimmed.startsWith("```")) inOtherBlock = false
                continue
            }
            if (trimmed.startsWith("```")) {
                if (trimmed.removePrefix("```").trim().equals("diff", ignoreCase = true)) { inDiff = true; diff = ArrayList() } else inOtherBlock = true
                readingVerify = false
                continue
            }
            HEADING.matchEntire(trimmed)?.let { m ->
                close()
                number = m.groupValues[1]
                title = m.groupValues[2].replace("*", "").trim()
                readingVerify = false
                return@let
            } ?: run {
                if (trimmed.startsWith("#")) { close(); number = null; readingVerify = false; return@run }
                if (number == null) return@run
                LINK.findAll(trimmed).lastOrNull()?.let { m -> link = resolve(m.groupValues[1], basePath) to (m.groupValues[2].toIntOrNull() ?: 1) }
                val v = VERIFY.matchEntire(trimmed)
                when {
                    v != null -> { verify = clean(v.groupValues[1]); readingVerify = true }
                    readingVerify && trimmed.isNotEmpty() -> verify = (verify.orEmpty() + " " + clean(trimmed)).trim()
                    trimmed.isEmpty() -> readingVerify = false
                }
            }
        }
        close()
        return steps
    }

    private fun diffLine(raw: String): DiffLine = when {
        raw.isEmpty() -> DiffLine(' ', "")
        raw[0] == '+' || raw[0] == '-' || raw[0] == ' ' -> DiffLine(raw[0], raw.substring(1))
        else -> DiffLine(' ', raw)
    }

    private fun resolve(path: String, basePath: String?): String {
        val p = path.removePrefix("file://").replace("%20", " ")
        return if (p.startsWith("/") || basePath == null) p else "${basePath.trimEnd('/')}/$p"
    }

    private fun clean(s: String) = s.replace("**", "").replace("`", "").trim()
}

/** Dónde poner el esqueleto: reemplazar las líneas [from]..[to] del archivo (0-based, inclusive) por [lines]. */
data class Replacement(val from: Int, val to: Int, val lines: List<String>)

/** Estado de un TODO del esqueleto en tu código. */
enum class TodoState { PENDING, DONE, UNKNOWN }

data class TodoMark(val label: String, val text: String, val state: TodoState)

/**
 * Cómo va un cambio en tu código.
 * [cursorLine]: dónde debería ir el código ahora (0-based), aunque el archivo haya cambiado desde que la IA lo citó.
 */
data class EditStatus(
    val todos: List<TodoMark>,
    val skeletonPlaced: Boolean,
    val canPlaceSkeleton: Boolean,
    val complete: Boolean,
    val cursorLine: Int?,
    val found: Boolean,
)

/** Ubica los cambios de un paso en el código actual y mide cuánto llevas. Sin IDE: solo texto. */
object StepMatcher {
    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")

    /** Lugares donde las líneas no vacías de [pattern] aparecen seguidas entre las líneas no vacías del archivo. */
    fun find(file: List<String>, pattern: List<String>): List<IntArray> {
        val pat = pattern.map(::norm).filter { it.isNotEmpty() }
        if (pat.isEmpty()) return emptyList()
        val idx = file.indices.filter { file[it].isNotBlank() }
        val normed = idx.map { norm(file[it]) }
        val out = ArrayList<IntArray>()
        for (s in 0..(idx.size - pat.size)) {
            if (pat.indices.all { normed[s + it] == pat[it] }) out += IntArray(pat.size) { idx[s + it] }
        }
        return out
    }

    private fun nearest(matches: List<IntArray>, hint: Int): IntArray? = matches.minByOrNull { kotlin.math.abs(it.first() - hint) }

    private fun todoLine(file: List<String>, label: String): Int? {
        val n = label.removePrefix("TODO ").trim()
        val r = Regex("TODO\\s+$n\\b")
        return file.indexOfFirst { r.containsMatchIn(it) }.takeIf { it >= 0 }
    }

    /** Dónde poner el cursor: tras el primer TODO pendiente, o donde empieza el cambio según el código que lo rodea. */
    fun cursorLine(file: List<String>, edit: StepEdit): Int? {
        val hint = (edit.line - 1).coerceAtLeast(0)
        edit.todos.firstNotNullOfOrNull { (label, _) -> todoLine(file, label) }?.let { return (it + 1).coerceAtMost(file.size - 1) }
        val firstChange = edit.diff.indexOfFirst { it.kind != ' ' }.takeIf { it >= 0 } ?: return hint.coerceAtMost((file.size - 1).coerceAtLeast(0))
        // Lo que está justo antes del cambio y se queda igual.
        val prefix = edit.diff.subList(0, firstChange).filter { it.kind == ' ' }.map { it.text }.takeLast(3)
        nearest(find(file, prefix), hint)?.let { return (it.last() + 1).coerceAtMost(file.size - 1) }
        // Si el cambio empieza el bloque, lo que viene justo después.
        val suffix = edit.diff.drop(firstChange).dropWhile { it.kind != ' ' }.takeWhile { it.kind == ' ' }.map { it.text }.take(3)
        nearest(find(file, suffix), hint)?.let { return it.first() }
        // Si ya lo pusiste, donde está lo nuevo.
        nearest(find(file, edit.added.filter { it.isNotBlank() }.take(2)), hint)?.let { return it.first() }
        return if (file.isEmpty()) null else hint.coerceAtMost(file.size - 1)
    }

    /** Reemplazo que pone el esqueleto (solo los TODO, nunca una solución), o null si el código ya no coincide con lo que citó la IA. */
    fun placeSkeleton(file: List<String>, edit: StepEdit): Replacement? {
        if (!edit.isSkeleton) return null
        if (find(file, edit.after).isNotEmpty()) return null
        val m = nearest(find(file, edit.before), (edit.line - 1).coerceAtLeast(0)) ?: return null
        return Replacement(m.first(), m.last(), edit.after.dropLastWhile { it.isBlank() })
    }

    /**
     * Cuánto llevas del cambio. [seenPlaced]: ya viste el esqueleto puesto alguna vez (así un TODO que desaparece cuenta como hecho).
     * [hasErrors]: el IDE marca errores en ese archivo.
     */
    fun status(file: List<String>, edit: StepEdit, hasErrors: Boolean, seenPlaced: Boolean): EditStatus {
        val text = file.joinToString("\n")
        val afterFound = find(file, edit.after).isNotEmpty()
        val beforeFound = find(file, edit.before).isNotEmpty()
        val cursor = cursorLine(file, edit)
        val todoCall = edit.added.any { "TODO(" in it } && "TODO(" in text

        if (edit.isDeletion) {
            val gone = find(file, edit.removed).isEmpty()
            return EditStatus(emptyList(), false, false, gone && !hasErrors, cursor, !gone)
        }
        if (!edit.isSkeleton) {
            return EditStatus(emptyList(), false, false, afterFound && !hasErrors, cursor, afterFound || beforeFound)
        }
        val present = edit.todos.associate { (label, _) -> label to (todoLine(file, label) != null) }
        val placed = afterFound || present.values.any { it }
        val started = placed || seenPlaced || !beforeFound
        // Las líneas que el paso manda reemplazar y que siguen igual: esa parte todavía no la hiciste. Las muy cortas
        // («)», «}») se ignoran porque aparecen en cualquier archivo.
        val untouched = edit.removed.map(::norm).filter { it.length >= 8 }.any { r -> file.any { norm(it) == r } }
        // Empezaste, no quedan TODO ni líneas por reemplazar, y compila.
        val complete = started && present.values.none { it } && !untouched && !todoCall && !hasErrors
        val marks = edit.todos.map { (label, t) ->
            val state = when {
                present[label] == true -> TodoState.PENDING
                placed || seenPlaced || complete -> TodoState.DONE
                else -> TodoState.UNKNOWN
            }
            TodoMark(label, t, state)
        }
        val canPlace = !placed && !seenPlaced && placeSkeleton(file, edit) != null
        return EditStatus(marks, placed, canPlace, complete, cursor, placed || beforeFound || seenPlaced)
    }
}

/** Respuesta del tutor a «¿Cumple el paso?». */
data class StepVerdict(val ok: Boolean, val missing: String)

object StepPrompts {
    fun verifySystem(o: PromptOptions) = """
Eres un mentor de Android y Kotlin Multiplatform. El estudiante (nivel ${o.level.label}) sigue un paso que le dio otra IA y quiere saber si su código ya lo cumple. Compara su código con lo que pide el paso. No le des la solución ni código: si falta algo, dilo en una frase concreta («falta que el color cambie cuando está deshabilitada»). Los TODO del paso son huecos que él debía completar con su propio código, no texto que deba quedar. ${Prompts.lang(o)}
Responde solo con JSON: {"cumple": true|false, "falta": "una frase, vacía si cumple"}
""".trim()

    fun verifyUser(step: GuideStep, files: List<Pair<String, String>>): String = buildString {
        appendLine("PASO: ${step.label}")
        step.verify?.let { appendLine("Cómo se verifica según la IA: $it") }
        appendLine()
        step.edits.forEach { e ->
            appendLine("Cambio que propuso la IA en ${e.fileName} (línea ${e.line}):")
            appendLine("```diff"); e.diff.forEach { appendLine("${it.kind}${it.text}") }; appendLine("```")
        }
        appendLine()
        files.forEach { (name, code) ->
            appendLine("Código actual de $name:")
            appendLine("```kotlin"); appendLine(Prompts.numbered(code)); appendLine("```")
        }
        append("¿Cumple el paso? Responde solo con el JSON.")
    }

    fun parseVerdict(text: String): StepVerdict {
        val m = Json.extract(text).asMap()
        val ok = m["cumple"] == true || m.str("cumple") == "true"
        return StepVerdict(ok, m.str("falta")?.trim().orEmpty())
    }
}

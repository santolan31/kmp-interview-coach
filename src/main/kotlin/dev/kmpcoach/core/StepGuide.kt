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

    /** Código que se queda igual justo antes del cambio: ubica el cambio aunque haya líneas repetidas en el archivo. */
    val anchorBefore: List<String>
        get() {
            val first = diff.indexOfFirst { it.kind != ' ' }.takeIf { it >= 0 } ?: return emptyList()
            return diff.subList(0, first).map { it.text }.filter { it.isNotBlank() }.takeLast(2)
        }

    /** Código que se queda igual justo después del cambio. */
    val anchorAfter: List<String>
        get() {
            val last = diff.indexOfLast { it.kind != ' ' }.takeIf { it >= 0 } ?: return emptyList()
            return diff.subList(last + 1, diff.size).map { it.text }.filter { it.isNotBlank() }.take(2)
        }

    /**
     * Cada TODO con lo que permite saber si ya lo hiciste aunque no hayas puesto el esqueleto: las líneas que reemplaza
     * (las que salen justo antes) o lo que debe existir después (por ejemplo `val isNotificationEnabled`).
     */
    val todoSpecs: List<TodoSpec>
        get() {
            val out = ArrayList<TodoSpec>()
            var i = 0
            while (i < diff.size) {
                val m = if (diff[i].kind == '+') TODO_N.find(diff[i].text) else null
                if (m == null) { i++; continue }
                var j = i + 1
                while (j < diff.size && diff[j].kind == '+' && TODO_N.find(diff[j].text) == null) j++
                val removed = ArrayList<String>()
                var k = i - 1
                while (k >= 0 && diff[k].kind == '-') { removed.add(0, diff[k].text); k-- }
                val before = diff.subList(0, i).filter { it.kind == ' ' && it.text.isNotBlank() }.map { it.text }.takeLast(2)
                val after = diff.subList(j, diff.size).filter { it.kind == ' ' && it.text.isNotBlank() }.map { it.text }.take(2)
                // Lo que rodea pegado a este TODO (sin saltar otros cambios): dice cuál de las líneas repetidas es la suya.
                val anchorBefore = ArrayList<String>()
                while (k >= 0 && diff[k].kind == ' ' && anchorBefore.size < 2) { if (diff[k].text.isNotBlank()) anchorBefore.add(0, diff[k].text); k-- }
                val anchorAfter = ArrayList<String>()
                var a = j
                while (a < diff.size && diff[a].kind == ' ' && anchorAfter.size < 2) { if (diff[a].text.isNotBlank()) anchorAfter += diff[a].text; a++ }
                out += TodoSpec(
                    "TODO ${m.groupValues[1]}", m.groupValues[2].trim(), removed, keyOf(diff.subList(i + 1, j).map { it.text }),
                    before, after, anchorBefore, anchorAfter,
                )
                i = j
            }
            return out
        }

    companion object {
        internal val TODO_N = Regex("TODO\\s+(\\d+)\\s*:?\\s*(.*)")
        internal val TODO_WORD = Regex("\\bTODO\\b")

        /** De `val isNotificationEnabled = TODO()` saca `val isNotificationEnabled`; nada si es muy genérico (`tint`, `.clickable`). */
        private fun keyOf(group: List<String>): String? {
            val line = group.firstOrNull { TODO_WORD.containsMatchIn(it) } ?: return null
            val k = line.substringBefore("TODO").trim().trimEnd('=', ':', '(', ' ').trim()
            return k.takeIf { it.length >= 6 && !it.startsWith(".") }
        }
    }
}

/**
 * Un TODO del esqueleto: su texto, las líneas que reemplaza, lo que debe existir después y el código que lo rodea.
 * [anchorBefore]/[anchorAfter]: las líneas que se quedan igual pegadas a él, para encontrarlo aunque lo que reemplaza
 * aparezca también en otra parte del archivo (por ejemplo el mismo `tint = …` en dos iconos).
 */
data class TodoSpec(
    val label: String,
    val text: String,
    val removed: List<String>,
    val key: String?,
    val before: List<String>,
    val after: List<String>,
    val anchorBefore: List<String> = emptyList(),
    val anchorAfter: List<String> = emptyList(),
)

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

/**
 * Estado de un TODO en tu código. PENDING: el comentario TODO sigue ahí. MISSING: no hay comentario, pero la línea
 * que debía cambiar sigue igual o falta lo que debía crearse. DONE: hecho. UNKNOWN: no hay forma de saberlo sin la IA.
 */
enum class TodoState { PENDING, MISSING, DONE, UNKNOWN }

/** [line]: dónde está o debería ir (0-based), para llevarte ahí y marcarlo en rojo. */
data class TodoMark(val label: String, val text: String, val state: TodoState, val line: Int? = null) {
    val missing: Boolean get() = state == TodoState.PENDING || state == TodoState.MISSING
}

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

    /** Un ancla sirve si tiene alguna línea con contenido: «)» o «}» aparecen en cualquier parte. */
    private fun strong(lines: List<String>) = lines.any { norm(it).length >= 8 }

    /**
     * Líneas del archivo (0-based) donde está ahora un trozo de código, entre el código que lo precede ([before]) y el que
     * lo sigue ([after]). [size]: cuántas líneas suele ocupar, por si solo se encuentra un ancla. Null si no hay anclas.
     */
    fun locate(file: List<String>, before: List<String>, after: List<String>, size: Int, hint: Int): IntRange? {
        if (file.isEmpty()) return null
        val last = file.size - 1
        val a = if (strong(before)) nearest(find(file, before), hint)?.last() else null
        val afters = if (strong(after)) find(file, after).map { it.first() } else emptyList()
        val span = size + 3
        if (a != null) {
            val b = afters.filter { it > a }.minOrNull()
            return if (b != null && b - a <= span + 30) (a + 1)..(b - 1) else (a + 1)..minOf(last, a + span)
        }
        val b = afters.minByOrNull { kotlin.math.abs(it - hint) } ?: return null
        return maxOf(0, b - span)..(b - 1)
    }

    /** Dónde está ahora el código del cambio, según lo que lo rodea; null si no se puede ubicar. */
    fun region(file: List<String>, edit: StepEdit): IntRange? {
        val hint = (edit.line - 1).coerceAtLeast(0)
        nearest(find(file, edit.after), hint)?.let { return it.first()..it.last() }
        nearest(find(file, edit.before), hint)?.let { return it.first()..it.last() }
        return locate(file, edit.anchorBefore, edit.anchorAfter, maxOf(edit.removed.size, edit.added.size), hint)
    }

    /**
     * Líneas (0-based) que pertenecen a este cambio en el código actual: dónde está el cambio y dónde va cada TODO.
     * Ahí manda el paso: los comentarios de la revisión en esas líneas se ocultan tras «¿Cumple el paso?».
     */
    fun covered(file: List<String>, edit: StepEdit): Set<Int> {
        if (file.isEmpty()) return emptySet()
        val hint = (edit.line - 1).coerceAtLeast(0)
        val out = HashSet<Int>()
        region(file, edit)?.let { out.addAll(it) }
        edit.todoSpecs.forEach { s ->
            // Si el TODO reemplaza líneas, su zona es entre lo que lo rodea; si crea algo nuevo, la línea donde está o va.
            if (s.removed.any { norm(it).length >= 8 }) locate(file, s.anchorBefore, s.anchorAfter, s.removed.size, hint)?.let { out.addAll(it) }
            mark(file, s, hint).line?.let { out += it }
        }
        return out.filterTo(HashSet()) { it in file.indices }
    }

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
        val hint = (edit.line - 1).coerceAtLeast(0)
        val placed = afterFound || edit.todos.any { (label, _) -> todoLine(file, label) != null }
        val started = placed || seenPlaced || !beforeFound
        // Las líneas que el paso manda reemplazar y que siguen igual: esa parte todavía no la hiciste. Las muy cortas
        // («)», «}») se ignoran porque aparecen en cualquier archivo.
        // Las que pertenecen a un TODO ya las juzga su marca (ubicada por lo que la rodea); aquí solo las demás, y solo
        // dentro del cambio: la misma línea en otra parte del archivo (otro icono) no es la del paso.
        val ofTodos = edit.todoSpecs.flatMap { it.removed }.map(::norm).toSet()
        val area = region(file, edit)
        val untouched = edit.removed.map(::norm).filter { it.length >= 8 && it !in ofTodos }
            .any { r -> file.indices.any { (area == null || it in area) && norm(file[it]) == r } }
        val marks = edit.todoSpecs.map { s -> mark(file, s, hint) }.map { m ->
            if (m.state == TodoState.UNKNOWN && (placed || seenPlaced)) m.copy(state = TodoState.DONE) else m
        }
        // Empezaste, no falta ningún TODO ni quedan líneas por reemplazar, y compila.
        val complete = started && marks.none { it.missing } && !untouched && !todoCall && !hasErrors
        val canPlace = !placed && !seenPlaced && placeSkeleton(file, edit) != null
        // El cursor va a lo primero que falta; si no falta nada visible, a donde empieza el cambio.
        val firstGap = marks.firstOrNull { it.missing && it.line != null }?.line
        return EditStatus(marks, placed, canPlace, complete, firstGap ?: cursor, placed || beforeFound || seenPlaced)
    }

    /** Si un TODO está hecho y dónde va, mirando tu código. */
    private fun mark(file: List<String>, s: TodoSpec, hint: Int): TodoMark {
        todoLine(file, s.label)?.let { return TodoMark(s.label, s.text, TodoState.PENDING, (it + 1).coerceAtMost(file.size - 1)) }
        val removed = s.removed.map(::norm).filter { it.length >= 8 }
        if (removed.isNotEmpty()) {
            val left = file.indices.filter { norm(file[it]) in removed }
            if (left.isEmpty()) return TodoMark(s.label, s.text, TodoState.DONE)
            // Si las líneas que lo rodean lo ubican, solo cuentan las que siguen ahí, no una igual en otra parte.
            val area = locate(file, s.anchorBefore, s.anchorAfter, s.removed.size, hint)
            val here = if (area == null) left else left.filter { it in area }
            return if (here.isEmpty()) TodoMark(s.label, s.text, TodoState.DONE, area?.first)
            else TodoMark(s.label, s.text, TodoState.MISSING, here.minByOrNull { kotlin.math.abs(it - hint) })
        }
        if (s.key != null) {
            val key = norm(s.key)
            val at = file.indexOfFirst { norm(it).contains(key) }
            if (at >= 0) return TodoMark(s.label, s.text, TodoState.DONE, at)
            // Falta crearlo: va justo antes del código que le sigue en el diff, o justo después del que lo precede.
            val line = nearest(find(file, s.after), hint)?.first() ?: nearest(find(file, s.before), hint)?.let { it.last() + 1 }
            return TodoMark(s.label, s.text, TodoState.MISSING, line?.coerceAtMost(file.size - 1))
        }
        return TodoMark(s.label, s.text, TodoState.UNKNOWN)
    }
}

/** Algo que, según el tutor, falta para cumplir el paso: en qué archivo y línea (1-based) y qué. */
data class StepGap(val file: String?, val line: Int?, val text: String)

/** Respuesta del tutor a «¿Cumple el paso?». */
data class StepVerdict(val ok: Boolean, val gaps: List<StepGap>) {
    val missing: String get() = gaps.firstOrNull()?.text.orEmpty()
}

object StepPrompts {
    fun verifySystem(o: PromptOptions) = """
Eres un mentor de Android y Kotlin Multiplatform. El estudiante (nivel ${o.level.label}) sigue un paso que le dio otra IA y quiere saber si su código ya lo cumple. Compara su código con lo que pide el paso: el paso y las reglas del proyecto mandan; no pidas nada que ellos no pidan ni propongas otra forma de hacerlo. Los TODO del paso son huecos que él debía completar con su propio código, no texto que deba quedar.
Si falta algo, sé explícito: una frase con lo que debe quedar para que esté correcto y en qué parte («la campana activa debe usar el mismo color que tenía antes del cambio; ahora usa `primary`»). Puedes nombrar la API o el valor del tema que corresponde, pero no escribas el código completo. Una sola frase por cosa que falte: no repitas lo mismo con otras palabras. ${Prompts.lang(o)}
Responde solo con JSON: {"cumple": true|false, "faltan": [{"archivo": "Nombre.kt", "linea": 82, "que": "una frase"}]}
"linea" es el número de línea del código actual (el número antes de "|") donde está o debería ir lo que falta. Un elemento por cada cosa que falte, como máximo 4; lista vacía si cumple.
""".trim()

    fun verifyUser(step: GuideStep, files: List<Pair<String, String>>, rules: ProjectRules.Found? = null): String = buildString {
        ProjectRules.context(rules).takeIf { it.isNotEmpty() }?.let { appendLine(it.trim()); appendLine() }
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

    private val CUMPLE = Regex("\"cumple\"\\s*:\\s*\"?(true|false)")
    private val GAP = Regex("\\{[^{}]*?\"que\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^{}]*}")
    private val GAP_FILE = Regex("\"archivo\"\\s*:\\s*\"([^\"]*)\"")
    private val GAP_LINE = Regex("\"linea\"\\s*:\\s*(\\d+)")

    /**
     * Si el JSON llegó cortado (el modelo se quedó sin espacio), rescata lo que sí llegó completo: si cumple y cada
     * cosa que falta ya cerrada con su «}». Null si ni siquiera llegó «cumple».
     */
    internal fun salvage(text: String): StepVerdict? {
        val ok = CUMPLE.find(text)?.groupValues?.get(1) ?: return null
        val gaps = GAP.findAll(text).map { m ->
            val obj = m.value
            StepGap(
                GAP_FILE.find(obj)?.groupValues?.get(1)?.trim()?.ifEmpty { null },
                GAP_LINE.find(obj)?.groupValues?.get(1)?.toIntOrNull(),
                m.groupValues[1].replace("\\\"", "\"").replace("\\n", " ").trim(),
            )
        }.filter { it.text.isNotEmpty() }.take(4).toList()
        return StepVerdict(ok == "true", gaps)
    }

    fun parseVerdict(text: String): StepVerdict {
        val m = try {
            Json.extract(text).asMap()
        } catch (e: JsonException) {
            return salvage(text) ?: throw e
        }
        val ok = m["cumple"] == true || m.str("cumple") == "true"
        val gaps = m["faltan"].asList().map { it.asMap() }.mapNotNull { g ->
            val what = g.str("que")?.trim().orEmpty()
            if (what.isEmpty()) null else StepGap(g.str("archivo")?.trim(), g.str("linea")?.toDoubleOrNull()?.toInt(), what)
        }.take(4)
        // Compatibilidad con la respuesta antigua: {"cumple": false, "falta": "…"}.
        val legacy = m.str("falta")?.trim().orEmpty()
        return StepVerdict(ok, if (gaps.isEmpty() && legacy.isNotEmpty()) listOf(StepGap(null, null, legacy)) else gaps)
    }
}

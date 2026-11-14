package dev.kmpcoach.core

/** Una línea de un bloque ```diff: ' ' se queda igual, '+' entra, '-' sale. */
data class DiffLine(val kind: Char, val text: String)

/**
 * Un cambio de un paso: el archivo, la línea que citó la IA y el diff que propuso.
 * [loose]: vino de un bloque de código normal (no un ```diff): todas sus líneas son código que debe quedar, sin saber
 * qué reemplaza ni dónde va exactamente. Se comprueba de forma más flexible ([StepMatcher.status]).
 */
data class StepEdit(val path: String, val line: Int, val diff: List<DiffLine>, val loose: Boolean = false) {
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
 * Lee los pasos de un mensaje de la IA, de dos formas:
 * - Estricta: lo que piden las reglas del proyecto, un título «## Paso N» por paso y, antes de cada bloque ```diff, un
 *   enlace al archivo con sus líneas (`[Archivo.kt:27-32](ruta#L27-L32)`). Si el mensaje tiene esos títulos, solo se usa esta.
 * - Flexible ([parseLoose]): para quien no pide un formato y recibe «1. Crea… 2. En tu ViewModel…» con bloques de código.
 */
object StepParser {
    private val HEADING = Regex("^#{2,4}\\s*(?:Paso|Step)\\s+(\\d+(?:\\.\\d+)*)\\s*[:.\\-–—]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val LINK = Regex("\\[[^\\]]*\\]\\(((?:file://)?[^)#\\s]+\\.[A-Za-z]+)(?:#L(\\d+)(?:-L?(\\d+))?)?\\)")
    private val VERIFY = Regex("^\\W*\\**\\s*C[oó]mo verificarlo\\s*:?\\**\\s*:?\\s*(.*)$", RegexOption.IGNORE_CASE)

    // Formatos flexibles, uno por mensaje (el primero que aparezca al menos dos veces):
    /** «### 1. Crea la entidad» o «## 2) Conecta el ViewModel». */
    private val NUM_HEADING = Regex("^#{1,4}\\s*(\\d+)[.)]\\s+(.+)$")
    /** «Paso 1: …», «**Paso 1:** …» o «Step 2 - …» sin #. */
    private val PLAIN_STEP = Regex("^\\*{0,2}(?:Paso|Step)\\s+(\\d+(?:\\.\\d+)*)\\s*[:.)\\-–—]?\\s*\\*{0,2}\\s*[:.)\\-–—]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    /** «1. Crea…» o «**2. En tu ViewModel…**» al inicio de la línea (sin sangría: no una sublista). */
    private val LIST_STEP = Regex("^(?:\\*\\*)?(\\d+)[.)]\\s+(.+)$")
    private const val CODE_EXT = "kt|kts|java|xml|swift|ts|tsx|js|jsx|py|gradle|toml"
    /** Un archivo nombrado en el texto: «`HomeViewModel.kt`» o «feature/home/HomeViewModel.kt». */
    private val FILE_MENTION = Regex("[\\w./-]*\\w\\.(?:$CODE_EXT)\\b")
    /** Bloques que son código de la app (no comandos de terminal ni salidas). Vacío = sin lenguaje. */
    private val CODE_LANGS = setOf("", "kotlin", "kt", "kts", "java", "xml", "swift", "ts", "typescript", "tsx", "js", "javascript", "jsx", "py", "python", "gradle", "groovy", "toml")

    /** El mensaje usa el formato estricto («## Paso N»). */
    fun hasSteps(markdown: String): Boolean = markdown.lineSequence().any { HEADING.matches(it.trim()) }

    /**
     * El mensaje es una guía que el Mentor puede seguir: formato estricto, o pasos flexibles con código para algún archivo
     * de este proyecto. [findFile]: ver [parse].
     */
    fun isGuide(markdown: String, basePath: String?, findFile: ((String) -> String?)? = null): Boolean =
        hasSteps(markdown) || parseLoose(markdown, basePath, findFile).isNotEmpty()

    /**
     * Los pasos del mensaje. [findFile]: busca en el proyecto un archivo que la IA nombró sin ruta completa
     * («`HomeViewModel.kt`») y devuelve su ruta, o null si no está o hay varios con ese nombre. Sin él, esos nombres no
     * se pueden ubicar y el paso queda sin código.
     */
    fun parse(markdown: String, basePath: String?, findFile: ((String) -> String?)? = null): List<GuideStep> =
        if (hasSteps(markdown)) parseStrict(markdown, basePath) else parseLoose(markdown, basePath, findFile)

    /**
     * Pasos de una respuesta sin el formato del proyecto. Para no confundir cualquier explicación con una guía, solo vale
     * si hay al menos dos pasos y algún bloque de código para un archivo de este proyecto ([basePath]). El archivo de un
     * bloque sale, en este orden, de su encabezado («```kotlin title="X.kt"»), de su primera línea («// X.kt»), de un
     * enlace o del último archivo nombrado en el paso. Un bloque normal es código que debe quedar ([StepEdit.loose]); un
     * ```diff se lee como en el formato estricto. Los cambios fuera del proyecto se descartan.
     */
    fun parseLoose(markdown: String, basePath: String?, findFile: ((String) -> String?)? = null): List<GuideStep> {
        val lines = markdown.lines()
        val marker = looseMarker(lines) ?: return emptyList()
        val base = basePath?.let { nfc(it).trimEnd('/') + "/" }
        val steps = ArrayList<GuideStep>()
        var number: String? = null
        var title = ""
        var edits = ArrayList<StepEdit>()
        var verify: String? = null
        var readingVerify = false
        var link: Pair<String, Int>? = null
        var mention: String? = null
        var inBlock = false
        var fenceIndent = 0
        var lang = ""
        var infoFile: String? = null
        var block = ArrayList<String>()

        fun locate(name: String): String? = when {
            name.startsWith("/") || name.startsWith("file://") -> resolve(name, basePath)
            else -> findFile?.invoke(name) ?: if ('/' in name && basePath != null) resolve(name, basePath) else null
        }

        fun closeBlock() {
            if (number == null) return
            val firstComment = block.firstOrNull { it.isNotBlank() }?.trim()
                ?.takeIf { it.startsWith("//") || it.startsWith("#") || it.startsWith("<!--") }
                ?.let { FILE_MENTION.find(it)?.value }
            val named = infoFile ?: firstComment
            val path = named?.let(::locate) ?: link?.first ?: mention?.let(::locate) ?: return
            val line = link?.takeIf { named == null }?.second ?: 1
            if (lang == "diff") {
                val diff = block.map(::diffLine)
                if (diff.any { it.kind != ' ' }) edits += StepEdit(path, line, diff)
            } else if (lang in CODE_LANGS && StepMatcher.significant(block).isNotEmpty()) {
                edits += StepEdit(path, line, block.map { DiffLine('+', it) }, loose = true)
            }
        }

        fun close() {
            number?.let { n ->
                val inProject = edits.filter { base == null || nfc(it.path).startsWith(base) }
                steps += GuideStep(n, title, inProject, verify)
            }
            edits = ArrayList(); verify = null; readingVerify = false; link = null; mention = null
        }

        for (raw in lines) {
            val trimmed = raw.trim()
            if (inBlock) {
                if (trimmed.startsWith("```")) {
                    inBlock = false
                    closeBlock()
                    link = null
                } else block += dedent(raw, fenceIndent)
                continue
            }
            if (trimmed.startsWith("```")) {
                val info = trimmed.removePrefix("```").trim()
                inBlock = true; block = ArrayList(); fenceIndent = raw.length - raw.trimStart(' ').length
                lang = info.substringBefore(' ').substringBefore(':').lowercase()
                infoFile = FILE_MENTION.find(info)?.value
                readingVerify = false
                continue
            }
            val topLevel = raw.isNotEmpty() && !raw[0].isWhitespace()
            val m = if (topLevel) marker.matchEntire(trimmed) else null
            if (m != null) {
                close()
                number = m.groupValues[1]
                title = clip(clean(m.groupValues[2]).trimEnd('*', ':').trim(), 80)
                // Sigue abajo: la misma línea puede nombrar el archivo («1. Crea la clase en `CartItem.kt`»).
            } else if (trimmed.startsWith("#")) { close(); number = null; continue }
            if (number == null) continue
            LINK.findAll(trimmed).lastOrNull()?.let { l -> link = resolve(l.groupValues[1], basePath) to (l.groupValues[2].toIntOrNull() ?: 1) }
            FILE_MENTION.findAll(trimmed).lastOrNull()?.let { mention = it.value }
            val v = VERIFY.matchEntire(trimmed)
            when {
                v != null -> { verify = clean(v.groupValues[1]); readingVerify = true }
                readingVerify && trimmed.isNotEmpty() -> verify = (verify.orEmpty() + " " + clean(trimmed)).trim()
                trimmed.isEmpty() -> readingVerify = false
            }
        }
        close()
        // Una guía dice qué hacer: la mayoría de sus pasos empiezan con una acción. Así un informe («1. El logo cortado
        // (corregido)») o una revisión («1. 🔴 Bug en…») con código no se confunden con un plan.
        val instructions = steps.count { isInstruction(it.title) }
        return if (steps.size >= 2 && steps.any { it.edits.isNotEmpty() } && instructions * 10 >= steps.size * 6) steps else emptyList()
    }

    /**
     * Verbos con los que empieza una instrucción, en español (tú, usted e infinitivo) y en inglés, y «En tu ViewModel…» /
     * «In your…», que dice dónde hacer algo.
     */
    private val INSTRUCTION = Regex(
        "^(?:(?:crea|agrega|añade|cambia|reemplaza|borra|elimina|quita|mueve|implementa|define|escribe|abre|usa|llama|pasa|importa|" +
            "configura|instala|ejecuta|corre|modifica|actualiza|inyecta|registra|declara|conecta|extrae|renombra|prueba|verifica|" +
            "compila|sincroniza|guarda|muestra|corrige|reemplace|cree|agregue|añada|cambie|elimine|implemente|haz|ve)\\b|" +
            "(?:crear|agregar|añadir|cambiar|reemplazar|borrar|eliminar|quitar|mover|implementar|definir|escribir|abrir|usar|llamar|" +
            "pasar|importar|configurar|instalar|ejecutar|correr|modificar|actualizar|inyectar|registrar|declarar|conectar|extraer|" +
            "renombrar|probar|verificar|compilar|sincronizar|guardar|mostrar|corregir|hacer)\\b|" +
            "(?:add|create|update|replace|remove|delete|move|implement|define|write|open|use|call|pass|import|configure|install|run|" +
            "modify|inject|register|declare|connect|make|extract|rename|test|verify|build|sync|set up|setup|fix|change|wire)\\b|" +
            "(?:en|dentro de|in|inside)\\s+(?:tu|el|la|los|las|your|the)\\b)",
        RegexOption.IGNORE_CASE,
    )

    private fun isInstruction(title: String): Boolean =
        INSTRUCTION.containsMatchIn(title.trimStart { !it.isLetter() }.replace("`", ""))

    /** El formato de pasos del mensaje: el primero de los flexibles que aparece al menos dos veces fuera del código. */
    private fun looseMarker(lines: List<String>): Regex? {
        val counts = IntArray(3)
        val markers = listOf(NUM_HEADING, PLAIN_STEP, LIST_STEP)
        var inBlock = false
        for (raw in lines) {
            val t = raw.trim()
            if (t.startsWith("```")) { inBlock = !inBlock; continue }
            if (inBlock || raw.isEmpty() || raw[0].isWhitespace()) continue
            markers.forEachIndexed { i, r -> if (r.matches(t)) counts[i]++ }
        }
        return markers.indices.firstOrNull { counts[it] >= 2 }?.let { markers[it] }
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    private fun parseStrict(markdown: String, basePath: String?): List<GuideStep> {
        val steps = ArrayList<GuideStep>()
        var number: String? = null
        var title = ""
        var edits = ArrayList<StepEdit>()
        var verify: String? = null
        var link: Pair<String, Int>? = null
        var inDiff = false
        // Espacios antes de la apertura del bloque (```diff dentro de una lista): se quitan de cada línea, como hace
        // Markdown, para que «  -    código» se lea como una línea que sale y no como una que se queda.
        var fenceIndent = 0
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
                    diff += diffLine(dedent(raw, fenceIndent))
                }
                continue
            }
            if (inOtherBlock) {
                if (trimmed.startsWith("```")) inOtherBlock = false
                continue
            }
            if (trimmed.startsWith("```")) {
                if (trimmed.removePrefix("```").trim().equals("diff", ignoreCase = true)) {
                    inDiff = true; diff = ArrayList(); fenceIndent = raw.length - raw.trimStart(' ').length
                } else inOtherBlock = true
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

    /** Quita hasta [n] espacios del inicio: la sangría del bloque, no la del código. */
    private fun dedent(raw: String, n: Int): String {
        var k = 0
        while (k < n && k < raw.length && raw[k] == ' ') k++
        return raw.substring(k)
    }

    private fun diffLine(raw: String): DiffLine = when {
        raw.isEmpty() -> DiffLine(' ', "")
        raw[0] == '+' || raw[0] == '-' || raw[0] == ' ' -> DiffLine(raw[0], raw.substring(1))
        else -> DiffLine(' ', raw)
    }

    private fun resolve(path: String, basePath: String?): String {
        val p = percentDecode(path.removePrefix("file://"))
        // En NFC: la «ó» puede llegar como una letra o como «o» + tilde suelta (NFD); así se compara igual con la ruta del editor.
        return nfc(if (p.startsWith("/") || basePath == null) p else "${basePath.trimEnd('/')}/$p")
    }

    /**
     * `%C3%B3` → `ó`: los enlaces file:// llevan los espacios y las tildes codificados en UTF-8. Una secuencia mal
     * formada se deja tal cual (mejor una ruta rara que perder el enlace).
     */
    internal fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder()
        val bytes = ArrayList<Byte>()
        fun flush() { if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().decodeToString()); bytes.clear() } }
        var i = 0
        while (i < s.length) {
            val hex = if (s[i] == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) { bytes += hex.toByte(); i += 3 } else { flush(); out.append(s[i]); i++ }
        }
        flush()
        return out.toString()
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
        if (edit.loose) return looseStatus(file, edit, hasErrors)
        val text = file.joinToString("\n")
        val afterFound = find(file, edit.after).isNotEmpty()
        val beforeFound = find(file, edit.before).isNotEmpty()
        val cursor = cursorLine(file, edit)
        val todoCall = edit.added.any { "TODO(" in it } && "TODO(" in text

        if (edit.isDeletion) {
            // Un borrado no deja TODO: lo que falta es el código que sigue ahí. Se marca como «borrar» en su línea, para
            // que la barra lo diga y te lleve ahí aunque estés en otro archivo del paso.
            val left = leftover(file, edit)
            val marks = left?.let { listOf(TodoMark(DELETE_LABEL, norm(file[it]), TodoState.MISSING, it)) }.orEmpty()
            return EditStatus(marks, false, false, left == null && !hasErrors, left ?: cursor, left != null)
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

    /** Etiqueta de lo que falta en un paso que borra código: «Te falta borrar (en X.kt): …». */
    const val DELETE_LABEL = "borrar"

    /** Etiqueta de lo que falta de un bloque de código normal: «Te falta agregar (en X.kt): …». */
    const val ADD_LABEL = "agregar"

    /** Parte del código que se completa con «...» o «…» («// ... tu código», «fun algo(...)»). */
    private val ELISION = Regex("\\.\\.\\.|…")
    private val COMMENT = Regex("^(//|/\\*|\\*|<!--|#).*")

    /**
     * Líneas de un bloque de código que permiten saber si ya lo escribiste: sin comentarios, sin las que la IA abrevia con
     * «...» y sin las muy cortas («}», «)»), que aparecen en cualquier archivo.
     */
    fun significant(lines: List<String>): List<String> =
        lines.map(::norm).filter { it.length >= 8 && !ELISION.containsMatchIn(it) && !COMMENT.matches(it) }

    /**
     * Un bloque de código normal no dice qué reemplaza ni dónde va: está hecho si al menos el 80 % de sus líneas
     * importantes ya están en el archivo (en cualquier orden y sangría) y compila. Así un nombre distinto o una línea
     * escrita a tu manera no lo bloquean. Lo que falta se muestra como «agregar» con la primera línea que no está.
     */
    private fun looseStatus(file: List<String>, edit: StepEdit, hasErrors: Boolean): EditStatus {
        val want = significant(edit.added)
        val have = file.map(::norm).toHashSet()
        val present = want.filter { it in have }
        val complete = want.isNotEmpty() && present.size * 10 >= want.size * 8 && !hasErrors
        val firstMissing = want.firstOrNull { it !in have }
        // El cursor va justo después de lo último que ya escribiste del bloque; si no hay nada, al inicio del archivo.
        val last = present.lastOrNull()?.let { p -> file.indexOfFirst { norm(it) == p } }?.takeIf { it >= 0 }
        val cursor = last?.let { (it + 1).coerceAtMost(file.size - 1) } ?: if (file.isEmpty()) null else 0
        val marks = if (complete || firstMissing == null) emptyList() else listOf(TodoMark(ADD_LABEL, firstMissing, TodoState.MISSING))
        return EditStatus(marks, false, false, complete, cursor, present.isNotEmpty())
    }

    /**
     * Primera línea (0-based) del código que el paso manda borrar y que sigue en el archivo; null si ya no está.
     * Si solo borraste una parte (la primera línea de un bloque, por ejemplo), cuenta lo que queda cerca de donde estaba;
     * las líneas muy cortas («}») no cuentan porque aparecen en cualquier parte.
     */
    fun leftover(file: List<String>, edit: StepEdit): Int? {
        val hint = (edit.line - 1).coerceAtLeast(0)
        nearest(find(file, edit.removed), hint)?.let { block ->
            return block.firstOrNull { norm(file[it]).length >= 8 } ?: block.first()
        }
        val strong = edit.removed.map(::norm).filter { it.length >= 8 }.toSet()
        if (strong.isEmpty()) return null
        val span = edit.removed.size + 3
        val area = locate(file, edit.anchorBefore, edit.anchorAfter, edit.removed.size, hint)
            ?: (hint - span - 12).coerceAtLeast(0)..(hint + span + 12)
        return file.indices.filter { it in area && norm(file[it]) in strong }.minByOrNull { kotlin.math.abs(it - hint) }
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
    /**
     * Lo que se le pide al Mentor en «Explícame este paso»: qué lograr y por qué, sin código. Si ya empezaste, también cómo
     * vas (qué cambio ya está y qué falta, con archivo y línea), para que no te explique lo que ya hiciste y se centre
     * en lo que falta. [statuses]: el estado de cada cambio, en el mismo orden que [GuideStep.edits].
     */
    fun explainRequest(step: GuideStep, statuses: List<EditStatus>): String = buildString {
        val todos = step.edits.flatMap { it.todos }.joinToString("; ") { "${it.first}: ${it.second}" }
        append("Explícame el ${step.label}: qué tengo que lograr")
        if (todos.isNotEmpty()) append(" en cada TODO ($todos)")
        append(" y por qué, punto por punto, sin darme el código.")
        val pairs = step.edits.zip(statuses)
        val done = pairs.filter { it.second.complete }.map { it.first.fileName }.distinct()
        val left = pairs.filter { !it.second.complete }
        val started = done.isNotEmpty() || left.any { (_, s) -> s.todos.any { it.state == TodoState.DONE } }
        if (!started) return@buildString
        if (left.isEmpty()) {
            append(" Cómo voy: según mi código ya hice todos los cambios del paso; dime cómo comprobar que está bien.")
            return@buildString
        }
        if (done.isNotEmpty()) append(" Cómo voy: ya hice lo de ${done.joinToString(", ")}.") else append(" Cómo voy:")
        append(" Me falta: ")
        append(left.joinToString("; ") { (e, s) -> pending(e, s) })
        append(". Céntrate en lo que me falta.")
    }

    /**
     * Mensaje para avisar a la IA del plan que terminaste todos los pasos. Empieza con «Revisa», la palabra con la que
     * pides una revisión crítica, y dice cómo se comprobó cada paso para que la IA sepa qué revisar con más cuidado.
     * [confirmed]: ids de los pasos que el Mentor confirmó con «¿Cumple el paso?»; [manual]: los que marcaste como hechos.
     */
    fun doneMessage(steps: List<GuideStep>, confirmed: Set<String>, manual: Set<String>): String = buildString {
        append(if (steps.size == 1) "Revisa: terminé el paso de tu plan:" else "Revisa: terminé los ${steps.size} pasos de tu plan:")
        append('\n')
        steps.forEach { s ->
            val how = when {
                s.id in confirmed -> "el Mentor lo confirmó"
                s.id in manual || s.edits.isEmpty() -> "lo marqué como hecho"
                else -> "el Mentor no ve nada pendiente, pero no lo confirmó"
            }
            append("- ").append(s.label).append(" (").append(how).append(")\n")
        }
        val files = steps.flatMap { it.edits }.map { it.fileName }.distinct()
        if (files.isNotEmpty()) append("Archivos que cambié: ").append(files.joinToString(", ")).append(".\n")
        // Explícito, para que cualquier IA haga una revisión de verdad (no todos tienen una regla que defina «Revisa»).
        append("Antes de seguir, valida mi trabajo: compila, revisa el código de esos archivos contra cada paso y dime ")
        append("si quedó bien, qué está mal o qué mejorarías, de lo más grave a lo menos grave, con archivo y línea. ")
        append("No lo corrijas por mí: dame pistas.")
    }

    /** «borrar en HomeViewModel.kt, línea 207 («HomeAction.NotificationClicked -> {»)», o el cambio y dónde va. */
    private fun pending(e: StepEdit, s: EditStatus): String {
        val marks = s.todos.filter { it.missing }
        if (marks.isEmpty()) return "el cambio en ${e.fileName}, línea ${(s.cursorLine ?: (e.line - 1)).coerceAtLeast(0) + 1}"
        return marks.joinToString("; ") { m ->
            val what = if (m.label == StepMatcher.DELETE_LABEL) "borrar" else if (m.state == TodoState.PENDING) "${m.label} (el comentario sigue escrito)" else m.label
            "$what en ${e.fileName}" + (m.line?.let { ", línea ${it + 1}" } ?: "") + " («${m.text}»)"
        }
    }

    fun verifySystem(o: PromptOptions) = """
Eres un mentor de ${o.code.domain}. El estudiante (nivel ${o.level.label}) sigue un paso que le dio otra IA y quiere saber si su código ya lo cumple. Compara su código con lo que pide el paso: el paso y las reglas del proyecto mandan; no pidas nada que ellos no pidan ni propongas otra forma de hacerlo. Los TODO del paso son huecos que él debía completar con su propio código, no texto que deba quedar.
Si falta algo, sé explícito: una frase con lo que debe quedar para que esté correcto y en qué parte («la campana activa debe usar el mismo color que tenía antes del cambio; ahora usa `primary`»). Puedes nombrar la API o el valor del tema que corresponde, pero no escribas el código completo. Una sola frase por cosa que falte: no repitas lo mismo con otras palabras. ${Prompts.lang(o)}
Responde solo con JSON: {"cumple": true|false, "faltan": [{"archivo": "Nombre.kt", "linea": 82, "que": "una frase"}]}
"linea" es el número de línea del código actual (el número antes de "|") donde está o debería ir lo que falta. Un elemento por cada cosa que falte, como máximo 4; lista vacía si cumple.
""".trim()

    /**
     * [files]: nombre y código actual de cada archivo del paso. [session]: el chat donde la otra IA dio el plan (la que
     * conoce todo el proyecto), para juzgar con su contexto. [basePath]: raíz del proyecto, para mostrar rutas relativas.
     */
    fun verifyUser(
        step: GuideStep, files: List<Pair<String, String>>, rules: ProjectRules.Found? = null, session: String? = null, basePath: String? = null,
    ): String = buildString {
        ProjectRules.context(rules).takeIf { it.isNotEmpty() }?.let { appendLine(it.trim()); appendLine() }
        Prompts.sessionContext(session).takeIf { it.isNotEmpty() }?.let { appendLine(it.trim()); appendLine() }
        appendLine("PASO: ${step.label}")
        step.verify?.let { appendLine("Cómo se verifica según la IA: $it") }
        appendLine()
        step.edits.forEach { e ->
            val where = basePath?.let { e.path.removePrefix(it.trimEnd('/') + "/") } ?: e.fileName
            appendLine("Cambio que propuso la IA en $where (línea ${e.line}):")
            appendLine("```diff"); e.diff.forEach { appendLine("${it.kind}${it.text}") }; appendLine("```")
        }
        appendLine()
        files.forEach { (name, code) ->
            appendLine("Código actual de $name:")
            appendLine("```${CodeLanguage.of(name).fence}"); appendLine(Prompts.numbered(code)); appendLine("```")
        }
        append("¿Cumple el paso? Responde solo con el JSON.")
    }

    private val CUMPLE = Regex("\"cumple\"\\s*:\\s*\"?(true|false)")
    private val GAP = Regex("\\{[^{}]*?\"que\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^{}]*\\}")
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

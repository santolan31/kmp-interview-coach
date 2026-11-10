package dev.kmpcoach.core

import java.security.MessageDigest

// Contexto del proyecto que no depende de la IA que uses: el plan en Markdown y el código nuevo según git.

/** Un paso del plan. [line] empieza en 1 y es la línea del archivo Markdown. */
data class PlanStep(val index: Int, val line: Int, val text: String, val done: Boolean, val section: String?)

data class Plan(val title: String, val steps: List<PlanStep>) {
    val doneCount: Int get() = steps.count { it.done }
    /** El primer paso sin terminar. */
    val firstPending: PlanStep? get() = steps.firstOrNull { !it.done }
}

/**
 * Lee un plan escrito en Markdown por cualquier IA o a mano:
 * - Si tiene casillas (`- [ ]`, `- [x]`, `- [/]`), cada casilla es un paso.
 * - Si no, cada elemento numerado de primer nivel (`1.`) es un paso.
 * - Si tampoco, cada título de nivel 2 o 3 es un paso.
 * Ignora los bloques de código.
 */
object PlanParser {
    private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*\\s*$")
    private val CHECK = Regex("^\\s*[-*+]\\s+`?\\[([ xX/])]`?\\s+(.+)$")
    private val NUMBERED = Regex("^(\\d+)[.)]\\s+(.+)$")

    fun parse(markdown: String, fallbackTitle: String): Plan {
        val lines = markdown.lines()
        var title: String? = null
        var section: String? = null
        var inFence = false
        val checks = ArrayList<PlanStep>()
        val numbered = ArrayList<PlanStep>()
        val headings = ArrayList<PlanStep>()
        lines.forEachIndexed { i, raw ->
            if (raw.trimStart().startsWith("```")) { inFence = !inFence; return@forEachIndexed }
            if (inFence) return@forEachIndexed
            HEADING.find(raw)?.let { m ->
                val level = m.groupValues[1].length
                val text = clean(m.groupValues[2])
                if (level == 1 && title == null) title = text
                if (level in 2..3) headings += PlanStep(headings.size, i + 1, text, false, section)
                if (level <= 3) section = text
                return@forEachIndexed
            }
            CHECK.find(raw)?.let { m ->
                checks += PlanStep(checks.size, i + 1, clean(m.groupValues[2]), m.groupValues[1].equals("x", ignoreCase = true), section)
                return@forEachIndexed
            }
            NUMBERED.find(raw)?.let { m -> numbered += PlanStep(numbered.size, i + 1, clean(m.groupValues[2]), false, section) }
        }
        val steps = when {
            checks.isNotEmpty() -> checks
            numbered.isNotEmpty() -> numbered
            else -> headings
        }
        return Plan(title ?: fallbackTitle, steps)
    }

    /** Lo que se le envía al modelo sobre el paso actual: corto, para no gastar tokens. */
    fun goal(plan: Plan, step: PlanStep): String = buildString {
        append("Plan «${plan.title}», paso ${step.index + 1} de ${plan.steps.size}: ${step.text}")
        if (!step.section.isNullOrBlank() && step.section != plan.title) append(" (sección: ${step.section})")
    }.take(400)

    private fun clean(s: String) = s.replace("**", "").replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1").trim()
}

/** Líneas agregadas a un archivo según git. [added] son números de línea del archivo nuevo, empezando en 1. */
data class FileDiff(val path: String, val added: List<Int>)

/** Lee la salida de `git diff --unified=0`. Los archivos borrados no aparecen. */
object DiffParser {
    private val HUNK = Regex("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")

    fun parse(diff: String): List<FileDiff> {
        val out = ArrayList<FileDiff>()
        var path: String? = null
        var added = ArrayList<Int>()
        var next = 0
        fun flush() {
            path?.let { if (added.isNotEmpty()) out += FileDiff(it, added) }
            path = null
            added = ArrayList()
        }
        for (line in diff.lines()) {
            when {
                line.startsWith("diff --git ") -> flush()
                line.startsWith("+++ ") -> {
                    val p = line.removePrefix("+++ ").trim()
                    path = if (p == "/dev/null") null else p.removePrefix("b/")
                }
                line.startsWith("--- ") -> {}
                line.startsWith("@@") -> HUNK.find(line)?.let { next = it.groupValues[1].toInt() }
                line.startsWith("+") -> { if (path != null) added += next; next++ }
                line.startsWith(" ") -> next++
                else -> {}
            }
        }
        flush()
        return out
    }
}

/** Un bloque de código nuevo que el estudiante debería entender. Las líneas empiezan en 1. */
data class CodeBlock(val path: String, val startLine: Int, val endLine: Int, val text: String) {
    /** Identidad del bloque según su contenido: si el código se mueve de línea, sigue siendo el mismo bloque. */
    val id: String get() = Blocks.fingerprint(text)
    val lineCount: Int get() = endLine - startLine + 1
}

object Blocks {
    private val TRIVIAL = Regex("^\\s*(import |package |[{}()\\[\\],;]*$|//|/\\*|\\*)")

    /**
     * Agrupa las líneas agregadas en bloques. Une líneas separadas por [gap] o menos, descarta bloques con menos de
     * [minLines] líneas con contenido (imports, llaves y comentarios no cuentan) y parte los bloques largos en líneas en blanco.
     */
    fun fromAdded(path: String, added: List<Int>, fileLines: List<String>, minLines: Int = 3, gap: Int = 2, target: Int = 25, max: Int = 60): List<CodeBlock> {
        if (added.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        var start = added.first()
        var end = start
        for (n in added.sorted().drop(1)) {
            if (n - end <= gap + 1) end = n else { ranges += start..end; start = n; end = n }
        }
        ranges += start..end
        return ranges.flatMap { split(it, fileLines, target, max) }
            .map { r -> r to (r.first..r.last).mapNotNull { fileLines.getOrNull(it - 1) } }
            .filter { (_, lines) -> lines.count { it.isNotBlank() && !TRIVIAL.containsMatchIn(it) } >= minLines }
            .map { (r, lines) -> CodeBlock(path, r.first, r.last, lines.joinToString("\n")) }
    }

    /** Parte un rango largo en trozos de unas [target] líneas, cortando en líneas en blanco; nunca más de [max]. */
    private fun split(r: IntRange, fileLines: List<String>, target: Int, max: Int): List<IntRange> {
        if (r.last - r.first + 1 <= max) return listOf(r)
        val out = ArrayList<IntRange>()
        var s = r.first
        for (n in r) {
            val size = n - s + 1
            val blank = fileLines.getOrNull(n - 1)?.isBlank() == true
            if ((size >= target && blank) || size >= max) { out += s..n; s = n + 1 }
        }
        if (s <= r.last) out += s..r.last
        return out
    }

    fun fingerprint(text: String): String {
        val normalized = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Busca el bloque en el texto actual del editor, que puede tener cambios sin guardar y por eso líneas distintas a las de git.
     * Devuelve la línea de inicio (desde 1) o null si el bloque ya no está igual.
     */
    fun locate(block: CodeBlock, documentLines: List<String>): Int? {
        val want = block.text.lines().map { it.trim() }
        val first = want.indexOfFirst { it.isNotEmpty() }.takeIf { it >= 0 } ?: return null
        val hint = block.startLine - 1
        val candidates = documentLines.indices.filter { documentLines[it].trim() == want[first] }.sortedBy { kotlin.math.abs(it - first - hint) }
        for (c in candidates) {
            val start = c - first
            if (start < 0 || start + want.size > documentLines.size) continue
            if (want.indices.all { documentLines[start + it].trim() == want[it] }) return start + 1
        }
        return null
    }
}

/** Resultado de comparar los archivos que el estudiante creía que tocaría un paso con los que realmente cambiaron. */
data class FileGuessResult(val hits: List<String>, val missed: List<String>, val wrong: List<String>)

object FileGuess {
    private val WORD = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.kts?)?")

    /**
     * Compara la respuesta libre del estudiante ("el HomeViewModel y la pantalla de detalle") con las rutas cambiadas.
     * - Acierto: un archivo cuyo nombre coincide con una palabra de la respuesta, o la contiene (con 5 letras o más).
     * - Faltó: un archivo cambiado que no nombró.
     * - Sobró: un nombre de clase o archivo que nombró (`HomeScreen`, `Foo.kt`) y que no cambió.
     */
    fun compare(answer: String, changedPaths: List<String>): FileGuessResult {
        val words = WORD.findAll(answer).map { it.value }.toList()
        val tokens = words.map { it.removeSuffix(".kts").removeSuffix(".kt").lowercase() }.filter { it.length >= 4 }.toSet()
        val named = words.filter { it.endsWith(".kt") || it.endsWith(".kts") || (it[0].isUpperCase() && it.drop(1).any(Char::isUpperCase)) }
            .map { it.removeSuffix(".kts").removeSuffix(".kt") }.distinct()
        fun base(p: String) = p.substringAfterLast('/').substringBeforeLast('.')
        fun matches(token: String, fileBase: String) = token == fileBase || (token.length >= 5 && token in fileBase) || (fileBase.length >= 5 && fileBase in token)
        val hits = changedPaths.filter { p -> tokens.any { matches(it, base(p).lowercase()) } }
        val missed = changedPaths - hits.toSet()
        val wrong = named.filter { n -> changedPaths.none { p -> matches(n.lowercase(), base(p).lowercase()) } }
        return FileGuessResult(hits, missed, wrong)
    }
}

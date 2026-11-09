package dev.kmpcoach.core

enum class Severity(val label: String, val rank: Int) {
    ERROR("Error", 2), WARN("Aviso", 1), INFO("Sugerencia", 0);

    companion object {
        fun of(s: String?): Severity = when (s?.trim()?.lowercase()) {
            "error" -> ERROR
            "info", "sugerencia", "hint" -> INFO
            else -> WARN
        }
    }
}

data class Diagnostic(val from: Int, val to: Int, val severity: Severity, val message: String, val line: Int = 0)
data class Objective(val label: String, val pass: Boolean)

class Analysis(val diagnostics: List<Diagnostic>, val objectives: List<Objective>, val micros: Long) {
    fun count(s: Severity) = diagnostics.count { it.severity == s }
    val objectivesMet get() = objectives.count { it.pass }
}

/** Código con comentarios y textos en blanco ([clean]) y solo sin comentarios ([noComments]); mismas posiciones que el original. */
class Scan(val clean: String, val noComments: String, val lexErrors: List<Diagnostic>)

/** Contexto que ven las reglas generales. */
class RuleContext(scan: Scan) {
    val clean = scan.clean
    val noComments = scan.noComments
    val lines: List<String> = clean.split('\n')
    val coroutines = Regex("""\bsuspend\b|\blaunch\s*\{|viewModelScope|withContext|\bFlow\s*<|\basync\s*\{|coroutineScope|runTest""").containsMatchIn(clean)
    val composable = clean.contains("@Composable")
    val viewModel = Regex("""\bViewModel\s*\(""").containsMatchIn(clean)
    val rethrowsCancel = Regex("""CancellationException|ensureActive\s*\(""").containsMatchIn(clean)
    val lazyList = Regex("""\bLazy(Column|Row)\b""").containsMatchIn(clean)
}

class Rule(
    val pattern: Regex,
    val severity: Severity,
    val message: String,
    val focus: Regex? = null,
    val applies: (RuleContext, Int) -> Boolean = { _, _ -> true },
)

object Analyzer {

    /** Reglas que aplican a cualquier archivo Kotlin, sea reto o no. */
    val GENERAL_RULES: List<Rule> = listOf(
        Rule(Regex("!!"), Severity.WARN, "`!!` lanza NullPointerException si el valor es null. ¿Puedes usar `?.`, `?:` o un smart cast?"),
        Rule(Regex("""\bGlobalScope\b"""), Severity.ERROR, "`GlobalScope` no se cancela con la pantalla ni con el ViewModel. ¿Por qué no `viewModelScope` o un scope que controles?"),
        Rule(Regex("""\bThread\.sleep\s*\("""), Severity.ERROR, "`Thread.sleep` bloquea el hilo. Dentro de corrutinas usa `delay()`, que suspende sin bloquear."),
        Rule(Regex("""\brunBlocking\b"""), Severity.WARN, "`runBlocking` bloquea el hilo actual. En código de app casi nunca hace falta: ¿por qué lo necesitas aquí?"),
        Rule(Regex("""\bsynchronized\s*\("""), Severity.ERROR, "No puedes suspender dentro de `synchronized` y además bloquea el hilo. Con corrutinas usa `Mutex` y `withLock`.",
            applies = { c, _ -> c.coroutines }),
        Rule(Regex("""catch\s*\(\s*\w+\s*:\s*(Exception|Throwable)\s*\)"""), Severity.WARN,
            "Este `catch` también atrapa la `CancellationException` y rompe la cancelación. Antes agrega `catch (e: CancellationException) { throw e }`.",
            applies = { c, _ -> c.coroutines && !c.rethrowsCancel }),
        Rule(Regex("""\brunCatching\b"""), Severity.INFO, "`runCatching` también atrapa la cancelación de la corrutina. Si envuelve funciones `suspend`, relanza la `CancellationException`.",
            applies = { c, _ -> c.coroutines && !c.rethrowsCancel }),
        Rule(Regex("""\bmutable(Int|Long|Float|Double)?StateOf\s*\("""), Severity.ERROR, "Sin `remember`, este estado se crea de nuevo en cada recomposición y el valor se pierde.",
            applies = { c, ln ->
                c.composable && !c.viewModel &&
                    !c.lines.getOrElse(ln) { "" }.contains("remember") &&
                    !Regex("""remember\w*\s*(\([^)]*\))?\s*\{\s*$""").containsMatchIn(c.lines.getOrElse(ln - 1) { "" })
            }),
        Rule(Regex("""\.collectAsState\s*\("""), Severity.INFO, "En Android, `collectAsStateWithLifecycle()` deja de recolectar cuando la app pasa a segundo plano."),
        Rule(Regex("""(?m)^(?![^\n]*\bprivate\b)[^\n]*\bval\s+\w+\s*(:[^=\n]+)?=\s*MutableStateFlow"""), Severity.WARN,
            "Este `MutableStateFlow` queda público: cualquiera podría cambiar el estado desde fuera. Hazlo `private` y expón `asStateFlow()`.",
            focus = Regex("MutableStateFlow")),
        Rule(Regex("""\bvar\s+\w+\s*(:[^=\n]+)?=\s*MutableStateFlow"""), Severity.WARN,
            "Con `var` se puede reemplazar el flow completo y los colectores seguirían escuchando el viejo. Usa `val`.", focus = Regex("""\bvar\b""")),
        Rule(Regex("""\b(_\w+)\.value\s*=\s*\1\.value\b"""), Severity.INFO, "Leer y escribir `.value` por separado no es atómico. `update { }` evita perder cambios concurrentes."),
        Rule(Regex("""\bsuspend\s+fun\s+\w+\s*\([^)]*\)\s*:\s*Flow\s*<"""), Severity.WARN,
            "Una función que devuelve `Flow` no necesita `suspend`: el trabajo ocurre cuando alguien recolecta.", focus = Regex("""\bsuspend\b""")),
        Rule(Regex("""\bTODO\s*\(\s*\)"""), Severity.INFO, "Pendiente: este `TODO()` lanza `NotImplementedError` si se ejecuta."),
        Rule(Regex("""\.printStackTrace\s*\(\s*\)"""), Severity.INFO, "`printStackTrace()` se pierde en producción. ¿Lo registras o lo conviertes en un estado de error?"),
        Rule(Regex("""\blateinit\s+var\s+\w+\s*:\s*Context\b"""), Severity.WARN,
            "Guardar un `Context` en una propiedad de larga vida puede filtrar la Activity. Si lo necesitas, usa `applicationContext`."),
        Rule(Regex("""\bwhile\s*\(\s*true\s*\)"""), Severity.WARN, "Bucle infinito sin punto de cancelación. Agrega `ensureActive()` o un `delay`.",
            applies = { c, _ -> c.coroutines && !Regex("""isActive|ensureActive|delay\s*\(|yield\s*\(""").containsMatchIn(c.clean) }),
        Rule(Regex("""\bitems\s*\(\s*\w+\s*\)"""), Severity.INFO, "Sin `key`, las filas se identifican por posición. Con `key = { it.id }` conservan su estado al insertar o borrar.",
            applies = { c, _ -> c.lazyList }),
    )

    /**
     * Analiza [text]. [lexical] agrega errores de llaves, paréntesis y textos sin cerrar
     * (en el IDE ya los marca el compilador, así que el plugin los desactiva).
     */
    fun analyze(text: String, challenge: Challenge?, lexical: Boolean = true): Analysis {
        val t0 = System.nanoTime()
        val scan = scan(text)
        val starts = lineStarts(text)
        val lineOf = { idx: Int -> lineOf(starts, idx) }
        val raw = ArrayList<Diagnostic>()
        if (lexical) {
            raw += scan.lexErrors
            raw += bracketErrors(scan.clean, lineOf)
        }
        val ctx = RuleContext(scan)
        for (r in GENERAL_RULES) collect(raw, ctx.clean, r.pattern, r.focus, r.severity, r.message) { from -> r.applies(ctx, lineOf(from)) }
        challenge?.rules?.forEach { rd ->
            if (rd.special != null) { raw += Specials.run(rd.special, ctx); return@forEach }
            val p = rd.pattern ?: return@forEach
            if (rd.onlyIf != null && !rd.onlyIf.containsMatchIn(ctx.clean)) return@forEach
            if (rd.unless != null && rd.unless.containsMatchIn(ctx.clean)) return@forEach
            collect(raw, if (rd.onStrings) ctx.noComments else ctx.clean, p, rd.focus, rd.severity, rd.message) { true }
        }
        // Una sola marca por rango: gana la más severa.
        val byRange = LinkedHashMap<Long, Diagnostic>()
        for (d in raw) {
            val key = d.from.toLong() * 1_000_003L + d.to
            val prev = byRange[key]
            if (prev == null || d.severity.rank > prev.severity.rank) byRange[key] = d
        }
        val diags = byRange.values.sortedBy { it.from }.take(80).map { it.copy(line = lineOf(it.from)) }
        val objectives = challenge?.checks?.map { c ->
            val n = c.pattern.findAll(scan.noComments).take(50).count()
            Objective(c.label, if (c.negate) n == 0 else n >= c.min)
        } ?: emptyList()
        return Analysis(diags, objectives, (System.nanoTime() - t0) / 1000)
    }

    private inline fun collect(
        out: MutableList<Diagnostic>, src: String, pattern: Regex, focus: Regex?, sev: Severity, msg: String, accept: (Int) -> Boolean,
    ) {
        var guard = 0
        for (m in pattern.findAll(src)) {
            if (guard++ >= 40) break
            if (m.value.isEmpty()) continue
            var from = m.range.first
            var to = m.range.last + 1
            if (focus != null) focus.find(m.value)?.let { f -> from = m.range.first + f.range.first; to = from + f.value.length }
            if (!accept(from)) continue
            out += Diagnostic(from, to, sev, msg)
        }
    }

    fun lineStarts(text: String): IntArray {
        val list = ArrayList<Int>().apply { add(0) }
        for (k in text.indices) if (text[k] == '\n') list += k + 1
        return list.toIntArray()
    }

    fun lineOf(starts: IntArray, idx: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= idx) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun scan(src: String): Scan = Scanner(src).run()

    private class Scanner(private val src: String) {
        private val n = src.length
        private val clean = src.toCharArray()
        private val nc = src.toCharArray()
        private val errs = ArrayList<Diagnostic>()
        private var i = 0

        fun run(): Scan { code(false); return Scan(String(clean), String(nc), errs) }

        private fun blank(a: CharArray, from: Int, to: Int) { for (k in from until minOf(to, n)) if (a[k] != '\n') a[k] = ' ' }

        private fun code(stopOnClose: Boolean) {
            var depth = 0
            while (i < n) {
                val c = src[i]
                val c2 = if (i + 1 < n) src[i + 1] else '\u0000'
                if (c == '/' && c2 == '/') {
                    var e = src.indexOf('\n', i); if (e < 0) e = n
                    blank(clean, i, e); blank(nc, i, e); i = e; continue
                }
                if (c == '/' && c2 == '*') {
                    val e = src.indexOf("*/", i + 2)
                    if (e < 0) {
                        errs += Diagnostic(i, i + 2, Severity.ERROR, "Comentario de bloque sin cerrar: falta `*/`.")
                        blank(clean, i, n); blank(nc, i, n); i = n; return
                    }
                    blank(clean, i, e + 2); blank(nc, i, e + 2); i = e + 2; continue
                }
                if (c == '"') { str(); continue }
                if (c == '\'') {
                    val m = CHAR_LIT.find(src.substring(i, minOf(n, i + 9)))
                    val len = if (m != null) m.value.length else 1
                    if (len > 2) blank(clean, i + 1, i + len - 1)
                    i += len; continue
                }
                if (stopOnClose) {
                    if (c == '{') depth++ else if (c == '}') { if (depth == 0) return; depth-- }
                }
                i++
            }
        }

        private fun str() {
            val start = i
            val raw = src.startsWith("\"\"\"", i)
            i += if (raw) 3 else 1
            var cs = i
            while (i < n) {
                val c = src[i]
                if (!raw && c == '\\') { i += 2; continue }
                if (!raw && c == '\n') { errs += Diagnostic(start, i, Severity.ERROR, STR_OPEN); blank(clean, cs, i); return }
                if (c == '$' && i + 1 < n && src[i + 1] == '{') { blank(clean, cs, i); i += 2; code(true); if (i < n) i++; cs = i; continue }
                if (if (raw) src.startsWith("\"\"\"", i) else c == '"') { blank(clean, cs, i); i += if (raw) 3 else 1; return }
                i++
            }
            errs += Diagnostic(start, minOf(start + 1, n), Severity.ERROR, if (raw) "Texto `\"\"\"` sin cerrar." else STR_OPEN)
            blank(clean, cs, n)
        }

        companion object {
            val CHAR_LIT = Regex("""^'(\\u[0-9a-fA-F]{4}|\\.|[^'\\\n])'""")
            const val STR_OPEN = "Este texto no se cierra: falta la comilla `\"` final."
        }
    }

    private fun bracketErrors(clean: String, lineOf: (Int) -> Int): List<Diagnostic> {
        val close = mapOf(')' to '(', ']' to '[', '}' to '{')
        val open = mapOf('(' to ')', '[' to ']', '{' to '}')
        val st = ArrayList<Int>()
        val errs = ArrayList<Diagnostic>()
        for (k in clean.indices) {
            val c = clean[k]
            if (c in open) { st += k; continue }
            val want = close[c] ?: continue
            if (st.isEmpty()) { errs += Diagnostic(k, k + 1, Severity.ERROR, "Sobra este `$c`: no hay nada abierto que cerrar."); continue }
            val top = st.last()
            val oc = clean[top]
            if (oc == want) { st.removeAt(st.size - 1); continue }
            var j = st.size - 1
            while (j >= 0 && clean[st[j]] != want) j--
            if (j < 0) {
                errs += Diagnostic(k, k + 1, Severity.ERROR, "Sobra este `$c`: lo último abierto es `$oc` en la línea ${lineOf(top) + 1}.")
                continue
            }
            errs += Diagnostic(top, top + 1, Severity.ERROR,
                "Este `$oc` nunca se cierra: antes de cerrar con `$c` en la línea ${lineOf(k) + 1} falta un `${open[oc]}`.")
            while (st.size > j) st.removeAt(st.size - 1)
        }
        for (k in st.takeLast(3)) errs += Diagnostic(k, k + 1, Severity.ERROR, "Este `${clean[k]}` nunca se cierra.")
        return errs.take(4)
    }
}

/** Reglas programadas que un paquete de retos puede activar con `"special": "<nombre>"`. */
object Specials {
    fun run(name: String, c: RuleContext): List<Diagnostic> = when (name) {
        "expectActual" -> expectActual(c)
        "catchBeforeStateIn" -> catchBeforeStateIn(c)
        "withLockStale" -> withLockStale(c)
        else -> emptyList()
    }

    private fun expectActual(c: RuleContext): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val names = Regex("""\bexpect\s+fun\s+(\w+)""").findAll(c.clean).map { it.groupValues[1] }.toSet()
        for (m in Regex("""\bactual\s+fun\s+(\w+)""").findAll(c.clean)) {
            if (names.isNotEmpty() && m.groupValues[1] !in names) out += Diagnostic(m.range.first, m.range.last + 1, Severity.ERROR,
                "No hay un `expect fun ${m.groupValues[1]}`. El nombre de `actual` debe coincidir con el de `expect` (${names.joinToString()}).")
        }
        for (m in Regex("""\bexpect\s+fun\s+\w+\s*\([^)]*\)\s*(:\s*\w+\s*)?(\{|=)""").findAll(c.clean)) {
            out += Diagnostic(m.range.first, m.range.first + 10, Severity.ERROR, "Una declaración `expect` no lleva cuerpo: solo la firma. El cuerpo va en cada `actual`.")
        }
        if (names.isEmpty()) Regex("""\bactual\s+fun""").find(c.clean)?.let {
            out += Diagnostic(it.range.first, it.range.first + 6, Severity.ERROR, "Hay un `actual` sin su `expect` en código común. Declara primero la firma con `expect fun`.")
        }
        return out
    }

    private fun catchBeforeStateIn(c: RuleContext): List<Diagnostic> {
        val i = Regex("""\.stateIn\s*\(""").find(c.clean)?.range?.first ?: return emptyList()
        val j = Regex("""\.catch\s*\{""").find(c.clean)?.range?.first ?: return emptyList()
        if (j >= i) return emptyList()
        return listOf(Diagnostic(j, j + 6, Severity.INFO,
            "Un `.catch` en la cadena termina el flujo tras el primer error: no habría más búsquedas. ¿Puedes capturar el error dentro de `mapLatest`?"))
    }

    private fun withLockStale(c: RuleContext): List<Diagnostic> {
        val m = Regex("""\.withLock\s*\{""").find(c.clean) ?: return emptyList()
        if (c.clean.substring(m.range.first).contains("staleToken")) return emptyList()
        return listOf(Diagnostic(m.range.first + 1, m.range.first + 9, Severity.INFO,
            "Dentro del lock, ¿cómo sabes si otra corrutina ya renovó el token mientras esperabas? Compara con `staleToken`."))
    }
}

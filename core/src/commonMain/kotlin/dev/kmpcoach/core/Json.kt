package dev.kmpcoach.core

/**
 * JSON mínimo y sin dependencias: suficiente para los paquetes de retos y para hablar con las APIs de IA.
 * parse() devuelve Map<String, Any?>, List<Any?>, String, Long, Double, Boolean o null.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) throw JsonException("Contenido extra en la posición ${p.i}")
        return v
    }

    /** Extrae el primer valor JSON de la respuesta de un modelo: JSON puro, un bloque ```json o el texto entre la primera llave y la última. */
    fun extract(text: String): Any? {
        val t = text.trim()
        runCatching { return parse(t) }
        Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(t)?.let { m -> runCatching { return parse(m.groupValues[1].trim()) } }
        val a = t.indexOf('{')
        val b = t.lastIndexOf('}')
        if (a >= 0 && b > a) return parse(t.substring(a, b + 1))
        throw JsonException("La respuesta no contiene JSON")
    }

    fun write(v: Any?): String = StringBuilder().also { writeTo(it, v) }.toString()

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Number, is Boolean -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); writeTo(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, x)
                }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            skipWs()
            if (i >= s.length) fail("JSON incompleto")
            val c = s[i]
            return when {
                c == '{' -> obj()
                c == '[' -> arr()
                c == '"' -> str()
                c == 't' -> lit("true", true)
                c == 'f' -> lit("false", false)
                c == 'n' -> lit("null", null)
                c == '-' || c.isDigit() -> num()
                else -> fail("Carácter inesperado '$c' en la posición $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Se esperaba $word en la posición $i")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek('}')) { i++; return m }
            while (true) {
                skipWs()
                if (!peek('"')) fail("Se esperaba una clave en la posición $i")
                val k = str()
                skipWs(); expect(':')
                m[k] = value()
                skipWs()
                if (peek(',')) { i++; continue }
                expect('}')
                return m
            }
        }

        private fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            skipWs()
            if (peek(']')) { i++; return l }
            while (true) {
                l.add(value())
                skipWs()
                if (peek(',')) { i++; continue }
                expect(']')
                return l
            }
        }

        private fun str(): String {
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (i >= s.length) break
                when (val e = s[i++]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        if (i + 4 > s.length) fail("Escape \\u incompleto")
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
            }
            fail("Texto sin cerrar")
        }

        private fun num(): Number {
            val st = i
            if (peek('-')) i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("Número inválido '$t'")
        }

        private fun peek(c: Char) = i < s.length && s[i] == c
        private fun expect(c: Char) { if (!peek(c)) fail("Se esperaba '$c' en la posición $i"); i++ }
        private fun fail(msg: String): Nothing = throw JsonException(msg)
    }
}

class JsonException(message: String) : RuntimeException(message)

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()
fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()
fun Map<String, Any?>.str(key: String): String? = this[key]?.let { if (it is String) it else it.toString() }
fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.trim().toIntOrNull()
    else -> null
}
fun Map<String, Any?>.bool(key: String): Boolean = this[key] == true

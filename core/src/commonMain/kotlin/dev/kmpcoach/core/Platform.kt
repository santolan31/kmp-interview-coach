package dev.kmpcoach.core

import kotlin.math.abs
import kotlin.math.floor

/** Fecha de hoy en la zona horaria local, como `aaaa-mm-dd`. */
expect fun todayIso(): String

/**
 * Fecha del calendario sin hora, en Kotlin puro (reemplaza a `java.time.LocalDate`, que no existe en JavaScript).
 * Se guarda como número de días desde el 1970-01-01, así sumar días es sumar un número.
 */
class IsoDate private constructor(val epochDay: Long) : Comparable<IsoDate> {

    fun plusDays(days: Long): IsoDate = IsoDate(epochDay + days)
    fun isAfter(other: IsoDate): Boolean = epochDay > other.epochDay
    override fun compareTo(other: IsoDate): Int = epochDay.compareTo(other.epochDay)
    override fun equals(other: Any?) = other is IsoDate && other.epochDay == epochDay
    override fun hashCode() = epochDay.hashCode()

    /** `aaaa-mm-dd`, igual que `LocalDate.toString()`. */
    override fun toString(): String {
        val (y, m, d) = civil(epochDay)
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    companion object {
        fun of(year: Int, month: Int, day: Int): IsoDate {
            require(month in 1..12 && day in 1..31) { "Fecha inválida: $year-$month-$day" }
            return IsoDate(daysFromCivil(year.toLong(), month, day))
        }

        /** Lee `aaaa-mm-dd`. Lanza [IllegalArgumentException] si no tiene ese formato. */
        fun parse(text: String): IsoDate {
            val p = text.trim().split('-')
            require(p.size == 3) { "Fecha inválida: $text" }
            return of(p[0].toInt(), p[1].toInt(), p[2].toInt())
        }

        fun today(): IsoDate = parse(todayIso())

        // Algoritmo de Howard Hinnant (días ↔ calendario gregoriano), válido para cualquier año.
        private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = (m + 9) % 12
            val doy = (153 * mp + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + doe - 719468
        }

        private fun civil(z0: Long): Triple<Int, Int, Int> {
            val z = z0 + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return Triple(y, m, d)
        }
    }
}

/** SHA-1 en Kotlin puro: da exactamente lo mismo que `MessageDigest.getInstance("SHA-1")`, en JVM y en JavaScript. */
object Sha1 {
    fun digest(input: ByteArray): ByteArray {
        val ml = input.size.toLong() * 8
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val msg = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) msg[msg.size - 1 - i] = (ml ushr (8 * i)).toByte()

        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()
        val w = IntArray(80)
        for (chunk in msg.indices step 64) {
            for (i in 0 until 16) {
                val j = chunk + i * 4
                w[i] = ((msg[j].toInt() and 0xff) shl 24) or ((msg[j + 1].toInt() and 0xff) shl 16) or
                    ((msg[j + 2].toInt() and 0xff) shl 8) or (msg[j + 3].toInt() and 0xff)
            }
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val (f, k) = when (i) {
                    in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                    in 20..39 -> (b xor c xor d) to 0x6ED9EBA1
                    in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                }
                val temp = a.rotateLeft(5) + f + e + k + w[i]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
        }
        val out = ByteArray(20)
        intArrayOf(h0, h1, h2, h3, h4).forEachIndexed { n, h -> for (i in 0 until 4) out[n * 4 + i] = (h ushr (24 - 8 * i)).toByte() }
        return out
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/**
 * Número con [decimals] decimales, redondeando la mitad hacia arriba, como `String.format(Locale.ROOT, "%.2f", v)`
 * (que no existe fuera de la JVM). Igual que Java, redondea sobre la escritura decimal más corta del número (`1.45`)
 * y no sobre su valor binario (`1.4499999…`): así `fixed(1.45, 1)` da `1.5`, como `String.format`.
 */
fun fixed(value: Double, decimals: Int): String {
    val (intPart, fracPart) = plainDecimal(abs(value))
    val frac = fracPart.padEnd(decimals + 1, '0')
    var digits = intPart + frac.take(decimals)                 // sin el punto
    if (frac[decimals] >= '5') digits = increment(digits)       // mitad hacia arriba
    val whole = digits.dropLast(decimals).ifEmpty { "0" }
    val text = if (decimals == 0) whole else whole + "." + digits.takeLast(decimals)
    val isZero = digits.all { it == '0' }
    return if (value < 0 && !isZero) "-$text" else text
}

/** Parte entera y decimales de la escritura más corta del número, sin notación científica (`1.0E-5` → `0`, `00001`). */
private fun plainDecimal(v: Double): Pair<String, String> {
    val s = v.toString().lowercase()
    val mantissa = s.substringBefore('e')
    val exp = if ('e' in s) s.substringAfter('e').toInt() else 0
    val int0 = mantissa.substringBefore('.')
    val frac0 = if ('.' in mantissa) mantissa.substringAfter('.') else ""
    val all = int0 + frac0
    val point = int0.length + exp                               // dónde queda el punto tras aplicar el exponente
    return when {
        point <= 0 -> "0" to "0".repeat(-point) + all
        point >= all.length -> all + "0".repeat(point - all.length) to ""
        else -> all.substring(0, point) to all.substring(point)
    }.let { (i, f) -> (i.trimStart('0').ifEmpty { "0" }) to f }
}

/** Suma 1 a un número escrito como dígitos ("129" → "130", "99" → "100"). */
private fun increment(digits: String): String {
    val c = digits.toCharArray()
    var i = c.lastIndex
    while (i >= 0) {
        if (c[i] == '9') { c[i] = '0'; i-- } else { c[i] = c[i] + 1; return c.concatToString() }
    }
    return "1" + c.concatToString()
}

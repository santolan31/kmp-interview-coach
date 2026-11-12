package dev.kmpcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Lo que reemplaza a Java en el core: deben dar lo mismo que antes, en JVM y en JavaScript. */
class PlatformTest {

    @Test
    fun sha1MatchesTheOfficialVectors() {
        // Vectores de prueba de la norma FIPS 180 (los mismos que da MessageDigest.getInstance("SHA-1")).
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Sha1.hex(Sha1.digest("".encodeToByteArray())))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Sha1.hex(Sha1.digest("abc".encodeToByteArray())))
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            Sha1.hex(Sha1.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
        // Un millón de «a»: cruza muchos bloques de 64 bytes.
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", Sha1.hex(Sha1.digest(ByteArray(1_000_000) { 'a'.code.toByte() })))
    }

    @Test
    fun datesAddDaysAcrossMonthsYearsAndLeapDays() {
        assertEquals("2026-11-11", IsoDate.of(2026, 11, 10).plusDays(1).toString())
        assertEquals("2027-01-01", IsoDate.of(2026, 12, 31).plusDays(1).toString())
        assertEquals("2028-02-29", IsoDate.of(2028, 2, 28).plusDays(1).toString())
        assertEquals("2026-03-01", IsoDate.of(2026, 2, 28).plusDays(1).toString())
        assertEquals("2026-12-12", IsoDate.parse("2026-11-10").plusDays(32).toString())
        assertEquals(0L, IsoDate.of(1970, 1, 1).epochDay)
        assertTrue(IsoDate.parse("2026-11-14").isAfter(IsoDate.parse("2026-11-13")))
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(todayIso()))
    }

    @Test
    fun fixedRoundsHalfUpLikeStringFormat() {
        // 1.45 en binario es 1.4499999...: String.format redondea sobre "1.45" y da 1.5. Debe dar lo mismo.
        assertEquals("1.5", fixed(1.45, 1))
        assertEquals("0.00001", fixed(1.0E-5, 5))
        assertEquals("1234567.0", fixed(1234567.0, 1))
        assertEquals("10.0", fixed(9.96, 1))
        assertEquals("0.0125", fixed(0.0125, 4))
        assertEquals("0.013", fixed(0.0125, 3))
        assertEquals("12.35", fixed(12.345678, 2))
        assertEquals("0.00", fixed(0.001, 2))
        assertEquals("2.0", fixed(2.0, 1))
        assertEquals("-1.25", fixed(-1.2499, 2))
    }
}

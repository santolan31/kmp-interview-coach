package dev.kmpcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Retomar un proyecto y las rutas con tildes (el caso real de «Facturaciónapp/technova-solutions»). */
class ResumeTest {
    private val nfcPath = "/Users/s/Documents/Facturaciónapp/technova-solutions"   // «ó» en una sola letra
    private val nfdPath = "/Users/s/Documents/Facturaciónapp/technova-solutions"  // «o» + tilde aparte (como macOS)

    @Test
    fun accentsWrittenEitherWayAreTheSamePath() {
        assertFalse(nfcPath == nfdPath, "se ven iguales pero no son el mismo texto")
        assertEquals(nfc(nfcPath), nfc(nfdPath))
        assertEquals(2, nfcAndNfd(nfcPath).size)
        assertEquals(listOf("/Users/s/maco"), nfcAndNfd("/Users/s/maco"), "sin tildes, una sola forma")
        // Antigravity escribe la ruta con la «ó» junta; el proyecto puede venir con la tilde aparte.
        assertTrue(AntigravityTranscripts.belongsTo("Active Document: $nfcPath/src/index.js", nfdPath))
        assertEquals(nfc(nfcPath), CopilotSessions.folderOf("""{"folder":"file:///Users/s/Documents/Facturaci%C3%B3napp/technova-solutions"}"""))
    }

    @Test
    fun agoSaysHowOldInWords() {
        val now = 1_800_000_000_000L
        assertEquals("hace 5 min", Resume.ago(now - 5 * 60_000, now))
        assertEquals("hace 3 h", Resume.ago(now - 3 * 3_600_000, now))
        assertEquals("hace 4 días", Resume.ago(now - 4 * 86_400_000L, now))
        assertEquals("hace 3 meses", Resume.ago(now - 100 * 86_400_000L, now))
        assertEquals("hace más de un año", Resume.ago(now - 400 * 86_400_000L, now))
    }

    @Test
    fun handoffCarriesThePlanWithWhatIsDoneAndAsksForTheNextSteps() {
        val h = Resume.handoff("technova-solutions", "Antigravity", "hace 3 meses", "Constructor de cotizaciones",
            listOf("Paso 1: el formulario" to true, "Paso 2: el PDF" to false, "Paso 3: probarlo" to null), null)
        assertTrue(h.startsWith("Retomo el proyecto «technova-solutions»"))
        assertTrue("- [x] Paso 1: el formulario (hecho)" in h && "- [ ] Paso 2: el PDF (pendiente)" in h && "- [?] Paso 3" in h)
        assertTrue("## Paso N" in h && "TODO n" in h, "pide los pasos en el formato que entiende la guía")
        val noPlan = Resume.handoff("x", "Claude Code", "hace 2 meses", null, emptyList(), "Estudiante: arregla el login")
        assertTrue("Esto fue lo último que hablamos" in noPlan && "arregla el login" in noPlan)
    }
}

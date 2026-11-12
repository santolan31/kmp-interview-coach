package dev.kmpcoach.core

/**
 * Texto en forma NFC: la «ó» como una sola letra. macOS guarda los nombres de archivo con la letra y la tilde por
 * separado (NFD) y otras herramientas los escriben juntos: se ven iguales, pero no son el mismo texto. Se normaliza
 * antes de comparar rutas.
 */
expect fun nfc(text: String): String

/** Las dos formas en que puede estar escrita una ruta con tildes (juntas y separadas), sin repetir si son iguales. */
expect fun nfcAndNfd(text: String): List<String>

/**
 * Retomar un proyecto: su última conversación con una IA es de hace tiempo (semanas o meses). Lo normal es abrir un
 * chat nuevo; para que la IA no empiece de cero, el Mentor arma un mensaje con el plan anterior y qué pasos ya están
 * hechos según el código actual, listo para pegar.
 */
object Resume {
    /** Pasado este tiempo sin conversaciones del proyecto, se considera que lo estás retomando. */
    const val RECENT_MS = 3L * 24 * 3600 * 1000

    /** «hace 5 min», «hace 3 h», «hace 4 días», «hace 3 meses», «hace más de un año». */
    fun ago(thenMs: Long, nowMs: Long): String {
        val mins = ((nowMs - thenMs) / 60_000).coerceAtLeast(0)
        val days = mins / 1440
        return when {
            mins < 60 -> "hace $mins min"
            mins < 48 * 60 -> "hace ${mins / 60} h"
            days < 31 -> "hace $days días"
            days < 365 -> (days / 30).let { if (it <= 1) "hace 1 mes" else "hace $it meses" }
            else -> "hace más de un año"
        }
    }

    /**
     * El mensaje para pegar en un chat nuevo. [steps]: cada paso del plan anterior con su estado según el código
     * actual (true = hecho, false = pendiente, null = no se puede comprobar solo). Sin plan, va [excerpt]: lo último que
     * se habló.
     */
    fun handoff(
        project: String, source: String, ago: String, title: String?, steps: List<Pair<String, Boolean?>>, excerpt: String?,
    ): String = buildString {
        append("Retomo el proyecto «$project» después de un tiempo: la última conversación con $source es de $ago")
        title?.takeIf { it.isNotBlank() }?.let { append(" («${it.trim()}»)") }
        appendLine(".")
        appendLine()
        if (steps.isNotEmpty()) {
            appendLine("Este era el plan, con lo que ya está hecho según el código actual:")
            steps.forEach { (label, done) ->
                appendLine(when (done) {
                    true -> "- [x] $label (hecho)"
                    false -> "- [ ] $label (pendiente)"
                    null -> "- [?] $label (no se puede comprobar solo: revísalo)"
                })
            }
        } else if (!excerpt.isNullOrBlank()) {
            appendLine("Esto fue lo último que hablamos:")
            appendLine("---")
            appendLine(excerpt.trim().let { if (it.length > 4000) it.take(3999) + "…" else it })
            appendLine("---")
        }
        appendLine()
        appendLine("Antes de seguir, revisa el estado actual del código: pudo cambiar desde entonces. Después dame los siguientes " +
            "pasos con un título «## Paso N: qué se logra» por paso y, antes de cada bloque ```diff, un enlace al archivo con sus " +
            "líneas (por ejemplo [Archivo.kt:27-32](ruta/Archivo.kt#L27-L32)). En vez de la solución, deja huecos " +
            "`// TODO n: qué hacer` para que yo escriba el código. Termina cada paso con **Cómo verificarlo:** y una frase.")
    }
}

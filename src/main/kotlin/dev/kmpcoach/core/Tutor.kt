package dev.kmpcoach.core

enum class CommentType(val id: String, val label: String) {
    ERROR("error", "Error"), MEJORA("mejora", "Mejora"), PREGUNTA("pregunta", "Pregunta"), BIEN("bien", "Bien");

    companion object {
        fun of(id: String?): CommentType = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: MEJORA
    }
}

data class TutorComment(val line: Int, val type: CommentType, val message: String)

data class TutorReview(val status: String, val summary: String, val comments: List<TutorComment>, val nextStep: String) {
    val statusLabel: String get() = STATUS[status] ?: "Bien encaminado"

    companion object {
        val STATUS = linkedMapOf(
            "listo" to "Cumple el reto",
            "encaminado" to "Bien encaminado",
            "a_medias" to "A medias",
            "desviado" to "Fuera del objetivo",
        )
    }
}

object ReviewParser {
    /** Convierte la respuesta del modelo en una revisión; tolera texto alrededor del JSON y líneas fuera de rango. */
    fun parse(text: String, lineCount: Int, maxComments: Int = 6): TutorReview {
        val root = Json.extract(text).asMap()
        val n = lineCount.coerceAtLeast(1)
        val comments = root["comentarios"].asList().mapNotNull { raw ->
            val m = raw.asMap()
            val msg = m.str("mensaje")?.trim().orEmpty()
            if (msg.isEmpty()) return@mapNotNull null
            TutorComment((m.int("linea") ?: 1).coerceIn(1, n), CommentType.of(m.str("tipo")), msg)
        }.take(maxComments.coerceAtLeast(1)).sortedBy { it.line }
        val status = m(root, "estado").takeIf { it in TutorReview.STATUS } ?: "encaminado"
        return TutorReview(status, m(root, "resumen"), comments, m(root, "siguientePaso"))
    }

    private fun m(root: Map<String, Any?>, k: String) = root.str(k)?.trim().orEmpty()
}

data class PromptOptions(val level: Level, val language: String, val maxComments: Int)

object Prompts {
    private const val MAX_CHARS = 60_000

    internal fun lang(o: PromptOptions) =
        if (o.language == "en") "Write every message in English (keep the JSON keys exactly as specified)." else "Escribe en español neutro."

    fun numbered(code: String): String {
        val src = if (code.length > MAX_CHARS) code.substring(0, MAX_CHARS) else code
        val body = src.split('\n').mapIndexed { i, l -> (i + 1).toString().padStart(4) + "| " + l }.joinToString("\n")
        return if (src.length < code.length) "$body\n… (archivo recortado: solo se muestran las primeras líneas)" else body
    }

    private fun context(challenge: Challenge?, code: String, analysis: Analysis?, fileName: String?): String = buildString {
        if (challenge != null) {
            appendLine("RETO (nivel ${challenge.level.label}): ${challenge.title}")
            appendLine("Enunciado: ${challenge.brief}")
            appendLine()
            appendLine("Objetivos que revisa el evaluador automático ([x] = ya detectado en el código):")
            analysis?.objectives?.forEach { appendLine("- [${if (it.pass) "x" else " "}] ${it.label.replace("`", "")}") }
            appendLine()
            if (challenge.solution.isNotBlank()) {
                appendLine("Solución de referencia, SOLO para tu criterio. No la copies ni la reveles; nunca muestres más de una línea de ella:")
                appendLine("```kotlin"); appendLine(challenge.solution); appendLine("```")
                appendLine()
            }
        } else {
            appendLine("ARCHIVO: ${fileName ?: "sin nombre"}. No es un reto: es código del propio proyecto del estudiante (Android o Kotlin Multiplatform).")
            appendLine()
        }
        val diags = analysis?.diagnostics.orEmpty().take(12)
        appendLine("Avisos del analizador local (el estudiante ya los ve subrayados en el editor):")
        if (diags.isEmpty()) appendLine("- (ninguno)")
        diags.forEach { appendLine("- línea ${it.line + 1} (${it.severity.label}): ${it.message.replace("`", "")}") }
        appendLine()
        appendLine("Código actual (el número antes de \"|\" es la línea):")
        appendLine("```kotlin"); appendLine(numbered(code)); append("```")
    }

    fun reviewSystem(o: PromptOptions) = """
Eres un entrevistador técnico de Android y Kotlin Multiplatform que acompaña a un estudiante de nivel ${o.level.label} mientras programa. Eres un mentor exigente pero amable: haces que el estudiante piense y justifique sus decisiones, como en una sesión de programación en pareja.

Revisa el código como un mentor sentado al lado:
- Da entre 1 y ${o.maxComments} comentarios, los más importantes primero, cada uno anclado a la línea exacta a la que se refiere.
- Tipos: "error" (no compilaría o es incorrecto), "mejora" (funciona, pero hay una forma más idiomática o segura), "pregunta" (una pregunta del estilo "¿por qué ...?" que obligue a justificar una decisión), "bien" (algo bien resuelto; máximo uno).
- Si falta algo para cumplir el objetivo, nombra la función o API que conviene usar ("recuerda que existe `debounce`"), sin escribir la solución. Fragmentos de código de una línea como máximo.
- Si el código está casi vacío, ancla el comentario donde debería empezar y da el primer paso.
- No repitas los avisos del analizador local salvo que aportes algo nuevo.
- No inventes problemas: si algo está bien, no lo marques. Las referencias sin resolver por imports faltantes no son errores.
- Frases cortas, código entre backticks. ${lang(o)}

Responde solo con JSON con esta forma:
{"estado":"encaminado","resumen":"una frase sobre cómo va","comentarios":[{"linea":4,"tipo":"pregunta","mensaje":"..."}],"siguientePaso":"una frase con lo próximo que debe hacer"}
"estado" es uno de: "listo" (cumple el objetivo), "encaminado", "a_medias", "desviado".
""".trim()

    fun reviewUser(challenge: Challenge?, code: String, analysis: Analysis?, fileName: String?) =
        context(challenge, code, analysis, fileName) + "\n\nRevisa este código y responde solo con el JSON."

    /**
     * Mensaje con el que el estudiante responde a un comentario de la revisión. Cita el comentario para que el tutor
     * sepa a cuál se refiere sin tener que volver a contárselo.
     */
    fun replyToComment(line: Int?, type: CommentType, comment: String, answer: String): String {
        val where = if (line != null) "de la línea $line" else "de la revisión"
        val quoted = comment.replace("`", "").replace(Regex("\\s+"), " ").trim()
        return "Respondo a tu comentario $where (${type.label}: «$quoted»):\n${answer.trim()}"
    }

    fun chatSystem(o: PromptOptions, challenge: Challenge?, code: String, analysis: Analysis?, fileName: String?) = """
Eres un mentor de Android y Kotlin Multiplatform que ayuda a un estudiante de nivel ${o.level.label} a entender el código que escribe, el suyo o el que le generó una IA. Responde sus preguntas en 120 palabras o menos, de forma directa y concreta, y refiérete a líneas de su código cuando ayude. Explica el porqué de las cosas. No le des la solución completa: guíalo con pistas y, si hace falta un ejemplo, usa otros datos distintos a los de su código. Si le hiciste preguntas de comprensión y las responde, dile qué está bien y qué no; si falló, dale una pista en vez de la respuesta. Si responde a un comentario tuyo de la revisión (empieza con «Respondo a tu comentario»), evalúa su respuesta: di en una frase si va bien, qué acierta y qué le falta, y termina con una sola pregunta o pista si aún no lo tiene. Si pide la solución, dale el siguiente paso y recuérdale que puede pulsar «Me rindo» en el panel. ${lang(o)}

${if (code.isBlank()) "El estudiante no tiene un archivo Kotlin abierto." else context(challenge, code, analysis, fileName)}
""".trim()
}

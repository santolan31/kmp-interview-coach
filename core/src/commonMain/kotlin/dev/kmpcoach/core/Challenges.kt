package dev.kmpcoach.core

enum class Level(val id: String, val label: String) {
    JUNIOR("junior", "Junior"),
    SEMI("semi", "Semi Senior"),
    SENIOR("senior", "Senior");

    override fun toString() = label

    companion object {
        fun of(id: String?): Level = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: JUNIOR
    }
}

/** Un objetivo verificable del reto: el patrón debe aparecer (o no aparecer, si [negate]) en el código sin comentarios. */
class CheckDef(val label: String, val pattern: Regex, val negate: Boolean, val min: Int)

/**
 * Regla del analizador en vivo. Si [special] no es null, se ejecuta una regla programada en [Specials].
 * [onStrings]: busca también dentro de textos (útil para SQL en @Query). [onlyIf] / [unless]: condiciones sobre todo el archivo.
 */
class RuleDef(
    val pattern: Regex?,
    val severity: Severity,
    val message: String,
    val focus: Regex? = null,
    val onStrings: Boolean = false,
    val onlyIf: Regex? = null,
    val unless: Regex? = null,
    val special: String? = null,
)

class Challenge(
    val id: String,
    val level: Level,
    val title: String,
    val topic: String,
    val minutes: Int,
    val brief: String,
    val starter: String,
    val checks: List<CheckDef>,
    val rules: List<RuleDef>,
    val solution: String,
    val notes: List<String>,
    val packName: String,
) {
    /** Primera línea que el plugin escribe en el archivo del reto; así sabe qué reto estás resolviendo. */
    val header: String get() = "// coach:$id · $title"
    override fun toString() = title
}

object ChallengeParser {
    private val MARKER = Regex("""^\s*//\s*coach:([\w.-]+)""")

    /** Busca el marcador `// coach:<id>` en las primeras líneas del archivo. */
    fun markerId(text: String): String? =
        text.lineSequence().take(3).firstNotNullOfOrNull { MARKER.find(it)?.groupValues?.get(1) }

    /** Lee un paquete de retos. Los errores (regex inválida, campos faltantes) se agregan a [warnings] y el reto o la regla se omite. */
    fun parsePack(json: String, source: String, warnings: MutableList<String>): List<Challenge> {
        val root = try { Json.parse(json).asMap() } catch (e: JsonException) {
            warnings += "$source: JSON inválido (${e.message})"
            return emptyList()
        }
        val packName = root.str("name") ?: source
        return root["challenges"].asList().mapNotNull { raw ->
            val m = raw.asMap()
            val id = m.str("id")
            val title = m.str("title")
            val starter = m.str("starter")
            if (id.isNullOrBlank() || title.isNullOrBlank() || starter == null) {
                warnings += "$source: un reto no tiene id, title o starter"
                return@mapNotNull null
            }
            fun rx(p: String?, what: String): Regex? = p?.let {
                try { Regex(it) } catch (e: Exception) { warnings += "$source/$id: $what inválida: ${e.message?.lineSequence()?.first()}"; null }
            }
            val checks = m["checks"].asList().mapNotNull { c ->
                val cm = c.asMap()
                val re = rx(cm.str("pattern"), "regex de objetivo") ?: return@mapNotNull null
                CheckDef(cm.str("label") ?: re.pattern, re, cm.bool("not"), cm.int("min") ?: 1)
            }
            val rules = m["rules"].asList().mapNotNull { r ->
                val rm = r.asMap()
                val special = rm.str("special")
                val re = if (special == null) rx(rm.str("pattern"), "regex de regla") ?: return@mapNotNull null else null
                RuleDef(
                    pattern = re,
                    severity = Severity.of(rm.str("severity")),
                    message = rm.str("message") ?: "",
                    focus = rx(rm.str("focus"), "focus"),
                    onStrings = rm.bool("onStrings"),
                    onlyIf = rx(rm.str("onlyIf"), "onlyIf"),
                    unless = rx(rm.str("unless"), "unless"),
                    special = special,
                )
            }
            Challenge(
                id = id.trim(),
                level = Level.of(m.str("level")),
                title = title,
                topic = m.str("topic") ?: "",
                minutes = m.int("minutes") ?: 0,
                brief = m.str("brief") ?: "",
                starter = starter,
                checks = checks,
                rules = rules,
                solution = m.str("solution") ?: "",
                notes = m["notes"].asList().mapNotNull { it as? String },
                packName = packName,
            )
        }
    }
}

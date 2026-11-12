@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package dev.kmpcoach.core

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

/**
 * La puerta del core hacia la extensión de VS Code (TypeScript). Todo entra y sale como texto JSON: así TypeScript
 * solo hace `JSON.parse` y no depende de cómo Kotlin/JS representa listas o clases. La interfaz (paneles, editor)
 * vive en TypeScript; la lógica, aquí, la misma que usa el plugin de Android Studio.
 */
@JsExport
object MentorApi {

    // ---------- proveedores y llamadas a la IA ----------

    /** Proveedores disponibles: clave de API o suscripción por su programa de terminal. */
    fun providers(): String = Json.write(ProviderPreset.entries.map {
        mapOf(
            "id" to it.id, "label" to it.label, "subscription" to it.isSubscription, "needsKey" to it.needsKey,
            "defaultModel" to it.defaultModel, "baseUrl" to it.baseUrl, "billingUrl" to AiClients.billingUrl(it),
            "executable" to it.cli?.executable,
        )
    })

    /** Mensaje si la configuración está incompleta; null si se puede usar. [config]: {provider, baseUrl, model, apiKey}. */
    fun validate(config: String): String? = AiClients.validate(cfg(config))

    /** Ruta del programa de la suscripción si está instalado; null si no (o si el proveedor es de API). */
    fun findCli(providerId: String, configuredPath: String?): String? =
        ProviderPreset.of(providerId).cli?.let { defaultProcessRunner().find(it, configuredPath?.takeIf(String::isNotBlank)) }

    /**
     * Pide una respuesta (reintenta con más espacio si llega cortada). Nunca falla: devuelve
     * {ok:true, text, inputTokens, outputTokens, costUsd} o {ok:false, error} con un mensaje para la persona.
     */
    fun complete(config: String, system: String, messages: String, maxTokens: Int): Promise<String> = GlobalScope.promise {
        val c = cfg(config)
        try {
            AiClients.validate(c)?.let { throw AiException(it) }
            val r = AiClients.create(c).completeFull(system, msgs(messages), maxTokens)
            Json.write(mapOf("ok" to true, "text" to r.text, "inputTokens" to r.usage?.inputTokens, "outputTokens" to r.usage?.outputTokens, "costUsd" to r.usage?.costUsd))
        } catch (e: Throwable) {
            Json.write(mapOf("ok" to false, "error" to AiClients.friendly(e, c)))
        }
    }

    /** {ok:true, models:[…]} o {ok:false, error}. */
    fun listModels(config: String): Promise<String> = GlobalScope.promise {
        val c = cfg(config)
        try {
            Json.write(mapOf("ok" to true, "models" to AiClients.create(c).listModels()))
        } catch (e: Throwable) {
            Json.write(mapOf("ok" to false, "error" to AiClients.friendly(e, c)))
        }
    }

    // ---------- revisión, chat y pistas ----------

    /** {system, user} para revisar el archivo. [options]: {level, language, maxComments}. [rules]: {source, text} o null. */
    fun reviewPrompt(options: String, path: String, code: String, session: String?, rules: String?): String {
        val o = opts(options, path)
        val analysis = if (CodeLanguage.of(path) == CodeLanguage.KOTLIN) Analyzer.analyze(code, null, lexical = false) else null
        return Json.write(mapOf("system" to Prompts.reviewSystem(o), "user" to Prompts.reviewUser(null, code, analysis, path, session, rulesOf(rules))))
    }

    /** {status, statusLabel, summary, nextStep, comments:[{line, type, typeLabel, message}]}; lanza si no es JSON. */
    fun parseReview(text: String, lineCount: Int, maxComments: Int): String {
        val r = ReviewParser.parse(text, lineCount, maxComments)
        return Json.write(mapOf(
            "status" to r.status, "statusLabel" to r.statusLabel, "summary" to r.summary, "nextStep" to r.nextStep,
            "comments" to r.comments.map { mapOf("line" to it.line, "type" to it.type.id, "typeLabel" to it.type.label, "message" to it.message) },
        ))
    }

    /** Instrucciones del chat (con el archivo abierto, si lo hay). [noPlan]: el Mentor está encendido y no encontró el plan. */
    fun chatSystem(options: String, path: String?, code: String, session: String?, rules: String?, noPlan: Boolean): String =
        Prompts.chatSystem(opts(options, path), null, code, null, path, session, rulesOf(rules), noPlan)

    /** Primer mensaje al responder un comentario: lo cita para que el Mentor sepa a cuál respondes. */
    fun replyToComment(line: Int, type: String, comment: String, answer: String): String =
        Prompts.replyToComment(line.takeIf { it > 0 }, CommentType.of(type), comment, answer)

    /** Siguiente escalón de la escalera de pistas tras [lastLevel] (null = primera); null si ya no quedan. */
    fun nextHintLevel(lastLevel: String?): String? =
        if (lastLevel == null) HintLevel.QUESTION.name else HintLevel.valueOf(lastLevel).next?.name

    /** {name, number, label} de cada escalón, para mostrar «Pista 2/4 · Concepto». */
    fun hintLevels(): String = Json.write(HintLevel.entries.map { mapOf("name" to it.name, "number" to it.number, "label" to it.label) })

    /**
     * {system, user} para una pista. [problems]: [{line, message}] del editor. [previous]: pistas ya dadas
     * [{level, line, text}]. [goal]: el extracto del plan, si lo hay.
     */
    fun hintPrompt(
        options: String, path: String, code: String, problems: String, previous: String, focusLine: Int, level: String, goal: String?, rules: String?,
    ): String {
        val o = opts(options, path)
        val lv = HintLevel.valueOf(level)
        val user = HintPrompts.hintUser(code, path, problemsOf(problems), hintsOf(previous), focusLine.takeIf { it > 0 }, lv, goal, emptyList(), rulesOf(rules))
        return Json.write(mapOf("system" to HintPrompts.hintSystem(o, lv), "user" to user))
    }

    /** Lee la pista. Si regala código del ejercicio, [leaks] es true y [redacted] trae el texto con ese fragmento oculto. */
    fun parseHint(text: String, code: String, lineCount: Int, level: String, fallbackLine: Int): String {
        val lv = HintLevel.valueOf(level)
        val h = HintParser.parse(text, lineCount, lv, fallbackLine)
        val leaks = CodeGuard.leaks(h.text, code, lv)
        return Json.write(mapOf(
            "level" to lv.name, "line" to h.line, "problem" to h.problem, "text" to h.text, "concept" to h.concept,
            "leaks" to leaks, "redacted" to (if (leaks) CodeGuard.redact(h.text, code, lv) else null),
        ))
    }

    /** Lo que se le pide al modelo si su pista regalaba código. */
    fun leakRetry(): String = HintPrompts.LEAK_RETRY

    /** {system, user} para «Me rindo»: la solución explicada. */
    fun giveUpPrompt(options: String, path: String, code: String, problems: String, hints: String, focusLine: Int, rules: String?): String {
        val o = opts(options, path)
        return Json.write(mapOf(
            "system" to HintPrompts.giveUpSystem(o),
            "user" to HintPrompts.giveUpUser(code, path, problemsOf(problems), hintsOf(hints), focusLine.takeIf { it > 0 }, rulesOf(rules)),
        ))
    }

    /** Pedir otra pista sin haber intentado nada: true = primero se pide un intento. */
    fun shouldNudge(textAtLastHint: String?, currentText: String, lastHintAt: Double, now: Double): Boolean =
        HelpPolicy.shouldNudge(textAtLastHint, currentText, lastHintAt.toLong(), now.toLong())

    // ---------- contexto: conversación con otra IA y reglas del proyecto ----------

    fun projectDirName(basePath: String): String = ClaudeSessions.projectDirName(basePath)

    /** Conversación de Antigravity (líneas de su transcript.jsonl) como JSON [{role, text, at}]. */
    fun parseAntigravity(lines: Array<String>): String = turnsJson(AntigravityTranscripts.parse(lines.asSequence()))
    fun antigravityBelongsTo(text: String, basePath: String): Boolean = AntigravityTranscripts.belongsTo(text, basePath)
    fun antigravityTranscriptFile(): String = AntigravityTranscripts.FILE

    /** Conversación de GitHub Copilot (su JSON de chatSessions) como JSON [{role, text, at}]. */
    fun parseCopilot(json: String): String = turnsJson(CopilotSessions.parse(json))
    fun copilotFolder(workspaceJson: String): String? = CopilotSessions.folderOf(workspaceJson)

    /** {id, label, brand} de la IA de donde sale una conversación o plan, según su ruta. */
    fun sourceOf(path: String): String = SourceKind.ofPath(path).let { Json.write(mapOf("id" to it.id, "label" to it.label, "brand" to it.brand.id)) }
    fun pastedPath(): String = SourceKind.PASTED_PATH

    // ---------- retomar un proyecto ----------

    /** Las formas en que puede estar escrita una ruta con tildes (para buscar la carpeta de Claude Code). */
    fun pathVariants(path: String): Array<String> = nfcAndNfd(path).toTypedArray()
    fun nfcOf(text: String): String = nfc(text)
    fun recentMs(): Double = Resume.RECENT_MS.toDouble()
    fun ago(thenMs: Double, nowMs: Double): String = Resume.ago(thenMs.toLong(), nowMs.toLong())

    /** Mensaje para pegar en un chat nuevo. [steps]: [{label, done: true|false|null}]. */
    fun resumeHandoff(project: String, source: String, ago: String, title: String?, steps: String, excerpt: String?): String {
        val list = Json.parse(steps).asList().map { it.asMap() }.map { it.str("label").orEmpty() to (it["done"] as? Boolean) }
        return Resume.handoff(project, source, ago, title, list, excerpt)
    }

    private fun turnsJson(turns: List<SessionTurn>) = Json.write(turns.map { mapOf("role" to it.role, "text" to it.text, "at" to it.at) })

    /** Mensajes de una sesión de Claude Code (líneas del .jsonl) como JSON [{role, text, at}]. */
    fun parseSession(lines: Array<String>): String =
        Json.write(ClaudeSessions.parse(lines.asSequence()).map { mapOf("role" to it.role, "text" to it.text, "at" to it.at) })

    /** Extracto para el Mentor (el plan completo primero) a partir de [turns] = salida de [parseSession]. */
    fun sessionExcerpt(turns: String, fileName: String?): String? = ClaudeSessions.excerpt(turnsOf(turns), fileName)

    fun lastMention(turns: String, fileName: String): String? = ClaudeSessions.lastMention(turnsOf(turns), fileName)
    fun lastRequest(turns: String): String? = ClaudeSessions.lastRequest(turnsOf(turns))
    fun hasSteps(markdown: String): Boolean = StepParser.hasSteps(markdown)

    /** Secciones de arquitectura y convenciones de un AGENTS.md / CLAUDE.md; null si está vacío. */
    fun extractRules(markdown: String): String? = ProjectRules.extract(markdown)

    /** El comentario habla de un código que ya cambió: se resuelve solo. */
    fun isOutdated(message: String, original: Array<String>, current: Array<String>): Boolean =
        StaleComments.outdated(message, original.toList(), current.toList())

    // ---------- guía de pasos (planes de Claude Code y de Antigravity) ----------

    fun isPlanFile(name: String): Boolean = AntigravityPlans.isPlanFile(name)
    fun isPlan(markdown: String): Boolean = AntigravityPlans.isPlan(markdown)
    fun planTitle(markdown: String): String? = AntigravityPlans.title(markdown)
    fun planMentions(markdown: String, fileName: String): Boolean = AntigravityPlans.mentions(markdown, fileName)
    fun planExcerpt(markdown: String): String = AntigravityPlans.excerpt(markdown)
    fun brainDirs(): Array<String> = AntigravityPlans.BRAIN_DIRS.toTypedArray()

    /** Los pasos de un plan (de Claude o de Antigravity) como JSON, con sus cambios y TODO. */
    fun steps(markdown: String, basePath: String?): String = Json.write(parse(markdown, basePath).map { s ->
        mapOf(
            "id" to s.id, "number" to s.number, "title" to s.title, "label" to s.label, "verify" to s.verify,
            "edits" to s.edits.map { e ->
                mapOf("path" to e.path, "fileName" to e.fileName, "line" to e.line, "isSkeleton" to e.isSkeleton, "isDeletion" to e.isDeletion,
                    "key" to e.key, "todos" to e.todos.map { (l, t) -> mapOf("label" to l, "text" to t) })
            },
        )
    })

    /** Cómo va un cambio del paso en el código actual: cada TODO con su estado y línea, si está completo y dónde ir. */
    fun editStatus(markdown: String, basePath: String?, step: Int, edit: Int, fileText: String, hasErrors: Boolean, seenPlaced: Boolean): String {
        val s = StepMatcher.status(fileText.lines(), parse(markdown, basePath)[step].edits[edit], hasErrors, seenPlaced)
        return Json.write(mapOf(
            "todos" to s.todos.map { mapOf("label" to it.label, "text" to it.text, "state" to it.state.name, "line" to it.line, "missing" to it.missing) },
            "skeletonPlaced" to s.skeletonPlaced, "canPlaceSkeleton" to s.canPlaceSkeleton, "complete" to s.complete,
            "cursorLine" to s.cursorLine, "found" to s.found,
        ))
    }

    /** Reemplazo que pone el esqueleto (solo los TODO): {from, to, lines} (líneas 0-based, inclusivas), o null. */
    fun placeSkeleton(markdown: String, basePath: String?, step: Int, edit: Int, fileText: String): String? =
        StepMatcher.placeSkeleton(fileText.lines(), parse(markdown, basePath)[step].edits[edit])?.let {
            Json.write(mapOf("from" to it.from, "to" to it.to, "lines" to it.lines))
        }

    /** {system, user} para «¿Cumple el paso?». [files]: [[nombre, código actual]] de los archivos del paso. */
    fun verifyPrompt(options: String, markdown: String, basePath: String?, step: Int, files: String, rules: String?, session: String?): String {
        val st = parse(markdown, basePath)[step]
        val f = Json.parse(files).asList().map { it.asList() }.map { (it.getOrNull(0) as? String).orEmpty() to (it.getOrNull(1) as? String).orEmpty() }
        val o = opts(options, st.edits.firstOrNull()?.path)
        return Json.write(mapOf("system" to StepPrompts.verifySystem(o), "user" to StepPrompts.verifyUser(st, f, rulesOf(rules), session, basePath)))
    }

    /** {ok, gaps:[{file, line, text}]}. Rescata lo que llegó completo si la respuesta vino cortada; lanza si no hay nada. */
    fun parseVerdict(text: String): String {
        val v = StepPrompts.parseVerdict(text)
        return Json.write(mapOf("ok" to v.ok, "gaps" to v.gaps.map { mapOf("file" to it.file, "line" to it.line, "text" to it.text) }))
    }

    private fun parse(markdown: String, basePath: String?) = StepParser.parse(AntigravityPlans.toSteps(markdown), basePath)

    // ---------- íconos de cada IA ----------

    /** {id, color, glyph} del ícono del proveedor (el SVG está en media/brands/<id>.svg). */
    fun brandOfProvider(providerId: String): String = Brand.ofProvider(providerId).let { Json.write(mapOf("id" to it.id, "color" to it.color, "glyph" to it.glyph)) }

    /** El ícono de la fuente del plan (conversación de Claude Code o plan de Antigravity) según su ruta. */
    fun brandOfPlanFile(path: String): String = Brand.ofPlanFile(path).id

    // ---------- consumo ----------

    fun formatTokens(n: Double): String = UsageMath.tokens(n.toLong())
    fun formatUsd(v: Double): String = UsageMath.usd(v)
    fun languageOf(path: String?): String = CodeLanguage.of(path).label

    // ---------- conversiones ----------

    private fun cfg(json: String): AiConfig {
        val m = Json.parse(json).asMap()
        val p = ProviderPreset.of(m.str("provider"))
        return AiConfig(
            preset = p,
            baseUrl = m.str("baseUrl")?.trim().takeUnless { it.isNullOrEmpty() } ?: p.baseUrl,
            model = m.str("model")?.trim().takeUnless { it.isNullOrEmpty() } ?: p.defaultModel,
            apiKey = m.str("apiKey")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun msgs(json: String) = Json.parse(json).asList().map { it.asMap() }.map { ChatMessage(it.str("role") ?: "user", it.str("content").orEmpty()) }

    private fun opts(json: String, path: String?): PromptOptions {
        val m = Json.parse(json).asMap()
        return PromptOptions(Level.of(m.str("level")), m.str("language") ?: "es", (m.int("maxComments") ?: 5).coerceIn(1, 10)).forFile(path)
    }

    private fun rulesOf(json: String?): ProjectRules.Found? = json?.let {
        val m = Json.parse(it).asMap()
        val text = m.str("text") ?: return null
        ProjectRules.Found(m.str("source") ?: "AGENTS.md", text)
    }

    private fun problemsOf(json: String) = Json.parse(json).asList().map { it.asMap() }.map { IdeProblem(it.int("line") ?: 1, it.str("message").orEmpty()) }

    private fun hintsOf(json: String) = Json.parse(json).asList().map { it.asMap() }.map {
        Hint(HintLevel.valueOf(it.str("level") ?: HintLevel.QUESTION.name), it.int("line") ?: 1, it.str("problem").orEmpty(), it.str("text").orEmpty(), it.str("concept"))
    }

    private fun turnsOf(json: String) = Json.parse(json).asList().map { it.asMap() }.map { SessionTurn(it.str("role").orEmpty(), it.str("text").orEmpty(), it.str("at").orEmpty()) }
}

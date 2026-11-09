package dev.kmpcoach.ide.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import dev.kmpcoach.core.Analysis
import dev.kmpcoach.core.Analyzer
import dev.kmpcoach.core.Challenge
import dev.kmpcoach.core.CommentType
import dev.kmpcoach.core.HintLevel
import dev.kmpcoach.core.Level
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import dev.kmpcoach.core.AiClients
import dev.kmpcoach.core.Prompts
import dev.kmpcoach.core.ProviderPreset
import dev.kmpcoach.core.Severity
import dev.kmpcoach.core.UsageKind
import dev.kmpcoach.core.UsageMath
import dev.kmpcoach.core.UsageStats
import com.intellij.ide.BrowserUtil
import com.intellij.ide.scratch.ScratchUtil
import dev.kmpcoach.ide.UsageTracker
import dev.kmpcoach.ide.ChallengeFiles
import dev.kmpcoach.ide.ChallengeRepository
import dev.kmpcoach.ide.CoachConfigurable
import dev.kmpcoach.ide.CoachSettings
import dev.kmpcoach.ide.TutorService
import java.awt.BorderLayout
import java.awt.Color
import java.net.URLDecoder
import java.net.URLEncoder
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.text.DefaultCaret

class CoachToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CoachPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** Panel lateral: elegir reto, objetivos en vivo, análisis local, revisión del tutor y chat. */
class CoachPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val repo = ChallengeRepository.getInstance(project)
    private val tutor = TutorService.getInstance(project)
    private val settings = CoachSettings.getInstance()
    private val usage = UsageTracker.getInstance()

    private val levelBox = ComboBox(DefaultComboBoxModel(Level.entries.toTypedArray()))
    private val challengeBox = ComboBox<Challenge>()
    private val pane = JEditorPane()
    private val scroll = JBScrollPane(pane)
    private val chatField = JBTextField()
    private val liveCheck = JBCheckBox("Tutor en vivo")
    private val reviewButton = JButton("Revisar mi código")
    private val hintButton = JButton("Dame una pista")
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var syncing = false

    init {
        pane.contentType = "text/html"
        pane.editorKit = HTMLEditorKitBuilder.simple()
        pane.isEditable = false
        pane.font = JBFont.label()
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        pane.border = JBUI.Borders.empty(8, 10)
        (pane.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        pane.addHyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) onLink(e.description) }

        val level = Level.of(settings.state.studentLevel)
        levelBox.selectedItem = level
        fillChallenges(level)
        levelBox.addActionListener { if (!syncing) (levelBox.selectedItem as? Level)?.let(::fillChallenges) }

        chatField.emptyText.text = "Pregúntale al tutor sobre tu código"
        chatField.addActionListener { send() }
        reviewButton.addActionListener { reviewNow() }
        hintButton.addActionListener { currentFile()?.takeIf { TutorService.isKotlin(it) }?.let(tutor::requestHint) }
        liveCheck.toolTipText = "Con errores, te da una pista si no avanzas; sin errores, revisa tu enfoque cuando haces una pausa. Usa tu clave de API."
        liveCheck.isSelected = settings.state.liveMode
        liveCheck.addActionListener { settings.state.liveMode = liveCheck.isSelected; scheduleRefresh() }

        val top = panel {
            row {
                cell(levelBox)
                cell(challengeBox).align(AlignX.FILL).resizableColumn()
                button("Abrir") { (challengeBox.selectedItem as? Challenge)?.let { ChallengeFiles.open(project, it) } }
            }
        }
        val bottom = panel {
            row {
                cell(hintButton)
                cell(reviewButton)
            }
            row {
                cell(liveCheck)
                link("Ajustes") { openSettings() }
            }
            row {
                cell(chatField).align(AlignX.FILL).resizableColumn()
                button("Preguntar") { send() }
            }
        }
        top.border = JBUI.Borders.empty(4, 6, 0, 6)
        bottom.border = JBUI.Borders.empty(0, 6, 6, 6)
        setContent(JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        })

        tutor.addListener(this, object : TutorService.Listener {
            override fun changed() = scheduleRefresh()
            override fun prefill(text: String) {
                chatField.text = text
                chatField.requestFocusInWindow()
                chatField.caretPosition = text.length
            }
        })
        usage.addListener(this) { scheduleRefresh() }
        if (settings.preset == ProviderPreset.OPENROUTER) tutor.refreshBalance()
        val bus = project.messageBus.connect(this)
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = scheduleRefresh()
        })
        // Los errores del IDE cambian cuando termina el resaltado, no cuando escribes: así el panel los muestra al día.
        bus.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonFinished() = scheduleRefresh()
        })
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (FileDocumentManager.getInstance().getFile(event.document) == currentFile()) scheduleRefresh()
            }
        }, this)
        refresh()
    }

    private fun currentFile(): VirtualFile? = FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile

    private fun fillChallenges(level: Level) {
        challengeBox.model = DefaultComboBoxModel(repo.byLevel(level).toTypedArray())
    }

    private fun syncSelection(challenge: Challenge?) {
        if (challenge == null || (challengeBox.selectedItem as? Challenge)?.id == challenge.id) return
        syncing = true
        try {
            levelBox.selectedItem = challenge.level
            fillChallenges(challenge.level)
            val model = challengeBox.model
            for (i in 0 until model.size) if (model.getElementAt(i).id == challenge.id) { challengeBox.selectedIndex = i; break }
        } finally {
            syncing = false
        }
    }

    private fun scheduleRefresh() {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, 200)
    }

    private fun openSettings() = ShowSettingsUtil.getInstance().showSettingsDialog(project, CoachConfigurable::class.java)

    private fun reviewNow() {
        val f = currentFile()?.takeIf { TutorService.isKotlin(it) } ?: return
        tutor.requestReview(f)
    }

    private fun send() {
        val q = chatField.text.trim()
        if (q.isEmpty()) return
        chatField.text = ""
        tutor.ask(currentFile()?.takeIf { TutorService.isKotlin(it) }, q)
    }

    // ---------- HTML ----------
    private fun hex(c: Color) = "#" + ColorUtil.toHex(c)
    private val ok get() = hex(JBColor(Color(0x1C7A49), Color(0x5DBE8A)))
    private val warn get() = hex(JBColor(Color(0x9A6408), Color(0xE3A443)))
    private val bad get() = hex(JBColor(Color(0xB0382A), Color(0xEC7C69)))
    private val info get() = hex(JBColor(Color(0x2F6AAE), Color(0x8DB8F2)))
    private val muted get() = hex(JBColor.GRAY)

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)

    private fun md(s: String): String = s.split("```").mapIndexed { i, part ->
        if (i % 2 == 1) "<pre>" + esc(part.substringAfter('\n', part).trimEnd()) + "</pre>"
        else esc(part).replace(Regex("`([^`]+)`"), "<code>$1</code>").replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>").replace("\n", "<br>")
    }.joinToString("")

    private fun sevColor(s: Severity) = when (s) { Severity.ERROR -> bad; Severity.WARN -> warn; Severity.INFO -> info }
    private fun typeColor(t: CommentType) = when (t) { CommentType.ERROR -> bad; CommentType.MEJORA -> warn; CommentType.PREGUNTA -> info; CommentType.BIEN -> ok }

    private fun refresh() {
        if (project.isDisposed) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val file = editor?.virtualFile
        val isKotlin = file != null && TutorService.isKotlin(file)
        val text = if (isKotlin) editor!!.document.text else ""
        val challenge = if (isKotlin) repo.challengeFor(text) else null
        syncSelection(challenge)
        val busy = tutor.isBusy(file)
        reviewButton.isEnabled = isKotlin && !busy
        reviewButton.text = when {
            busy -> "Revisando ${shortName(file!!)}…"
            isKotlin -> "Revisar ${shortName(file!!)}"
            else -> "Revisar mi código"
        }
        reviewButton.toolTipText = if (isKotlin) "Envía ${file!!.name} al tutor de IA (Ctrl+Alt+Mayús+K / ⌘⌥⇧K)" else "Abre un archivo .kt para revisarlo"
        liveCheck.isSelected = settings.state.liveMode
        val next = if (isKotlin) tutor.nextHintLevel(file) else null
        val hintBusy = tutor.isHintBusy(file)
        hintButton.isEnabled = isKotlin && !hintBusy && next != null
        hintButton.text = when {
            hintBusy -> "Pensando la pista…"
            !isKotlin || next == HintLevel.QUESTION -> "Dame una pista"
            next == null -> "Sin más pistas"
            else -> "Otra pista (${next.number}/${HintLevel.entries.size})"
        }
        hintButton.toolTipText = "Te guía sin darte la solución: pregunta → concepto → herramienta → pasos (Ctrl+Alt+Mayús+J / ⌘⌥⇧J)"

        val analysis = if (isKotlin) Analyzer.analyze(text, challenge, lexical = false) else null
        val h = StringBuilder("<html><body>")
        fileHeader(h, file, isKotlin)
        if (challenge != null && analysis != null) challengeSection(h, challenge, text, analysis) else intro(h, isKotlin)
        if (isKotlin && analysis != null) {
            hintSection(h, file!!)
            localSection(h, analysis)
            tutorSection(h, file, text, challenge, analysis)
        }
        if (settings.state.liveMode) liveSection(h, if (isKotlin) file else null)
        chatSection(h, if (isKotlin) file else null)
        usageSection(h)
        h.append("<p style='color:$muted'>${esc(settings.modelLabel())} · <a href='settings'>cambiar</a> · <a href='reload'>recargar retos</a></p>")
        h.append("</body></html>")

        val pos = scroll.verticalScrollBar.value
        pane.text = h.toString()
        SwingUtilities.invokeLater { scroll.verticalScrollBar.value = pos }
    }

    private fun intro(h: StringBuilder, isKotlin: Boolean) {
        h.append("<p><b>KMP Interview Coach</b></p>")
        if (!isKotlin) {
            h.append("<p>Abre cualquier archivo Kotlin de tu proyecto, o elige un reto arriba y pulsa <b>Abrir</b>. ")
            h.append("Si te atascas, pide una <b>pista</b>: el tutor te guía con preguntas sin darte el código. ")
            h.append("Selecciona código y usa <b>KMP Coach › Explícame esto</b> para comprobar que lo entiendes.</p>")
        } else {
            h.append("<p style='color:$muted'>Este archivo no es un reto: las pistas, el análisis local y el tutor trabajan sobre tu propio código.</p>")
        }
        if (settings.state.model.isNullOrBlank() && settings.preset.defaultModel.isEmpty()) {
            h.append("<p style='color:$warn'>Falta elegir el modelo de IA. <a href='settings'>Configurar proveedor</a></p>")
        }
    }

    private fun challengeSection(h: StringBuilder, c: Challenge, text: String, a: Analysis) {
        h.append("<p><b>${esc(c.title)}</b><br><span style='color:$muted'>${c.level.label} · ${esc(c.topic)}${if (c.minutes > 0) " · ${c.minutes} min" else ""} · ${esc(c.packName)}</span></p>")
        h.append("<p>${md(c.brief)}</p>")
        val met = a.objectivesMet
        val total = a.objectives.size
        h.append("<p><b>Objetivos</b> <span style='color:${if (met == total) ok else muted}'>$met/$total</span><br>")
        a.objectives.forEach { o ->
            h.append(if (o.pass) "<span style='color:$ok'>✓</span> " else "<span style='color:$muted'>○</span> ")
            h.append(if (o.pass) md(o.label) else "<span style='color:$muted'>${md(o.label)}</span>").append("<br>")
        }
        h.append("</p>")
        val changed = text.substringAfter('\n').trim() != c.starter.trim()
        if (met == total && changed) h.append("<p style='color:$ok'>Cumples todos los objetivos. Pide una revisión al tutor o compara con la solución.</p>")
        h.append("<p><a href='solution'>Ver solución de referencia</a> · <a href='restart'>Empezar de nuevo</a></p>")
    }

    // ---------- pistas ----------
    private fun hintSection(h: StringBuilder, file: VirtualFile) {
        val s = tutor.hintSession(file)
        val hints = s?.hints?.toList().orEmpty()
        val next = tutor.nextHintLevel(file)
        h.append("<p><b>Pistas</b> <span style='color:$muted'>· te guían sin darte la solución</span><br>")

        val problems = tutor.ideProblems(file)
        if (problems.isNotEmpty()) {
            h.append("<span style='color:$muted'>El IDE marca ${problems.size} ${if (problems.size == 1) "error" else "errores"}:</span><br>")
            problems.take(3).forEach { p ->
                h.append("<a href='line:${p.line - 1}'>L${p.line}</a> <span style='color:$bad'>${esc(p.message)}</span><br>")
            }
        }

        // Escalera: los escalones que ya usaste van en negrita.
        h.append("<span style='color:$muted'>")
        HintLevel.entries.forEachIndexed { i, l ->
            if (i > 0) h.append(" → ")
            h.append(if (hints.any { it.level == l }) "<b>${l.number}. ${l.label}</b>" else "${l.number}. ${l.label}")
        }
        h.append("</span><br>")

        hints.forEach { hint ->
            h.append("<a href='line:${hint.line - 1}'>L${hint.line}</a> <span style='color:$info'>${hint.level.number}. ${hint.level.label}</span> ${md(hint.text)}")
            if (hint.redacted) h.append(" <span style='color:$muted'>(oculté un fragmento con código de tu ejercicio)</span>")
            h.append("<br>")
        }
        if (hints.isNotEmpty() && s?.lastAuto == true) h.append("<span style='color:$muted'>El tutor en vivo te dio esta pista porque llevabas un rato sin avanzar.</span><br>")

        when {
            tutor.isHintBusy(file) -> h.append("<span style='color:$muted'>Pensando la pista ${next?.number ?: ""}…</span><br>")
            s?.error != null -> h.append("<span style='color:$bad'>${esc(s.error!!)}</span> <a href='settings'>Ajustes</a><br>")
            hints.isEmpty() -> h.append("<span style='color:$muted'>¿Atascado? Pide una pista: primero una pregunta, luego el concepto, la herramienta con un ejemplo de otros datos y los pasos.</span><br>")
            next == null -> h.append("<span style='color:$muted'>Ya viste las ${HintLevel.entries.size} pistas. Inténtalo; si no sale, ríndete sin culpa.</span><br>")
        }

        val links = buildList {
            if (!tutor.isHintBusy(file) && next != null) add("<a href='hint'>${if (hints.isEmpty()) "Dame una pista" else "Otra pista (${esc(next.label)})"}</a>")
            if (!tutor.isGiveUpBusy(file)) add("<a href='giveup'>Me rindo</a>")
            if (hints.isNotEmpty()) add("<a href='newproblem'>Nuevo problema</a>")
            if (hints.isNotEmpty() && s?.showInEditor == true) add("<a href='hidehint'>Quitar del editor</a>")
        }
        h.append(links.joinToString(" · ")).append("</p>")

        if (tutor.isGiveUpBusy(file)) h.append("<p style='color:$muted'>Preparando la solución explicada…</p>")
        s?.giveUp?.let { h.append("<p><b>Solución explicada</b><br>${md(it)}</p><p><a href='handoff'>Copiar para preguntarle a Claude</a></p>") }
    }

    /** «Me rindo»: antes de mostrar la solución, recuerda cuántas pistas quedan y deja elegir cómo verla. */
    private fun giveUpDialog(file: VirtualFile) {
        val used = tutor.hintSession(file)?.hints?.size ?: 0
        val next = tutor.nextHintLevel(file)
        val total = HintLevel.entries.size
        val message = buildString {
            if (next != null) append("Llevas $used de $total pistas. Intentar con una más suele ser lo que más enseña.\n\n")
            append("¿Cómo quieres ver la solución?\n")
            append("• Explicada aquí: el tutor te muestra el código y qué hace cada línea.\n")
            append("• Copiar para Claude: copia el archivo, los errores y las pistas para pegarlos en tu chat.")
        }
        val options = if (next != null) arrayOf("Explicada aquí", "Copiar para Claude", "Otra pista") else arrayOf("Explicada aquí", "Copiar para Claude", "Cancelar")
        when (Messages.showDialog(project, message, "Me rindo", options, 0, Messages.getQuestionIcon())) {
            0 -> tutor.giveUp(file)
            1 -> tutor.copyHandoff(file)
            2 -> if (next != null) tutor.requestHint(file)
        }
    }

    private fun localSection(h: StringBuilder, a: Analysis) {
        val e = a.count(Severity.ERROR)
        val w = a.count(Severity.WARN)
        val s = a.count(Severity.INFO)
        val ms = String.format("%.2f", a.micros / 1000.0)
        h.append("<p><b>Análisis local</b> <span style='color:$muted'>· $ms ms</span><br>")
        if (a.diagnostics.isEmpty()) {
            h.append("<span style='color:$muted'>Sin avisos. Revisa patrones y reglas del reto; los errores de compilación los marca el IDE.</span>")
        } else {
            h.append("<span style='color:$bad'>$e ${if (e == 1) "error" else "errores"}</span> · <span style='color:$warn'>$w ${if (w == 1) "aviso" else "avisos"}</span> · <span style='color:$info'>$s ${if (s == 1) "sugerencia" else "sugerencias"}</span><br>")
            a.diagnostics.sortedByDescending { it.severity.rank }.take(8).forEach { d ->
                h.append("<a href='line:${d.line}'>L${d.line + 1}</a> <span style='color:${sevColor(d.severity)}'>${d.severity.label}</span> ${md(d.message)}<br>")
            }
        }
        h.append("</p>")
    }

    private fun tutorSection(h: StringBuilder, file: VirtualFile, text: String, challenge: Challenge?, analysis: Analysis) {
        h.append("<p><b>Tutor de IA</b> <span style='color:$muted'>· revisa <b>${esc(file.name)}</b></span><br>")
        val others = tutor.filesUnderReview().filter { it != file.name }
        if (others.isNotEmpty()) h.append("<span style='color:$muted'>También revisando: ${esc(others.joinToString())}</span><br>")
        val fr = tutor.reviewFor(file)
        when {
            tutor.isBusy(file) -> h.append("<span style='color:$muted'>Revisando <b>${esc(file.name)}</b> con ${esc(settings.modelLabel())}…</span> <a href='cancel'>Detener</a><br>")
            tutor.errorFor(file) != null -> h.append("<span style='color:$bad'>${esc(tutor.errorFor(file)!!)}</span> <a href='settings'>Ajustes</a><br>")
            settings.state.liveMode && tutor.isWaitingForSyntax(file) -> h.append("<span style='color:$muted'>En vivo: esperando a que el archivo no tenga errores de sintaxis.</span><br>")
        }
        if (fr == null) {
            if (!tutor.isBusy(file)) {
                h.append("<span style='color:$muted'>Pulsa «Revisar ${esc(shortName(file))}» (⌘⌥⇧K) y el tutor comentará debajo de cada línea.</span><br>")
                h.append("<span style='color:$muted'>${reviewEstimate(file, text, challenge, analysis)}</span>")
            }
            h.append("</p>")
            return
        }
        val r = fr.review
        val stColor = when (r.status) { "listo" -> ok; "desviado" -> bad; "a_medias" -> warn; else -> info }
        h.append("<span style='color:$stColor'><b>${esc(r.statusLabel)}</b></span>")
        h.append(" <span style='color:$muted'>· ${if (fr.auto) "en vivo" else "manual"} · ${ago(fr.at)}</span><br>")
        fr.call?.let { h.append("<span style='color:$muted'>${callCost(it.usage.inputTokens, it.usage.outputTokens, it.costUsd, it.estimated)} · ${esc(fr.modelLabel)}</span><br>") }
        if (r.summary.isNotBlank()) h.append(md(r.summary)).append("<br>")
        h.append("</p><p>")
        fr.comments.forEach { c ->
            val line = if (c.marker.isValid) FileDocumentManager.getInstance().getDocument(file)?.getLineNumber(c.marker.startOffset) ?: 0 else 0
            h.append("<a href='line:$line'>L${line + 1}</a> <span style='color:${typeColor(c.type)}'>${c.type.label}</span> ${md(c.message)}<br>")
        }
        h.append("</p>")
        if (r.nextStep.isNotBlank()) h.append("<p><b>Siguiente paso:</b> ${md(r.nextStep)}</p>")
        h.append("<p style='color:$muted'>")
        if (fr.stale) h.append("Cambiaste el código desde esta revisión. ${reviewEstimate(file, text, challenge, analysis)}<br>")
        h.append("<a href='clear'>Quitar comentarios del editor</a></p>")
    }

    // ---------- archivo actual ----------
    private fun shortName(file: VirtualFile) = file.name.let { if (it.length > 28) it.take(25) + "…" else it }

    private fun fileHeader(h: StringBuilder, file: VirtualFile?, isKotlin: Boolean) {
        if (file == null) {
            h.append("<p style='color:$muted'>Ningún archivo abierto en el editor.</p>")
            return
        }
        val where = when {
            ScratchUtil.isScratch(file) -> "archivo de borrador del IDE"
            else -> project.basePath?.let { base -> file.parent?.path?.removePrefix(base)?.trimStart('/') }?.ifBlank { "raíz del proyecto" } ?: file.parent?.path.orEmpty()
        }
        val color = if (isKotlin) info else muted
        h.append("<p><span style='color:$color'>▶</span> Archivo: <b>${esc(file.name)}</b><br><span style='color:$muted'>${esc(where)}")
        if (!isKotlin) h.append(" · el tutor solo revisa archivos <code>.kt</code>")
        h.append("</span></p>")
    }

    /** Cuánto costaría revisar el archivo ahora, calculado con el mismo texto que se enviaría. */
    private fun reviewEstimate(file: VirtualFile, text: String, challenge: Challenge?, analysis: Analysis): String {
        val o = settings.promptOptions()
        val input = UsageMath.estimateTokens(Prompts.reviewSystem(o) + Prompts.reviewUser(challenge, text, analysis, file.name))
        val out = usage.session(UsageKind.REVIEW).avgOutput.takeIf { it > 0 }?.toInt() ?: UsageMath.TYPICAL_REVIEW_OUTPUT
        return "Cada revisión de este archivo envía ≈ ${UsageMath.tokens(input.toLong())} tokens y recibe ≈ ${UsageMath.tokens(out.toLong())} · " + costOf(input, out)
    }

    private fun costOf(input: Int, output: Int): String = when {
        settings.preset == ProviderPreset.OLLAMA -> "gratis (local)"
        else -> settings.price()?.let { "≈ " + UsageMath.usd(it.cost(input, output)) } ?: "<a href='settings'>configura el precio</a> para ver el costo"
    }

    private fun callCost(input: Int, output: Int, cost: Double?, estimated: Boolean): String {
        val t = "${UsageMath.tokens(input.toLong())} entrada + ${UsageMath.tokens(output.toLong())} salida"
        val c = when {
            cost != null -> UsageMath.usd(cost)
            settings.preset == ProviderPreset.OLLAMA -> "gratis"
            else -> "costo sin precio configurado"
        }
        return (if (estimated) "≈ " else "") + "$t · $c"
    }

    private fun ago(at: Long): String {
        val s = (System.currentTimeMillis() - at) / 1000
        return when {
            s < 10 -> "ahora"
            s < 60 -> "hace $s s"
            s < 3600 -> "hace ${s / 60} min"
            else -> "hace ${s / 3600} h"
        }
    }

    // ---------- tutor en vivo ----------
    private fun liveSection(h: StringBuilder, file: VirtualFile?) {
        val s = settings.state
        h.append("<p><b>Tutor en vivo</b> <span style='color:$ok'>activo</span><br>")
        h.append("<span style='color:$muted'>Vigila el archivo <code>.kt</code> que estás editando")
        if (file != null) h.append(" (ahora <b>${esc(file.name)}</b>)")
        h.append(". Actúa cuando dejas de escribir ${String.format("%.1f", s.liveDelayMs / 1000.0)} s:<br>")
        h.append("· con errores o un <code>TODO()</code>, si llevas ${s.stuckAfterSec} s sin reducirlos, te da una pista;<br>")
        h.append("· sin errores, revisa tu enfoque, como máximo una vez cada ${s.minIntervalSec} s y solo si el código cambió.<br>")
        h.append("Cada pista cuesta poco (≈ una revisión corta); cada revisión en vivo cuesta lo mismo que una manual.</span><br>")
        when {
            file == null -> {}
            tutor.isBusy(file) -> h.append("<span style='color:$info'>Revisando ahora…</span>")
            tutor.isHintBusy(file) -> h.append("<span style='color:$info'>Preparando una pista…</span>")
            tutor.isWaitingStuck(file) -> h.append("<span style='color:$info'>Hay errores: si en ${s.stuckAfterSec} s no avanzas, te doy una pista.</span>")
            tutor.isLiveScheduled(file) -> h.append("<span style='color:$info'>Programado: esperando a que hagas la pausa o pase el intervalo mínimo.</span>")
            tutor.isWaitingForSyntax(file) -> h.append("<span style='color:$warn'>Hay errores de sintaxis: pide una pista si no sabes cómo seguir.</span>")
            else -> h.append("<span style='color:$muted'>Sin cambios pendientes.</span>")
        }
        val live = usage.session(UsageKind.LIVE)
        if (live.calls > 0) h.append("<br><span style='color:$muted'>Esta sesión: ${live.calls} ${if (live.calls == 1) "revisión" else "revisiones"} en vivo · ${statsCost(live)}</span>")
        h.append("</p>")
    }

    // ---------- consumo ----------
    private fun statsCost(st: UsageStats): String {
        val t = "${UsageMath.tokens(st.inputTokens + st.outputTokens)} tokens"
        return when {
            settings.preset == ProviderPreset.OLLAMA -> "$t · gratis"
            st.pricedCalls == 0 -> t
            st.pricedCalls < st.calls -> "$t · ≥ ${UsageMath.usd(st.costUsd)}"
            else -> "$t · ${UsageMath.usd(st.costUsd)}"
        }
    }

    private fun usageSection(h: StringBuilder) {
        val p = settings.preset
        h.append("<p><b>Consumo de la API</b> <span style='color:$muted'>· ${esc(p.label)}</span><br>")

        // Saldo
        when {
            p == ProviderPreset.OLLAMA -> h.append("<span style='color:$muted'>Ollama corre en tu computador: no tiene saldo ni costo.</span><br>")
            p == ProviderPreset.OPENROUTER -> {
                val b = usage.balance.takeIf { usage.hasBalanceFor(p.id) }
                val err = usage.balanceError.takeIf { usage.hasBalanceFor(p.id) }
                when {
                    b?.remainingUsd != null -> h.append("Saldo restante: <b>${UsageMath.usd(b.remainingUsd)}</b>" +
                        (b.limitUsd?.let { " de ${UsageMath.usd(it)}" } ?: "") + " <span style='color:$muted'>· ${ago(usage.balanceAt)}</span> <a href='balance'>actualizar</a><br>")
                    b?.usedUsd != null -> h.append("Gastado con esta clave: <b>${UsageMath.usd(b.usedUsd)}</b> (la clave no tiene límite) <a href='balance'>actualizar</a><br>")
                    err != null -> h.append("<span style='color:$bad'>${esc(err)}</span> <a href='balance'>reintentar</a><br>")
                    else -> h.append("<a href='balance'>Consultar saldo</a><br>")
                }
            }
            else -> {
                h.append("<span style='color:$muted'>${esc(p.label)} no permite consultar el saldo con una clave de API.")
                AiClients.billingUrl(p)?.let { h.append(" <a href='open:$it'>Ver saldo en la consola</a>") }
                h.append("</span><br>")
            }
        }

        // Límites de la última respuesta
        usage.limitsFor(p.id)?.let { l ->
            val parts = buildList {
                if (l.requestsRemaining != null) add("${l.requestsRemaining}${l.requestsLimit?.let { "/$it" } ?: ""} solicitudes")
                if (l.tokensRemaining != null) add("${UsageMath.tokens(l.tokensRemaining)}${l.tokensLimit?.let { "/" + UsageMath.tokens(it) } ?: ""} tokens")
            }
            h.append("<span style='color:$muted'>Cupo disponible en la ventana actual: ${parts.joinToString(" · ")}" +
                (l.tokensReset?.let { " (se renueva: ${esc(it)})" } ?: "") + "</span><br>")
        }

        // Última llamada
        usage.lastCall?.let { c ->
            h.append("Última llamada: ${c.kind.label}${c.fileName?.let { " · ${esc(it)}" } ?: ""} · ${ago(c.at)}<br>")
            h.append("<span style='color:$muted'>${callCost(c.usage.inputTokens, c.usage.outputTokens, c.costUsd, c.estimated)}</span><br>")
        }

        // Por tipo
        val total = usage.sessionTotal()
        if (total.calls == 0) {
            h.append("<span style='color:$muted'>Aún no hay llamadas en esta sesión.</span>")
        } else {
            h.append("<br><b>Esta sesión</b>: ${total.calls} ${if (total.calls == 1) "llamada" else "llamadas"} · ${statsCost(total)}<br>")
            UsageKind.entries.forEach { k ->
                val st = usage.session(k)
                if (st.calls == 0) return@forEach
                val avg = "${UsageMath.tokens(st.avgInput)} + ${UsageMath.tokens(st.avgOutput)} tokens" +
                    (st.avgCost?.let { " ≈ " + UsageMath.usd(it) } ?: "")
                h.append("<span style='color:$muted'>· ${k.label}: ${st.calls} × (${avg} c/u)</span><br>")
            }
        }
        val all = usage.totalAll()
        if (all.calls > 0) {
            h.append("<br><span style='color:$muted'>Acumulado desde ${esc(usage.since)}: ${all.calls} llamadas · ${statsCost(all)} · <a href='resetusage'>reiniciar</a></span>")
        }
        if (settings.price() == null && p != ProviderPreset.OLLAMA && p != ProviderPreset.OPENROUTER) {
            h.append("<br><span style='color:$muted'>Para ver el costo en dólares, <a href='settings'>escribe el precio de tu modelo</a>.</span>")
        }
        h.append("</p>")
    }

    private fun chatSection(h: StringBuilder, file: VirtualFile?) {
        val msgs = tutor.chatFor(file)
        h.append("<p><b>Chat con el tutor</b>")
        if (msgs.isNotEmpty()) h.append(" <span style='color:$muted'>· <a href='clearchat'>borrar</a></span>")
        h.append("</p>")
        if (msgs.isEmpty()) {
            h.append("<p style='color:$muted'>Prueba con: ")
            listOf("¿Por qué no debería usar un for aquí?", "¿Qué me preguntaría un entrevistador sobre este código?", "Dame una pista sin darme la solución").forEachIndexed { i, q ->
                if (i > 0) h.append(" · ")
                h.append("<a href='ask:${URLEncoder.encode(q, Charsets.UTF_8)}'>${esc(q)}</a>")
            }
            h.append("</p>")
        }
        msgs.forEach { m ->
            if (m.role == "user") h.append("<p><b>Tú:</b> ${md(m.content)}</p>")
            else h.append("<p><b style='color:$info'>Tutor:</b> ${md(m.content)}</p>")
        }
        if (tutor.isChatBusy(file)) h.append("<p style='color:$muted'>El tutor está escribiendo…</p>")
    }

    private fun onLink(desc: String) {
        val file = currentFile()
        val challenge = file?.let { f -> FileDocumentManager.getInstance().getDocument(f)?.let { repo.challengeFor(it.text) } }
        when {
            desc.startsWith("line:") -> file?.let { OpenFileDescriptor(project, it, desc.removePrefix("line:").toIntOrNull() ?: 0, 0).navigate(true) }
            desc.startsWith("ask:") -> tutor.ask(file?.takeIf { TutorService.isKotlin(it) }, URLDecoder.decode(desc.removePrefix("ask:"), Charsets.UTF_8))
            desc == "solution" -> challenge?.let { ChallengeFiles.openSolution(project, it) }
            desc == "restart" -> if (file != null && challenge != null) restart(file, challenge)
            desc == "settings" -> openSettings()
            desc == "cancel" -> file?.let(tutor::cancelReview)
            desc == "clear" -> file?.let(tutor::clearReview)
            desc == "hint" -> file?.takeIf { TutorService.isKotlin(it) }?.let(tutor::requestHint)
            desc == "giveup" -> file?.takeIf { TutorService.isKotlin(it) }?.let(::giveUpDialog)
            desc == "handoff" -> file?.let(tutor::copyHandoff)
            desc == "newproblem" -> file?.let(tutor::resetHints)
            desc == "hidehint" -> file?.let(tutor::hideHintInEditor)
            desc.startsWith("open:") -> BrowserUtil.browse(desc.removePrefix("open:"))
            desc == "balance" -> tutor.refreshBalance()
            desc == "resetusage" -> if (Messages.showYesNoDialog(project, "¿Reiniciar el contador de consumo acumulado?", "Consumo de la API", null) == Messages.YES) usage.resetTotals()
            desc == "clearchat" -> tutor.clearChat(file?.takeIf { TutorService.isKotlin(it) })
            desc == "reload" -> {
                val w = repo.reload()
                fillChallenges(levelBox.selectedItem as? Level ?: Level.JUNIOR)
                Messages.showInfoMessage(project,
                    if (w.isEmpty()) "Retos recargados: ${repo.all().size}." else "Retos recargados con advertencias:\n" + w.joinToString("\n"),
                    "KMP Interview Coach")
                scheduleRefresh()
            }
        }
    }

    private fun restart(file: VirtualFile, c: Challenge) {
        val answer = Messages.showYesNoDialog(project, "¿Reemplazar tu código por el código inicial del reto?", "Empezar de nuevo", Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Empezar de nuevo", null, { doc.setText(c.header + "\n" + c.starter.trimEnd() + "\n") })
        tutor.clearReview(file)
    }

    override fun dispose() {}
}

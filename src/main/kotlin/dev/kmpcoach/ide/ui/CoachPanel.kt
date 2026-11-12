package dev.kmpcoach.ide.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.OnOffButton
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.kmpcoach.core.AiClients
import dev.kmpcoach.core.Analysis
import dev.kmpcoach.core.Analyzer
import dev.kmpcoach.core.Challenge
import dev.kmpcoach.core.CommentType
import dev.kmpcoach.core.FileGuess
import dev.kmpcoach.core.HintLevel
import dev.kmpcoach.core.Level
import dev.kmpcoach.core.ProviderPreset
import dev.kmpcoach.core.Severity
import dev.kmpcoach.core.UsageKind
import dev.kmpcoach.core.UsageMath
import dev.kmpcoach.core.UsageStats
import com.intellij.openapi.application.ApplicationManager
import dev.kmpcoach.ide.AiSessionContext
import dev.kmpcoach.ide.ChallengeFiles
import dev.kmpcoach.ide.ChallengeRepository
import dev.kmpcoach.ide.CoachConfigurable
import dev.kmpcoach.ide.CoachSettings
import dev.kmpcoach.ide.LearnerProfile
import dev.kmpcoach.ide.LearningService
import dev.kmpcoach.ide.ProjectContext
import dev.kmpcoach.ide.ProjectRulesReader
import dev.kmpcoach.ide.StepGuideService
import dev.kmpcoach.ide.TutorService
import dev.kmpcoach.ide.UsageTracker
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.ActionEvent
import java.awt.event.HierarchyEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.net.URLDecoder
import java.net.URLEncoder
import javax.swing.AbstractAction
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.text.DefaultCaret
import javax.swing.text.DefaultEditorKit

class CoachToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CoachPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** Caja redondeada donde se escribe, como la de un chat. */
private class RoundedBox : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(1)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            GraphicsUtil.setupAAPainting(g2)
            val arc = JBUI.scale(16)
            g2.color = UIUtil.getTextFieldBackground()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }
}

/**
 * Panel lateral con tres pestañas:
 * - «Conversación»: la conversación del archivo actual (revisiones, pistas y chat, en orden de llegada), el interruptor del
 *   tutor y la caja para escribir.
 * - «Progreso»: plan, retos, comprensión del código nuevo, repaso y análisis local.
 * - «Consumo»: uso y costo de la API.
 */
class CoachPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val repo = ChallengeRepository.getInstance(project)
    private val tutor = TutorService.getInstance(project)
    private val settings = CoachSettings.getInstance()
    private val usage = UsageTracker.getInstance()

    // Pestaña «Tutor»
    private val feed = htmlPane()
    private val feedScroll = scrollOf(feed)
    private val fileLabel = JBLabel()
    private val statusLabel = JBLabel()
    private val tutorSwitch = OnOffButton()
    private val input = JBTextArea(3, 20)
    private val hintLink = ActionLink("Dame una pista") { currentKotlin()?.let(tutor::requestHint) }
    private val reviewLink = ActionLink("Revisar archivo") { reviewNow() }
    private val giveUpLink = ActionLink("Me rindo") { currentKotlin()?.let(::giveUpDialog) }
    private val sendButton = JButton("Enviar")
    private val modelLink = ActionLink("") { chooseModel() }
    @Volatile private var loadingModels = false

    // Pestaña «Progreso»
    private val progress = htmlPane()
    private val progressScroll = scrollOf(progress)
    private val levelBox = ComboBox(DefaultComboBoxModel(Level.entries.toTypedArray()))
    private val challengeBox = ComboBox<Challenge>()

    // Pestaña «Consumo»
    private val usagePane = htmlPane()
    private val usageScroll = scrollOf(usagePane)

    private val tabs = JBTabbedPane()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var syncing = false
    private var showGuessCompare = false

    /** Último HTML de cada vista: si no cambió no se vuelve a pintar, para no perder la selección ni mover el scroll. */
    private val shown = HashMap<JEditorPane, String>()
    private var feedSignature = ""

    init {
        val level = Level.of(settings.state.studentLevel)
        levelBox.selectedItem = level
        fillChallenges(level)
        levelBox.addActionListener { if (!syncing) (levelBox.selectedItem as? Level)?.let(::fillChallenges) }

        tabs.addTab("Conversación", tutorTab())
        tabs.addTab("Progreso", progressTab())
        tabs.addTab("Consumo", usageScroll)
        setContent(tabs)
        // Al cambiar de pestaña, o al abrir el panel, se calcula solo lo que se va a ver.
        tabs.addChangeListener { refresh() }
        addHierarchyListener { e ->
            if ((e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L && isShowing) refresh()
        }

        tutor.addListener(this, object : TutorService.Listener {
            override fun changed() = scheduleRefresh()
            override fun prefill(text: String) {
                tabs.selectedIndex = 0
                input.text = text
                input.requestFocusInWindow()
                input.caretPosition = text.length
            }
        })
        usage.addListener(this) { scheduleRefresh() }
        ProjectContext.getInstance(project).addListener(this) { scheduleRefresh() }
        LearningService.getInstance(project).addListener(this) { scheduleRefresh() }
        LearnerProfile.getInstance().addListener(this) { scheduleRefresh() }
        AiSessionContext.getInstance(project).addListener(this) { ApplicationManager.getApplication().invokeLater { scheduleRefresh() } }
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

    // ---------- construcción ----------
    private fun htmlPane() = JEditorPane().apply {
        contentType = "text/html"
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        font = JBFont.label()
        background = JBColor.PanelBackground
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        border = JBUI.Borders.empty(8, 12)
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        addHyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) onLink(e.description) }
    }

    private fun scrollOf(pane: JEditorPane) = JBScrollPane(pane).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    private fun tutorTab(): JComponent {
        fileLabel.font = JBFont.label().asBold()
        statusLabel.font = JBFont.small()
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        statusLabel.border = JBUI.Borders.emptyTop(4)
        tutorSwitch.toolTipText = "Enciende o apaga el tutor en este archivo"
        tutorSwitch.addActionListener { if (!syncing) currentKotlin()?.let { tutor.setTutorOn(it, tutorSwitch.isSelected) } }

        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 6, 12)
            add(fileLabel, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(JBLabel("Mentor"))
                add(tutorSwitch)
            }, BorderLayout.EAST)
            add(statusLabel, BorderLayout.SOUTH)
        }

        input.lineWrap = true
        input.wrapStyleWord = true
        input.isOpaque = false
        input.font = JBFont.label()
        input.border = JBUI.Borders.empty(8, 10, 4, 10)
        input.emptyText.text = "Pregunta o responde al tutor (Enter envía)"
        // Enter envía, como en un chat; Mayús+Enter hace el salto de línea.
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "kmpcoach.send")
        input.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), DefaultEditorKit.insertBreakAction)
        input.actionMap.put("kmpcoach.send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = send()
        })
        val inputScroll = JBScrollPane(input).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        sendButton.putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        sendButton.addActionListener { send() }
        giveUpLink.toolTipText = "Ver la solución explicada o copiarla para tu chat. Antes te recuerda cuántas pistas quedan."
        val actions = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 10, 6, 6)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(4))).apply {
                isOpaque = false
                add(hintLink)
                add(reviewLink)
                add(giveUpLink)
            }, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }
        val box = RoundedBox().apply {
            add(inputScroll, BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
        }

        modelLink.toolTipText = "Modelo de IA que responde. Pulsa para elegir otro de tu proveedor."
        val footer = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(modelLink)
        }
        val composer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 10, 8, 10)
            add(box, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(feedScroll, BorderLayout.CENTER)
            add(composer, BorderLayout.SOUTH)
        }
    }

    private fun progressTab(): JComponent {
        val top = panel {
            row("Retos de práctica:") {
                cell(levelBox)
                cell(challengeBox).align(AlignX.FILL).resizableColumn()
                button("Abrir") { (challengeBox.selectedItem as? Challenge)?.let { ChallengeFiles.open(project, it) } }
            }
        }
        top.border = JBUI.Borders.empty(6, 10, 0, 10)
        return JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(progressScroll, BorderLayout.CENTER)
        }
    }

    // ---------- estado ----------
    private fun currentFile(): VirtualFile? = FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile
    private fun currentKotlin(): VirtualFile? = currentFile()?.takeIf { TutorService.isKotlin(it) }

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
        if (!isShowing) return
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh() }, 200)
    }

    private fun openSettings() = ShowSettingsUtil.getInstance().showSettingsDialog(project, CoachConfigurable::class.java)

    /**
     * Cambia el modelo sin abrir los Ajustes: pide a tu proveedor la lista de modelos y la muestra para filtrar y elegir.
     * La clave se lee del almacén seguro y la lista llega por red: todo fuera del hilo de la interfaz.
     */
    private fun chooseModel() {
        if (loadingModels) return
        loadingModels = true
        modelLink.text = "Cargando modelos…"
        val app = ApplicationManager.getApplication()
        fun done(models: List<String>, error: String?) = app.invokeLater {
            loadingModels = false
            if (project.isDisposed) return@invokeLater
            modelLink.text = settings.modelLabel() + " ▾"
            showModelPopup(models, error)
        }
        app.executeOnPooledThread {
            val cfg = runCatching { settings.aiConfig() }.getOrNull()
            val invalid = cfg?.let { AiClients.validate(it.copy(model = it.model.ifBlank { "x" })) }
            if (cfg == null || invalid != null) return@executeOnPooledThread done(emptyList(), invalid ?: "No pude leer la configuración del proveedor.")
            AiClients.create(cfg).listModels().whenComplete { models, err ->
                done(models.orEmpty().sorted(), err?.let { AiClients.friendly(it, cfg) })
            }
        }
    }

    private fun showModelPopup(models: List<String>, error: String?) {
        val current = settings.state.model.orEmpty().ifBlank { settings.preset.defaultModel }
        val type = "Escribir el nombre de otro modelo…"
        val more = "Cambiar de proveedor, clave o precio…"
        val mark = "✓ "
        val items = buildList {
            if (current.isNotBlank()) add(mark + current)
            addAll(models.filter { it != current })
            add(type)
            add(more)
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
            .setTitle(if (error != null) "No pude cargar la lista: $error" else "Modelo de ${settings.preset.label}")
            .setNamerForFiltering { it }
            .setItemChosenCallback { choice ->
                when (choice) {
                    more -> openSettings()
                    type -> Messages.showInputDialog(project, "Nombre exacto del modelo (por ejemplo claude-haiku-5-5):", "Modelo", null, current, null)
                        ?.trim()?.takeIf { it.isNotEmpty() }?.let(::setModel)
                    else -> setModel(choice.removePrefix(mark))
                }
            }
            .createPopup()
            .showUnderneathOf(modelLink)
    }

    private fun setModel(model: String) {
        settings.state.model = model
        modelLink.text = settings.modelLabel() + " ▾"
        scheduleRefresh()
    }

    private fun reviewNow() {
        currentKotlin()?.let(tutor::requestReview)
    }

    private fun send() {
        val q = input.text.trim()
        if (q.isEmpty()) return
        val file = currentKotlin()
        if (tutor.isChatBusy(file)) return
        input.text = ""
        tutor.ask(file, q)
    }

    private fun lineOf(file: VirtualFile, c: TutorService.LiveComment): Int =
        if (c.marker.isValid) TutorService.documentOf(file)?.getLineNumber(c.marker.startOffset) ?: 0 else 0

    private fun shortcut(actionId: String): String = KeymapUtil.getFirstKeyboardShortcutText(actionId)
    private fun shortcutNote(actionId: String): String = shortcut(actionId).let { if (it.isEmpty()) "" else " ($it)" }

    // ---------- HTML ----------
    private fun hex(c: Color) = "#" + ColorUtil.toHex(c)
    private val ok get() = hex(JBColor(Color(0x1C7A49), Color(0x5DBE8A)))
    private val warn get() = hex(JBColor(Color(0x9A6408), Color(0xE3A443)))
    private val bad get() = hex(JBColor(Color(0xB0382A), Color(0xEC7C69)))
    private val info get() = hex(JBColor(Color(0x2F6AAE), Color(0x8DB8F2)))
    private val muted get() = hex(JBColor.GRAY)
    private val cardBg get() = hex(JBColor(Color(0xEBECF0), Color(0x393B40)))
    private val userBg get() = hex(JBColor(Color(0xD9E6FA), Color(0x2E436E)))

    private fun esc(s: String) = StringUtil.escapeXmlEntities(s)

    private fun md(s: String): String = s.split("```").mapIndexed { i, part ->
        if (i % 2 == 1) "<pre>" + esc(part.substringAfter('\n', part).trimEnd()) + "</pre>"
        else esc(part).replace(Regex("`([^`]+)`"), "<code>$1</code>").replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>").replace("\n", "<br>")
    }.joinToString("")

    private fun sevColor(s: Severity) = when (s) { Severity.ERROR -> bad; Severity.WARN -> warn; Severity.INFO -> info }
    private fun typeColor(t: CommentType) = when (t) { CommentType.ERROR -> bad; CommentType.MEJORA -> warn; CommentType.PREGUNTA -> info; CommentType.BIEN -> ok }

    /** Quién habla y cuándo, en gris sobre cada mensaje del tutor. */
    private fun head(text: String) = "<div style='color:$muted;margin-bottom:3px'>$text</div>"

    /** Tarjeta con una barra de color a la izquierda: un comentario o una pista. */
    private fun card(color: String, html: String) =
        "<table width='100%' cellspacing='0' cellpadding='0' style='margin-top:5px'><tr><td width='3' bgcolor='$color'></td>" +
            "<td><table width='100%' cellspacing='0' cellpadding='7' bgcolor='$cardBg'><tr><td>$html</td></tr></table></td></tr></table>"

    /** Burbuja de lo que escribiste tú, desplazada a la derecha como en un chat. */
    private fun bubble(html: String) =
        "<table width='100%' cellspacing='0' cellpadding='0'><tr><td width='36'>&nbsp;</td>" +
            "<td><table width='100%' cellspacing='0' cellpadding='7' bgcolor='$userBg'><tr><td>$html</td></tr></table></td></tr></table>"

    private fun entry(html: String) = "<div style='margin-bottom:12px'>$html</div>"

    private fun show(pane: JEditorPane, scroll: JBScrollPane, html: String, toBottom: Boolean = false) {
        if (shown[pane] == html) return
        shown[pane] = html
        val bar = scroll.verticalScrollBar
        val pos = bar.value
        pane.text = html
        SwingUtilities.invokeLater {
            scroll.validate()
            bar.value = if (toBottom) bar.maximum else pos
        }
    }

    private fun refresh() {
        // Con el panel cerrado no se calcula nada, y con él abierto solo la pestaña que se ve: así no pesa mientras escribes.
        if (project.isDisposed || !isShowing) return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val file = editor?.virtualFile?.takeIf { TutorService.isKotlin(it) }
        when (tabs.selectedIndex) {
            0 -> refreshFeed(file)
            1 -> {
                val text = if (file != null) editor.document.text else ""
                val challenge = if (file != null) repo.challengeFor(text) else null
                syncSelection(challenge)
                val analysis = if (file != null) Analyzer.analyze(text, challenge, lexical = false) else null
                show(progress, progressScroll, progressHtml(file, text, challenge, analysis))
            }
            else -> show(usagePane, usageScroll, usageHtml())
        }
    }

    private fun refreshFeed(file: VirtualFile?) {
        refreshControls(file)
        val entries = tutor.timeline(file)
        val session = tutor.hintSession(file)
        // Si llegó algo nuevo a la conversación, baja hasta el final; si no, respeta donde estabas leyendo.
        val signature = listOf(
            file?.url, entries.size, tutor.isBusy(file), tutor.isHintBusy(file), tutor.isGiveUpBusy(file), tutor.isChatBusy(file),
            session?.notice, session?.error, tutor.errorFor(file),
        ).joinToString("|")
        show(feed, feedScroll, feedHtml(file, entries), toBottom = signature != feedSignature)
        feedSignature = signature
    }

    private fun refreshControls(file: VirtualFile?) {
        fileLabel.text = file?.name ?: "Sin archivo Kotlin abierto"
        syncing = true
        try {
            tutorSwitch.isEnabled = file != null
            tutorSwitch.isSelected = tutor.isTutorOn(file)
        } finally {
            syncing = false
        }
        statusLabel.text = "<html>${esc(statusText(file))}</html>"

        val next = if (file != null) tutor.nextHintLevel(file) else null
        val hintBusy = tutor.isHintBusy(file)
        hintLink.isEnabled = file != null && !hintBusy && next != null
        hintLink.text = when {
            hintBusy -> "Pensando la pista…"
            file == null || next == HintLevel.QUESTION -> "Dame una pista"
            next == null -> "Sin más pistas"
            else -> "Otra pista (${next.number}/${HintLevel.entries.size})"
        }
        hintLink.toolTipText = "Te guía sin darte la solución: pregunta → concepto → herramienta → pasos" + shortcutNote("KmpCoach.Hint")
        val busy = tutor.isBusy(file)
        reviewLink.isEnabled = file != null && !busy
        reviewLink.text = when {
            busy -> "Revisando…"
            tutor.reviewFor(file) != null -> "Actualizar revisión"
            else -> "Revisar archivo"
        }
        reviewLink.toolTipText = "El tutor comenta debajo de las líneas de este archivo" + shortcutNote("KmpCoach.ReviewNow")
        giveUpLink.isEnabled = file != null && !tutor.isGiveUpBusy(file)
        sendButton.isEnabled = !tutor.isChatBusy(file)
        if (!loadingModels) modelLink.text = settings.modelLabel() + " ▾"
    }

    private fun statusText(file: VirtualFile?): String {
        if (file == null) return "Abre un archivo .kt para usar el tutor."
        if (!tutor.isTutorOn(file)) {
            return "Apagado en este archivo: nada aparece solo. Enciéndelo cuando lo necesites${shortcutNote("KmpCoach.ToggleTutor")}."
        }
        val problems = tutor.ideProblems(file).size
        return when {
            tutor.isHintBusy(file) -> "Activo · pensando una pista…"
            tutor.isBusy(file) -> "Activo · revisando el archivo…"
            problems > 0 -> "Activo · el IDE marca $problems ${if (problems == 1) "error" else "errores"} · " +
                "si no avanzas en ${settings.state.stuckAfterSec} s te doy una pista"
            else -> "Activo · sin errores · los comentarios no cambian hasta que pidas actualizar la revisión"
        }
    }

    // ---------- pestaña «Tutor»: la conversación ----------
    private fun feedHtml(file: VirtualFile?, entries: List<TutorService.Entry>): String {
        val h = StringBuilder("<html><body>")
        val session = tutor.hintSession(file)
        val working = tutor.isBusy(file) || tutor.isHintBusy(file) || tutor.isGiveUpBusy(file) || tutor.isChatBusy(file)
        sessionLine(h, file)
        if (entries.isEmpty() && !working) guide(h, file)

        val totalHints = session?.hints?.size ?: 0
        var hintIndex = 0
        entries.forEach { e ->
            when (e) {
                is TutorService.Entry.Review -> if (file != null) reviewEntry(h, file, e.review)
                is TutorService.Entry.HintGiven -> {
                    hintIndex++
                    val hint = e.hint
                    val body = StringBuilder("<a href='line:${hint.line - 1}'>L${hint.line}</a> ${md(hint.text)}")
                    if (hint.redacted) body.append("<br><span style='color:$muted'>Oculté un fragmento con código de tu ejercicio.</span>")
                    if (hintIndex == totalHints && session?.lastAuto == true) {
                        body.append("<br><span style='color:$muted'>Te la di porque llevabas un rato sin avanzar.</span>")
                    }
                    h.append(entry(head("Mentor · pista ${hint.level.number}/${HintLevel.entries.size} · ${hint.level.label}") + card(info, body.toString())))
                }
                is TutorService.Entry.GiveUp -> h.append(entry(
                    head("Mentor · solución explicada") + md(e.text) +
                        "<div style='margin-top:4px'><a href='handoff'>Copiar para preguntarle a Claude</a></div>"
                ))
                is TutorService.Entry.Chat ->
                    if (e.message.role == "user") h.append(entry(bubble(md(e.message.content))))
                    else h.append(entry(head("Mentor") + md(e.message.content)))
            }
        }

        // Lo que está pasando ahora y los avisos, siempre al final.
        session?.notice?.let {
            h.append(entry("<span style='color:$warn'>${esc(it)}</span> <a href='hintforce'>Necesito otra pista igual</a>"))
        }
        session?.error?.let { h.append(entry("<span style='color:$bad'>${esc(it)}</span> <a href='settings'>Ajustes</a>")) }
        tutor.errorFor(file)?.let { h.append(entry("<span style='color:$bad'>${esc(it)}</span> <a href='settings'>Ajustes</a>")) }
        when {
            tutor.isHintBusy(file) -> h.append(entry("<span style='color:$muted'>Mentor · pensando la pista…</span>"))
            tutor.isBusy(file) -> h.append(entry("<span style='color:$muted'>Mentor · revisando ${esc(file?.name.orEmpty())}…</span> <a href='cancel'>Detener</a>"))
            tutor.isGiveUpBusy(file) -> h.append(entry("<span style='color:$muted'>Mentor · preparando la solución explicada…</span>"))
            tutor.isChatBusy(file) -> h.append(entry("<span style='color:$muted'>Mentor · escribiendo…</span>"))
        }

        val links = buildList {
            if (totalHints > 0) {
                if (tutor.nextHintLevel(file) == null) add("Ya viste las ${HintLevel.entries.size} pistas: inténtalo; si no sale, ríndete sin culpa")
                add("<a href='newproblem'>Nuevo problema (reinicia las pistas)</a>")
                if (session?.showInEditor == true) add("<a href='hidehint'>Quitar la pista del editor</a>")
            }
            if (tutor.chatFor(file).isNotEmpty()) add("<a href='clearchat'>Borrar el chat</a>")
        }
        if (links.isNotEmpty()) h.append("<div style='color:$muted'>${links.joinToString(" · ")}</div>")
        h.append("</body></html>")
        return h.toString()
    }

    /** Qué conversación con Claude Code está leyendo el tutor para saber en qué paso vas. Solo con el tutor encendido. */
    private fun sessionLine(h: StringBuilder, file: VirtualFile?) {
        if (file == null || !tutor.isTutorOn(file) || !settings.state.readAiSession) return
        val found = AiSessionContext.getInstance(project).peek(file.name)
        // Todo lo que el tutor tiene en cuenta: así sabes en qué se basa lo que te dice.
        val rules = ProjectRulesReader.getInstance(project).peek()
        val step = runCatching { StepGuideService.getInstance(project).view(file)?.current?.step }.getOrNull()
        val extra = listOfNotNull(
            step?.let { "Paso ${esc(it.number)}" },
            rules?.let { "reglas: ${esc(it.source)}" } ?: "sin AGENTS.md",
        ).joinToString(" · ")
        val text = if (found == null) {
            "Contexto: no encontré una conversación reciente de Claude Code en este proyecto · $extra. <a href='session'>elegir</a>"
        } else {
            "Contexto: «${esc(found.lastRequest ?: "conversación sin pedidos")}» · $extra · " +
                (if (found.automatic) "elegida sola" else "elegida por ti") + " · <a href='session'>cambiar</a>"
        }
        h.append("<div style='color:$muted;margin-bottom:10px'>$text</div>")
    }

    private fun chooseSession() {
        val ctx = AiSessionContext.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val items = ctx.candidates().map { it to ctx.label(it) }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val auto = "Automática: la más reciente que habla de este archivo"
                val options = listOf(auto) + items.map { it.second }
                JBPopupFactory.getInstance().createPopupChooserBuilder(options)
                    .setTitle("Conversación que lee el tutor")
                    .setItemChosenCallback { c -> ctx.choose(if (c == auto) null else items.firstOrNull { it.second == c }?.first) }
                    .createPopup()
                    .showCenteredInCurrentWindow(project)
            }
        }
    }

    /** Conversación vacía: cómo se usa, en tres gestos. Se puede cerrar. */
    private fun guide(h: StringBuilder, file: VirtualFile?) {
        if (settings.state.model.isNullOrBlank() && settings.preset.defaultModel.isEmpty()) {
            h.append(entry("<span style='color:$warn'>Falta elegir el modelo de IA.</span> <a href='settings'>Configurar proveedor</a>"))
        }
        if (file == null) {
            h.append(entry("<span style='color:$muted'>Abre un archivo Kotlin de tu proyecto, o un reto desde la pestaña <b>Progreso</b>. " +
                "También puedes escribir abajo una pregunta general.</span>"))
            return
        }
        if (settings.state.guideDismissed) {
            h.append("<div style='color:$muted;margin-bottom:8px'>Enciende el tutor, pide una pista o escribe abajo. Por ejemplo:</div>")
            listOf("¿Qué me preguntaría un entrevistador sobre este código?", "¿Qué parte de este archivo es la más frágil?").forEach { q ->
                h.append("<div><a href='ask:${URLEncoder.encode(q, Charsets.UTF_8)}'>${esc(q)}</a></div>")
            }
            return
        }
        val body = StringBuilder()
        body.append("<b>Cómo usar el tutor en este archivo</b><br><br>")
        body.append("<b>1. Enciéndelo cuando lo necesites.</b> Interruptor de arriba, botón «Mentor» junto a las pestañas del editor")
        body.append(shortcutNote("KmpCoach.ToggleTutor")).append(". Te da una primera ayuda y luego solo habla si te atascas. Apagado, no aparece nada solo.<br><br>")
        body.append("<b>2. Pide lo que quieras, encendido o no.</b> <a href='hint'>Dame una pista</a>").append(shortcutNote("KmpCoach.Hint"))
        body.append(" te guía sin darte la solución. <a href='review'>Revisar archivo</a>").append(shortcutNote("KmpCoach.ReviewNow"))
        body.append(" deja comentarios bajo las líneas, y no cambian hasta que tú pidas actualizarlos.<br><br>")
        body.append("<b>3. Responde ahí mismo.</b> Haz clic en un comentario del editor: se abre una ventanita pegada a él. " +
            "El tutor te contesta debajo del comentario y puedes seguir la conversación.<br><br>")
        body.append("<a href='guideok'>Entendido, ocultar esta guía</a>")
        h.append(entry(card(info, body.toString())))
    }

    private fun reviewEntry(h: StringBuilder, file: VirtualFile, fr: TutorService.FileReview) {
        val r = fr.review
        val b = StringBuilder(head("Mentor · revisión de ${esc(file.name)} · ${ago(fr.at)}"))
        val stColor = when (r.status) { "listo" -> ok; "desviado" -> bad; "a_medias" -> warn; else -> info }
        b.append("<span style='color:$stColor'><b>${esc(r.statusLabel)}</b></span>")
        if (r.summary.isNotBlank()) b.append(" · ").append(md(r.summary))
        fr.comments.forEach { c ->
            val line = lineOf(file, c)
            val body = StringBuilder("<a href='line:$line'>L${line + 1}</a> <b style='color:${typeColor(c.type)}'>${c.type.label}</b><br>${md(c.message)}")
            c.thread.forEach { m ->
                body.append("<br>")
                if (m.role == "user") body.append("<b>Tú:</b> ") else body.append("<b style='color:$info'>Mentor:</b> ")
                body.append(md(m.content))
            }
            if (c.busy) body.append("<br><span style='color:$muted'>Mentor · pensando…</span>")
            val coveredBy = if (c.resolved) null else tutor.coveringStep(file, c)
            when {
                c.autoResolved -> body.append("<br><span style='color:$ok'>✓ Resuelto solo: cambiaste el código del que hablaba</span>")
                c.resolved -> body.append("<br><span style='color:$ok'>✓ Resuelto</span>")
                coveredBy != null -> body.append("<br><span style='color:$muted'>Oculto en el editor: esa parte la revisa el ${esc(coveredBy)} («¿Cumple el paso?»)</span>")
                c.type != CommentType.BIEN && !c.busy -> {
                    val reply = if (c.thread.isEmpty()) "Responder" else "Seguir la conversación"
                    body.append("<br><a href='reply:${c.id}'>$reply</a> · <a href='resolve:${c.id}'>Resolver</a>")
                }
            }
            b.append(card(typeColor(c.type), body.toString()))
        }
        if (r.nextStep.isNotBlank()) b.append("<div style='margin-top:6px'><b>Siguiente paso:</b> ${md(r.nextStep)}</div>")
        if (fr.stale) {
            b.append("<div style='margin-top:6px;color:$warn'>Cambiaste el código desde esta revisión. Los comentarios siguen aquí hasta que tú decidas. ")
            b.append("<a href='review'>Actualizar revisión</a></div>")
        }
        b.append("<div style='margin-top:4px;color:$muted'>")
        if (fr.sources.isNotEmpty()) b.append("Leí: ${esc(fr.sources.joinToString(" · "))}<br>")
        fr.call?.let { b.append("${callCost(it.usage.inputTokens, it.usage.outputTokens, it.costUsd, it.estimated)} · ") }
        b.append("<a href='clear'>Quitar esta revisión</a></div>")
        h.append(entry(b.toString()))
    }

    // ---------- pestaña «Progreso» ----------
    private fun progressHtml(file: VirtualFile?, text: String, challenge: Challenge?, analysis: Analysis?): String {
        val h = StringBuilder("<html><body>")
        planSection(h)
        if (challenge != null && analysis != null) challengeSection(h, challenge, text, analysis)
        comprehensionSection(h, file, text)
        repasoSection(h)
        if (analysis != null) localSection(h, analysis)
        h.append("<p style='color:$muted'><a href='reload'>Recargar retos</a></p>")
        h.append("</body></html>")
        return h.toString()
    }

    private fun challengeSection(h: StringBuilder, c: Challenge, text: String, a: Analysis) {
        h.append("<p><b>Reto: ${esc(c.title)}</b><br><span style='color:$muted'>${c.level.label} · ${esc(c.topic)}${if (c.minutes > 0) " · ${c.minutes} min" else ""} · ${esc(c.packName)}</span></p>")
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

    private fun planSection(h: StringBuilder) {
        val ctx = ProjectContext.getInstance(project)
        val plan = ctx.plan()
        h.append("<p><b>Plan</b> ")
        if (plan == null) {
            h.append("<span style='color:$muted'>· sin plan</span><br>")
            h.append("<span style='color:$muted'>Guarda en <code>docs/plans/</code> el plan que hagas con cualquier IA (un archivo Markdown) ")
            h.append("y el tutor sabrá qué intentas hacer sin que se lo cuentes.</span> <a href='plan:choose'>Elegir un archivo</a></p>")
            return
        }
        h.append("<span style='color:$muted'>· ${esc(plan.title)} · ${plan.doneCount}/${plan.steps.size} hechos</span><br>")
        val step = ctx.currentStep()
        if (step == null) {
            h.append("<span style='color:$muted'>No encontré pasos. Usa casillas (<code>- [ ]</code>), una lista numerada o títulos <code>##</code>.</span> ")
            h.append("<a href='plan:open'>Abrir plan</a> · <a href='plan:choose'>Cambiar</a></p>")
            return
        }
        h.append("Paso ${step.index + 1}: <b>${md(step.text)}</b>")
        if (step.done) h.append(" <span style='color:$ok'>✓ hecho</span>")
        h.append("<br><span style='color:$muted'><a href='plan:prev'>‹ anterior</a> · <a href='plan:next'>siguiente ›</a> · ")
        h.append("<a href='plan:pending'>primer pendiente</a> · <a href='plan:open'>abrir</a> · <a href='plan:choose'>cambiar</a></span><br>")
        val guess = ctx.guessForStep()
        if (guess == null) {
            h.append("<a href='guess'>Antes de empezar: ¿qué archivos crees que toca este paso?</a>")
        } else {
            h.append("<span style='color:$muted'>Tu predicción: ${esc(guess)}</span> · ")
            h.append("<a href='guesscompare'>${if (showGuessCompare) "ocultar comparación" else "comparar con lo que cambió"}</a> · <a href='guess'>cambiar</a>")
            if (showGuessCompare) {
                val r = FileGuess.compare(guess, ctx.changedPaths)
                fun names(l: List<String>) = esc(l.joinToString { it.substringAfterLast('/') })
                h.append("<br>")
                if (ctx.changedPaths.isEmpty()) h.append("<span style='color:$muted'>Todavía no hay archivos cambiados según git.</span>")
                if (r.hits.isNotEmpty()) h.append("<span style='color:$ok'>✓ Acertaste: ${names(r.hits)}</span><br>")
                if (r.missed.isNotEmpty()) h.append("<span style='color:$warn'>○ También cambiaron: ${names(r.missed)}</span> <span style='color:$muted'>¿por qué hacía falta tocarlos?</span><br>")
                if (r.wrong.isNotEmpty()) h.append("<span style='color:$muted'>· Nombraste y no cambiaron: ${esc(r.wrong.joinToString())}</span>")
            }
        }
        h.append("</p>")
    }

    private fun choosePlan() {
        val ctx = ProjectContext.getInstance(project)
        val other = "Otro archivo…"
        val auto = "Automático: el más reciente de docs/plans"
        val options = listOf(auto) + ctx.availablePlans() + other
        JBPopupFactory.getInstance().createPopupChooserBuilder(options)
            .setTitle("Plan activo")
            .setItemChosenCallback { choice ->
                when (choice) {
                    auto -> ctx.choosePlan(null)
                    other -> FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("md"), project, null) { vf ->
                        ctx.choosePlan(ctx.relativePath(vf) ?: vf.path)
                    }
                    else -> ctx.choosePlan(choice)
                }
            }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private fun askGuess() {
        val ctx = ProjectContext.getInstance(project)
        val step = ctx.currentStep() ?: return
        val message = "Paso: ${step.text}\n\n¿Qué archivos o clases crees que vas a crear o modificar? Escríbelo como te salga, " +
            "por ejemplo: «el HomeViewModel y un componente nuevo FavoriteButton». Al terminar, compáralo con lo que realmente cambió."
        val answer = Messages.showMultilineInputDialog(project, message.replace("`", ""), "Antes de empezar", ctx.guessForStep().orEmpty(), Messages.getQuestionIcon(), null)
        if (!answer.isNullOrBlank()) {
            ctx.saveGuess(answer.trim())
            showGuessCompare = false
        }
    }

    private fun comprehensionSection(h: StringBuilder, file: VirtualFile?, text: String) {
        if (!settings.state.trackNewCode) return
        val ctx = ProjectContext.getInstance(project)
        val learning = LearningService.getInstance(project)
        h.append("<p><b>Comprensión del código nuevo</b> <span style='color:$muted'>· según git, lo escriba quien lo escriba</span><br>")
        val all = ctx.allBlocks()
        when {
            ctx.gitError != null -> h.append("<span style='color:$muted'>${esc(ctx.gitError!!)}</span>")
            ctx.refreshedAt == 0L -> h.append("<span style='color:$muted'>Leyendo los cambios de git…</span>")
            all.isEmpty() -> h.append("<span style='color:$muted'>No hay código nuevo sin commit. Cuando tú o una IA escriban código, aparecerá aquí para que compruebes que lo entiendes.</span>")
            else -> {
                val ignored = all.count { ctx.isIgnored(it) }
                val counted = all.size - ignored
                val understood = all.count { ctx.isUnderstood(it) }
                val color = if (understood == counted) ok else if (understood * 2 >= counted) info else warn
                h.append("Comprobaste que entiendes <b style='color:$color'>$understood de $counted</b> bloques nuevos.<br>")
                learning.status?.let { h.append("<span style='color:$info'>${esc(it)}</span><br>") }
                val here = if (file != null) ctx.blocksIn(file, text.lines()) else emptyList()
                here.forEach { (b, start) ->
                    val end = start + b.lineCount - 1
                    h.append("<a href='line:${start - 1}'>L$start–$end</a> ")
                    when {
                        ctx.isUnderstood(b) -> h.append("<span style='color:$ok'>✓ entendido</span>")
                        ctx.isIgnored(b) -> h.append("<span style='color:$muted'>sin revisión</span>")
                        learning.isBusy(b) -> h.append("<span style='color:$info'>en curso…</span>")
                        else -> h.append("<span style='color:$warn'>sin comprobar</span> · <a href='cpredict:${b.id}'>Predecir</a> · " +
                            "<a href='cexplain:${b.id}'>Explicar sin mirar</a> · <a href='cignore:${b.id}'>No hace falta</a>")
                    }
                    h.append("<br>")
                }
                val elsewhere = ctx.pendingBlocks().count { b -> here.none { it.first.id == b.id } }
                if (elsewhere > 0) h.append("<a href='cnext'>Ir al siguiente bloque sin comprobar</a> <span style='color:$muted'>($elsewhere en otros archivos)</span><br>")
                h.append("<span style='color:$muted'>En el editor, el icono <b>?</b> del margen marca cada bloque sin comprobar.</span>")
            }
        }
        val st = ctx.state
        if (st.predictions > 0) {
            h.append("<br><span style='color:$muted'>Tus predicciones (código y tests): ${st.predictionsRight} de ${st.predictions} acertadas.</span>")
        }
        h.append(" <a href='crefresh'>actualizar</a></p>")
    }

    private fun goToNextPending() {
        val ctx = ProjectContext.getInstance(project)
        val current = currentFile()?.let { ctx.relativePath(it) }
        val b = ctx.pendingBlocks().sortedBy { if (it.path == current) 1 else 0 }.firstOrNull() ?: return
        val base = ctx.basePath ?: return
        val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(base, b.path)) ?: return
        OpenFileDescriptor(project, vf, b.startLine - 1, 0).navigate(true)
    }

    private fun repasoSection(h: StringBuilder) {
        val profile = LearnerProfile.getInstance()
        val cards = profile.cards()
        val mastered = profile.mastered()
        if (cards.isEmpty() && mastered.isEmpty()) return
        h.append("<p><b>Repaso</b><br>")
        val due = profile.dueReview()
        when {
            LearningService.getInstance(project).isReviewBusy() -> h.append("<span style='color:$muted'>Repasando…</span><br>")
            due != null -> {
                h.append("Toca repasar <b>${esc(due.concept)}</b> · <a href='reviewnow'>Repasar (30 s)</a>")
                val n = profile.dueCount()
                if (n > 1) h.append(" <span style='color:$muted'>· $n pendientes</span>")
                h.append("<br>")
            }
            cards.isNotEmpty() -> cards.minByOrNull { it.due }?.let { h.append("<span style='color:$muted'>Nada que repasar hoy. Próximo: ${esc(it.concept)} (${it.due}).</span><br>") }
        }
        if (mastered.isNotEmpty()) {
            h.append("<span style='color:$ok'>Dominas: ${esc(mastered.joinToString())}</span> <span style='color:$muted'>· las pistas sobre esto serán más breves.</span>")
        }
        h.append("</p>")
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
        h.append("<p><b>Análisis local</b> <span style='color:$muted'>· sin IA</span><br>")
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

    // ---------- pestaña «Consumo» ----------
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
            s < 60 -> "ahora"
            s < 3600 -> "hace ${s / 60} min"
            else -> "hace ${s / 3600} h"
        }
    }

    private fun statsCost(st: UsageStats): String {
        val t = "${UsageMath.tokens(st.inputTokens + st.outputTokens)} tokens"
        return when {
            settings.preset == ProviderPreset.OLLAMA -> "$t · gratis"
            st.pricedCalls == 0 -> t
            st.pricedCalls < st.calls -> "$t · ≥ ${UsageMath.usd(st.costUsd)}"
            else -> "$t · ${UsageMath.usd(st.costUsd)}"
        }
    }

    private fun usageHtml(): String {
        val h = StringBuilder("<html><body>")
        val p = settings.preset
        h.append("<p><b>Consumo de la API</b> <span style='color:$muted'>· ${esc(settings.modelLabel())} · <a href='settings'>cambiar</a></span><br>")

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
        h.append("</p></body></html>")
        return h.toString()
    }

    // ---------- enlaces ----------
    private fun onLink(desc: String) {
        val file = currentFile()
        val kotlin = file?.takeIf { TutorService.isKotlin(it) }
        val challenge = file?.let { f -> TutorService.documentOf(f)?.let { repo.challengeFor(it.text) } }
        when {
            desc.startsWith("line:") -> file?.let { OpenFileDescriptor(project, it, desc.removePrefix("line:").toIntOrNull() ?: 0, 0).navigate(true) }
            desc.startsWith("ask:") -> tutor.ask(kotlin, URLDecoder.decode(desc.removePrefix("ask:"), Charsets.UTF_8))
            desc.startsWith("reply:") -> kotlin?.let { f -> tutor.findComment(f, desc.removePrefix("reply:"))?.let { tutor.replyTo(f, it) } }
            desc.startsWith("resolve:") -> kotlin?.let { f -> tutor.findComment(f, desc.removePrefix("resolve:"))?.let { tutor.resolveComment(f, it) } }
            desc == "guideok" -> { settings.state.guideDismissed = true; scheduleRefresh() }
            desc == "session" -> chooseSession()
            desc == "review" -> reviewNow()
            desc == "solution" -> challenge?.let { ChallengeFiles.openSolution(project, it) }
            desc == "restart" -> if (file != null && challenge != null) restart(file, challenge)
            desc == "settings" -> openSettings()
            desc == "cancel" -> file?.let(tutor::cancelReview)
            desc == "clear" -> file?.let(tutor::clearReview)
            desc == "hint" -> kotlin?.let(tutor::requestHint)
            desc == "handoff" -> file?.let(tutor::copyHandoff)
            desc == "newproblem" -> file?.let(tutor::resetHints)
            desc == "hidehint" -> file?.let(tutor::hideHintInEditor)
            desc == "hintforce" -> kotlin?.let { tutor.requestHint(it, force = true) }
            desc.startsWith("plan:") -> {
                val ctx = ProjectContext.getInstance(project)
                when (desc.removePrefix("plan:")) {
                    "prev" -> ctx.moveStep(-1)
                    "next" -> ctx.moveStep(1)
                    "pending" -> ctx.followPending()
                    "choose" -> choosePlan()
                    "open" -> ctx.planFile()?.let { vf ->
                        OpenFileDescriptor(project, vf, (ctx.currentStep()?.line ?: 1) - 1, 0).navigate(true)
                    }
                }
                showGuessCompare = false
            }
            desc == "guess" -> askGuess()
            desc == "guesscompare" -> { showGuessCompare = !showGuessCompare; scheduleRefresh() }
            desc.startsWith("cpredict:") || desc.startsWith("cexplain:") || desc.startsWith("cignore:") -> {
                val ctx = ProjectContext.getInstance(project)
                val block = ctx.allBlocks().firstOrNull { it.id == desc.substringAfter(':') } ?: return
                when (desc.substringBefore(':')) {
                    "cpredict" -> LearningService.getInstance(project).predict(block)
                    "cexplain" -> LearningService.getInstance(project).explain(block)
                    "cignore" -> ctx.markIgnored(block)
                }
            }
            desc == "cnext" -> goToNextPending()
            desc == "crefresh" -> ProjectContext.getInstance(project).scheduleRefresh()
            desc == "reviewnow" -> LearningService.getInstance(project).reviewNow()
            desc.startsWith("open:") -> BrowserUtil.browse(desc.removePrefix("open:"))
            desc == "balance" -> tutor.refreshBalance()
            desc == "resetusage" -> if (Messages.showYesNoDialog(project, "¿Reiniciar el contador de consumo acumulado?", "Consumo de la API", null) == Messages.YES) usage.resetTotals()
            desc == "clearchat" -> tutor.clearChat(kotlin)
            desc == "reload" -> {
                val w = repo.reload()
                fillChallenges(levelBox.selectedItem as? Level ?: Level.JUNIOR)
                Messages.showInfoMessage(project,
                    if (w.isEmpty()) "Retos recargados: ${repo.all().size}." else "Retos recargados con advertencias:\n" + w.joinToString("\n"),
                    "Mentor")
                scheduleRefresh()
            }
        }
    }

    private fun restart(file: VirtualFile, c: Challenge) {
        val answer = Messages.showYesNoDialog(project, "¿Reemplazar tu código por el código inicial del reto?", "Empezar de nuevo", Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        val doc = TutorService.documentOf(file) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Empezar de nuevo", null, { doc.setText(c.header + "\n" + c.starter.trimEnd() + "\n") })
        tutor.clearReview(file)
    }

    override fun dispose() {}
}

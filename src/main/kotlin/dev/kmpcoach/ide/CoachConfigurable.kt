package dev.kmpcoach.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.layout.selected
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.openapi.util.text.StringUtil
import dev.kmpcoach.core.AiClients
import dev.kmpcoach.core.AiConfig
import dev.kmpcoach.core.ChatMessage
import dev.kmpcoach.core.Level
import dev.kmpcoach.core.ProviderPreset
import javax.swing.JComponent
import javax.swing.JLabel

private enum class Lang(val id: String, val label: String) {
    ES("es", "Español"), EN("en", "English");
    override fun toString() = label
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: ES }
}

/** Ajustes › Herramientas › KMP Interview Coach: proveedor, clave, modelo y comportamiento del tutor. */
class CoachConfigurable : BoundConfigurable("KMP Interview Coach") {

    private val settings = CoachSettings.getInstance()
    private var shown: ProviderPreset = settings.preset
    private val loadedKeys = HashMap<ProviderPreset, String>()
    private val editedKeys = HashMap<ProviderPreset, String>()

    private lateinit var baseUrlField: JBTextField
    private lateinit var modelField: JBTextField
    private lateinit var keyField: JBPasswordField
    private lateinit var status: JLabel
    private lateinit var priceInField: JBTextField
    private lateinit var priceOutField: JBTextField

    /**
     * El almacén seguro puede ser lento (Llavero, KWallet): se lee y escribe fuera del hilo de UI.
     * Mientras no termina de leerse, el campo queda vacío y no cuenta como modificado.
     */
    private fun loadKey(p: ProviderPreset) {
        loadedKeys[p]?.let { if (shown == p && keyField.password.isEmpty()) keyField.text = it; return }
        ApplicationManager.getApplication().executeOnPooledThread {
            val key = runCatching { CoachSecrets.get(p).orEmpty() }.getOrDefault("")
            ApplicationManager.getApplication().invokeLater({
                loadedKeys[p] = key
                if (shown == p && keyField.password.isEmpty() && editedKeys[p] == null) keyField.text = key
            }, ModalityState.any())
        }
    }

    private fun keyModified(p: ProviderPreset, value: String): Boolean {
        val stored = loadedKeys[p] ?: return value.isNotBlank()
        return value.trim() != stored
    }

    override fun createPanel(): DialogPanel = panel {
        group("Proveedor de IA (usa tu propia clave)") {
            row("Proveedor:") {
                comboBox(ProviderPreset.entries.toList())
                    .bindItem({ settings.preset }, { settings.state.preset = (it ?: ProviderPreset.ANTHROPIC).id })
                    .applyToComponent { addActionListener { (selectedItem as? ProviderPreset)?.let(::switchPreset) } }
            }
            row("URL base:") {
                baseUrlField = textField().columns(COLUMNS_LARGE)
                    .bindText({ settings.state.baseUrl.orEmpty() }, { settings.state.baseUrl = it.trim() })
                    .component
            }
            row("Modelo:") {
                modelField = textField().columns(COLUMNS_MEDIUM)
                    .bindText({ settings.state.model.orEmpty() }, { settings.state.model = it.trim() })
                    .component
                modelField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                    override fun textChanged(e: javax.swing.event.DocumentEvent) = syncPriceWithModel()
                })
                button("Cargar modelos") { e -> loadModels(e.source as JComponent) }
            }
            row("Clave de API:") {
                keyField = passwordField().columns(COLUMNS_LARGE).component
                loadKey(shown)
            }.rowComment("Se guarda en el almacén seguro del IDE (Llavero, KWallet o KeePass), una por proveedor. Ollama y los servidores locales no la necesitan.")
            row {
                button("Probar conexión") { testConnection() }
                status = label("").component
            }
            row {
                comment("Tu código se envía solo al proveedor que elijas, y solo cuando pides una revisión, preguntas en el chat o activas el tutor en vivo.")
            }
        }
        group("Costo (para estimar cuánto gasta cada llamada)") {
            row("Precio por millón de tokens (USD):") {
                priceInField = textField().columns(6)
                    .bindText({ savedPrice { it.priceInputPerM } }, { settings.state.priceInputPerM = it.trim() })
                    .applyToComponent { emptyText.text = "entrada" }
                    .component
                label("entrada")
                priceOutField = textField().columns(6)
                    .bindText({ savedPrice { it.priceOutputPerM } }, { settings.state.priceOutputPerM = it.trim() })
                    .applyToComponent { emptyText.text = "salida" }
                    .component
                label("salida")
            }.rowComment(
                "Cópialo de la página de precios de tu proveedor para el modelo que elegiste. Se guarda para ese modelo: si cambias de modelo, escribe el nuevo precio. " +
                    "Sin precio, el panel muestra solo tokens. OpenRouter informa el costo real de cada llamada y no lo necesita."
            )
        }
        group("Tutor") {
            row("Tu nivel:") {
                comboBox(Level.entries.toList())
                    .bindItem({ Level.of(settings.state.studentLevel) }, { settings.state.studentLevel = (it ?: Level.JUNIOR).id })
            }
            row("Idioma de las respuestas:") {
                comboBox(Lang.entries.toList())
                    .bindItem({ Lang.of(settings.state.language) }, { settings.state.language = (it ?: Lang.ES).id })
            }
            row("Comentarios por revisión (máx.):") {
                intTextField(1..10).bindIntText({ settings.state.maxComments }, { settings.state.maxComments = it })
            }
            lateinit var live: Cell<JBCheckBox>
            row {
                live = checkBox("Tutor en vivo: acompañarme cuando hago una pausa al escribir")
                    .bindSelected({ settings.state.liveMode }, { settings.state.liveMode = it })
            }
            indent {
                row("Pausa antes de actuar (ms):") {
                    intTextField(500..20_000).bindIntText({ settings.state.liveDelayMs }, { settings.state.liveDelayMs = it })
                }
                row("Con errores, darme una pista si no avanzo en (s):") {
                    intTextField(15..600).bindIntText({ settings.state.stuckAfterSec }, { settings.state.stuckAfterSec = it })
                }.rowComment("Si el IDE marca errores o hay un TODO(), el tutor espera a que lleves ese tiempo sin reducirlos y te da una sola pista. Para más, pulsa «Otra pista».")
                row("Sin errores, tiempo mínimo entre revisiones (s):") {
                    intTextField(5..600).bindIntText({ settings.state.minIntervalSec }, { settings.state.minIntervalSec = it })
                }.rowComment("Cuando el código no tiene errores, revisa tu enfoque y te hace preguntas, solo si cambió desde la última revisión.")
            }.enabledIf(live.component.selected)
        }
        group("Aprendizaje") {
            row {
                checkBox("Pista en gris al final de la línea (Tab: otra pista · Esc: ocultar)")
                    .bindSelected({ settings.state.ghostHints }, { settings.state.ghostHints = it })
            }
            row {
                checkBox("Marcar el código nuevo (según git) que aún no compruebo que entiendo")
                    .bindSelected({ settings.state.trackNewCode }, { settings.state.trackNewCode = it })
            }.rowComment("Funciona con cualquier IA: lee lo que cambió en git, no la conversación. El plan se lee de docs/plans/*.md.")
            row {
                checkBox("Antes de ver el resultado de los tests, preguntarme qué creo que pasará")
                    .bindSelected({ settings.state.predictTests }, { settings.state.predictTests = it })
            }
            row {
                checkBox("Una pregunta de repaso al día sobre conceptos que me costaron")
                    .bindSelected({ settings.state.dailyReview }, { settings.state.dailyReview = it })
            }
        }
        group("Análisis local (sin IA)") {
            row {
                checkBox("Analizar todos los archivos Kotlin, no solo los retos")
                    .bindSelected({ settings.state.analyzeAllKotlin }, { settings.state.analyzeAllKotlin = it })
            }
        }
    }

    /** El precio guardado solo se muestra si corresponde al modelo configurado. */
    private fun savedPrice(get: (CoachSettings.Options) -> String?): String =
        if (settings.state.pricedModel == settings.modelKey()) get(settings.state).orEmpty() else ""

    private fun dialogModelKey() = "${shown.id}/${modelField.text.trim().ifBlank { shown.defaultModel }}"

    /** Al cambiar de modelo en el diálogo, el precio del anterior ya no aplica. */
    private fun syncPriceWithModel() {
        if (!::priceInField.isInitialized) return
        if (dialogModelKey() == settings.state.pricedModel) {
            priceInField.text = settings.state.priceInputPerM.orEmpty()
            priceOutField.text = settings.state.priceOutputPerM.orEmpty()
        } else {
            priceInField.text = ""
            priceOutField.text = ""
        }
    }

    private fun switchPreset(p: ProviderPreset) {
        if (p == shown) return
        editedKeys[shown] = String(keyField.password)
        shown = p
        if (p != ProviderPreset.CUSTOM) {
            baseUrlField.text = p.baseUrl
            modelField.text = p.defaultModel
        }
        keyField.text = editedKeys[p] ?: loadedKeys[p].orEmpty()
        if (editedKeys[p] == null && loadedKeys[p] == null) loadKey(p)
        status.text = if (p.defaultModel.isEmpty()) "Pulsa «Cargar modelos» para elegir uno." else ""
    }

    private fun uiConfig() = AiConfig(shown, baseUrlField.text.trim(), modelField.text.trim(), String(keyField.password).trim())

    private fun show(text: String) {
        status.text = "<html>" + StringUtil.escapeXmlEntities(text) + "</html>"
    }

    private fun loadModels(anchor: JComponent) {
        val cfg = uiConfig()
        if (cfg.baseUrl.isBlank()) return show("Escribe la URL base.")
        if (cfg.preset.needsKey && cfg.apiKey.isNullOrBlank()) return show("Escribe la clave para ver los modelos.")
        show("Cargando modelos…")
        AiClients.create(cfg).listModels().whenComplete { models, err ->
            ApplicationManager.getApplication().invokeLater({
                when {
                    err != null -> show(AiClients.friendly(err, cfg))
                    models.isNullOrEmpty() -> show("El proveedor no devolvió modelos. Escribe el nombre a mano.")
                    else -> {
                        show("${models.size} modelos disponibles.")
                        JBPopupFactory.getInstance().createPopupChooserBuilder(models)
                            .setTitle("Modelos de ${cfg.preset.label}")
                            .setNamerForFiltering { it }
                            .setItemChosenCallback { modelField.text = it }
                            .createPopup()
                            .showUnderneathOf(anchor)
                    }
                }
            }, ModalityState.stateForComponent(anchor))
        }
    }

    private fun testConnection() {
        val cfg = uiConfig()
        AiClients.validate(cfg)?.let { return show(it) }
        show("Probando…")
        AiClients.create(cfg).complete("Responde solo con la palabra OK.", listOf(ChatMessage("user", "Prueba de conexión")), 20)
            .whenComplete { reply, err ->
                ApplicationManager.getApplication().invokeLater({
                    show(if (err != null) AiClients.friendly(err, cfg) else "Conectado con ${cfg.model}. Respuesta: ${reply?.text.orEmpty().trim().take(60)}")
                }, ModalityState.stateForComponent(status))
            }
    }

    override fun isModified(): Boolean {
        if (super.isModified()) return true
        val now = HashMap(editedKeys).apply { put(shown, String(keyField.password)) }
        return now.any { (p, k) -> keyModified(p, k) }
    }

    override fun apply() {
        super.apply()
        settings.state.pricedModel = settings.modelKey()
        editedKeys[shown] = String(keyField.password)
        val toSave = editedKeys.filter { (p, k) -> keyModified(p, k) }.mapValues { it.value.trim() }
        toSave.forEach { (p, k) -> loadedKeys[p] = k }
        if (toSave.isNotEmpty()) ApplicationManager.getApplication().executeOnPooledThread {
            toSave.forEach { (p, k) -> CoachSecrets.set(p, k) }
        }
        editedKeys.clear()
    }

    override fun reset() {
        super.reset()
        editedKeys.clear()
        shown = settings.preset
        keyField.text = loadedKeys[shown].orEmpty()
        loadKey(shown)
        status.text = ""
    }
}

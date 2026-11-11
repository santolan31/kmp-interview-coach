package dev.kmpcoach.ide

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import dev.kmpcoach.core.AiClient
import dev.kmpcoach.core.AiClients
import dev.kmpcoach.core.AiConfig
import dev.kmpcoach.core.AiException
import dev.kmpcoach.core.Level
import dev.kmpcoach.core.ModelPrice
import dev.kmpcoach.core.PromptOptions
import dev.kmpcoach.core.ProviderPreset

/** Ajustes globales del plugin. La clave de API no se guarda aquí sino en el almacén seguro del IDE ([CoachSecrets]). */
@Service(Service.Level.APP)
@State(name = "KmpInterviewCoach", storages = [Storage("kmpInterviewCoach.xml")])
class CoachSettings : SimplePersistentStateComponent<CoachSettings.Options>(Options()) {

    class Options : BaseState() {
        var preset by string(ProviderPreset.ANTHROPIC.id)
        var baseUrl by string(ProviderPreset.ANTHROPIC.baseUrl)
        var model by string(ProviderPreset.ANTHROPIC.defaultModel)
        var studentLevel by string(Level.JUNIOR.id)
        var language by string("es")
        /** Con el tutor encendido en un archivo: pausa al escribir antes de comprobar si estás atascado. */
        var liveDelayMs by property(2500)
        /** Con el tutor encendido, con errores o código incompleto: segundos sin avanzar antes de que te dé una pista. */
        var stuckAfterSec by property(45)
        /** Con el tutor encendido, leer tu conversación más reciente de Claude Code en este proyecto para saber en qué paso vas. */
        var readAiSession by property(true)
        /** Ya cerraste la guía de primer uso del panel. */
        var guideDismissed by property(false)
        /** Atajos de la pista bajo la línea: Tab pide la siguiente y Esc la oculta. */
        var ghostHints by property(true)
        /** Antes de ver el resultado de los tests, pregunta qué crees que pasará. */
        var predictTests by property(true)
        /** Una pregunta de repaso al día sobre un concepto que te costó. */
        var dailyReview by property(true)
        /** Marca en el margen el código nuevo (según git) que aún no has comprobado que entiendes. */
        var trackNewCode by property(true)
        var maxComments by property(5)
        var analyzeAllKotlin by property(true)
        /** Precio del modelo en USD por millón de tokens, para estimar el costo. [pricedModel] es el modelo al que corresponde. */
        var priceInputPerM by string("")
        var priceOutputPerM by string("")
        var pricedModel by string("")
    }

    val preset: ProviderPreset get() = ProviderPreset.of(state.preset)

    fun aiConfig(): AiConfig {
        val p = preset
        return AiConfig(
            preset = p,
            baseUrl = state.baseUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: p.baseUrl,
            model = state.model?.trim().takeUnless { it.isNullOrEmpty() } ?: p.defaultModel,
            apiKey = CoachSecrets.get(p),
        )
    }

    /** Crea el cliente de IA. Lee la clave del almacén seguro: llámalo fuera del hilo de UI. */
    fun createClient(): Pair<AiClient, AiConfig> {
        val cfg = aiConfig()
        AiClients.validate(cfg)?.let { throw AiException(it) }
        return AiClients.create(cfg) to cfg
    }

    fun promptOptions() = PromptOptions(Level.of(state.studentLevel), state.language ?: "es", state.maxComments.coerceIn(1, 10))

    /** Identifica proveedor y modelo, para saber si el precio guardado corresponde al modelo actual. */
    fun modelKey(): String = "${preset.id}/${state.model.orEmpty().ifBlank { preset.defaultModel }}"

    /** Precio del modelo actual; null si no lo configuraste o si lo configuraste para otro modelo. */
    fun price(): ModelPrice? =
        if (state.pricedModel == modelKey()) ModelPrice.parse(state.priceInputPerM, state.priceOutputPerM) else null

    fun modelLabel(): String = "${preset.label} · ${state.model.orEmpty().ifBlank { preset.defaultModel }}"

    companion object {
        fun getInstance(): CoachSettings = service()
    }
}

object CoachSecrets {
    // Nombre antiguo a propósito: es donde ya están guardadas las claves. Si cambiara, el plugin no las encontraría.
    private fun attributes(p: ProviderPreset) = CredentialAttributes(generateServiceName("KMP Interview Coach", p.id))
    fun get(p: ProviderPreset): String? = PasswordSafe.instance.getPassword(attributes(p))
    fun set(p: ProviderPreset, key: String?) = PasswordSafe.instance.setPassword(attributes(p), key?.trim()?.ifEmpty { null })
}

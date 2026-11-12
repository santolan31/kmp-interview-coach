package dev.kmpcoach.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.kmpcoach.core.AiClients
import dev.kmpcoach.core.AiConfig
import dev.kmpcoach.core.ChatMessage
import dev.kmpcoach.core.futureOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Si el Mentor puede hablar con la IA elegida. Se prueba con una llamada mínima («responde OK») al guardar Ajustes y
 * cuando pulsas «Probar» en el panel, y se avisa del resultado: así sabes enseguida si quedó conectado.
 * Con clave de API cuesta unos pocos tokens; con suscripción gasta algo de su cuota.
 */
@Service(Service.Level.APP)
class ConnectionStatus {
    enum class State { UNTESTED, TESTING, OK, ERROR }

    @Volatile var state = State.UNTESTED
        private set
    @Volatile var message = "Sin probar"
        private set
    @Volatile var at = 0L
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(parent: Disposable, l: () -> Unit) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    private fun set(s: State, m: String) {
        state = s
        message = m
        at = System.currentTimeMillis()
        ApplicationManager.getApplication().invokeLater { listeners.forEach { it() } }
    }

    /** Prueba la conexión en segundo plano y avisa (si [notify]) con el resultado y qué hacer si falló. */
    fun test(project: Project?, notify: Boolean = true) {
        if (state == State.TESTING) return
        set(State.TESTING, "Probando la conexión…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val settings = CoachSettings.getInstance()
            val label = settings.modelLabel()
            var cfg: AiConfig? = null
            val t0 = System.currentTimeMillis()
            try {
                val (client, c) = settings.createClient()
                cfg = c
                futureOf { client.complete("Responde solo con la palabra OK.", listOf(ChatMessage("user", "Prueba de conexión")), 20) }
                    .get(200, TimeUnit.SECONDS)
                val secs = String.format("%.1f", (System.currentTimeMillis() - t0) / 1000.0)
                val how = if (c.preset.isSubscription) "con tu suscripción" else "con tu clave de API"
                set(State.OK, "Conectado $how ($secs s)")
                if (notify) notify(project, "✓ Mentor conectado: $label · $how · respondió en $secs s.", NotificationType.INFORMATION)
            } catch (e: Throwable) {
                val cause = if (e is ExecutionException) e.cause ?: e else e
                val msg = AiClients.friendly(cause, cfg)
                set(State.ERROR, msg)
                if (notify) notify(project, "✗ El Mentor no pudo conectarse ($label): $msg", NotificationType.WARNING)
            }
        }
    }

    private fun notify(project: Project?, text: String, type: NotificationType) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup(TutorService.NOTIFICATION_GROUP)
            .createNotification("Conexión del Mentor", text, type)
        if (type != NotificationType.INFORMATION) n.addAction(NotificationAction.createSimple("Abrir ajustes") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, CoachConfigurable::class.java)
        })
        n.notify(project)
    }

    companion object {
        fun getInstance(): ConnectionStatus = service()
    }
}

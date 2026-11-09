package dev.kmpcoach.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import dev.kmpcoach.core.AccountBalance
import dev.kmpcoach.core.AiUsage
import dev.kmpcoach.core.Json
import dev.kmpcoach.core.RateLimits
import dev.kmpcoach.core.UsageKind
import dev.kmpcoach.core.UsageMath
import dev.kmpcoach.core.UsageStats
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Consumo de la API de IA: llamadas y tokens por tipo (revisión manual, en vivo, chat), en esta sesión del IDE
 * y acumulado desde la última vez que lo reiniciaste. También guarda los límites de la última respuesta y el saldo, si el proveedor lo expone.
 */
@Service(Service.Level.APP)
@State(name = "KmpInterviewCoachUsage", storages = [Storage("kmpInterviewCoachUsage.xml")])
class UsageTracker : SimplePersistentStateComponent<UsageTracker.Totals>(Totals()) {

    class Totals : BaseState() {
        var since by string(LocalDate.now().toString())
        /** JSON {kind: UsageStats} */
        var byKind by string("{}")
    }

    /** Detalle de la última llamada, para mostrar cuánto costó exactamente. */
    data class LastCall(
        val kind: UsageKind,
        val fileName: String?,
        val usage: AiUsage,
        val costUsd: Double?,
        val estimated: Boolean,
        val modelLabel: String,
        val at: Long,
    )

    private val session = ConcurrentHashMap<UsageKind, UsageStats>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var lastCall: LastCall? = null
        private set
    @Volatile var limits: RateLimits? = null
        private set
    @Volatile var limitsProvider: String? = null
        private set
    @Volatile var balance: AccountBalance? = null
        private set
    @Volatile var balanceError: String? = null
        private set
    @Volatile var balanceAt: Long = 0
        private set
    @Volatile private var balanceProvider: String? = null

    fun addListener(parent: Disposable, l: () -> Unit) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    private fun fire() = listeners.forEach { it() }

    fun session(kind: UsageKind) = session[kind] ?: UsageStats()
    fun sessionTotal() = session.values.fold(UsageStats()) { a, b -> a + b }

    fun total(kind: UsageKind): UsageStats = totals()[kind] ?: UsageStats()
    fun totalAll() = totals().values.fold(UsageStats()) { a, b -> a + b }
    val since: String get() = state.since.orEmpty()

    private fun totals(): Map<UsageKind, UsageStats> = runCatching {
        Json.parse(state.byKind ?: "{}").let { it as? Map<*, *> ?: emptyMap<Any, Any>() }
            .mapNotNull { (k, v) -> UsageKind.of(k as? String)?.let { kind -> kind to UsageStats.fromMap((v as? Map<*, *>).orEmpty().mapKeys { it.key.toString() }) } }
            .toMap()
    }.getOrDefault(emptyMap())

    /**
     * Registra una llamada. Si el proveedor no devolvió `usage`, se estima con el largo del texto enviado y recibido.
     * El costo usa el que informa el proveedor o, si no, el precio que configuraste para este modelo.
     */
    @Synchronized
    fun record(kind: UsageKind, fileName: String?, usage: AiUsage?, sentChars: String, reply: String, limits: RateLimits?) {
        val settings = CoachSettings.getInstance()
        val estimated = usage == null
        val u = usage ?: AiUsage(UsageMath.estimateTokens(sentChars), UsageMath.estimateTokens(reply))
        val cost = u.costUsd ?: settings.price()?.cost(u.inputTokens, u.outputTokens)
        val one = UsageStats.of(u, cost)

        session.merge(kind, one, UsageStats::plus)
        val all = totals().toMutableMap()
        all.merge(kind, one, UsageStats::plus)
        state.byKind = Json.write(all.mapKeys { it.key.id }.mapValues { it.value.toMap() })

        lastCall = LastCall(kind, fileName, u, cost, estimated, settings.modelLabel(), System.currentTimeMillis())
        if (limits != null) {
            this.limits = limits
            limitsProvider = settings.preset.id
        }
        fire()
    }

    fun setBalance(providerId: String, b: AccountBalance?, error: String?) {
        balanceProvider = providerId
        balance = b
        balanceError = error
        balanceAt = System.currentTimeMillis()
        fire()
    }

    /** Los límites y el saldo son de un proveedor concreto: al cambiar de proveedor dejan de aplicar. */
    fun limitsFor(providerId: String): RateLimits? = limits.takeIf { limitsProvider == providerId }
    fun hasBalanceFor(providerId: String) = balanceProvider == providerId

    fun resetTotals() {
        state.byKind = "{}"
        state.since = LocalDate.now().toString()
        session.clear()
        lastCall = null
        fire()
    }

    companion object {
        fun getInstance(): UsageTracker = service()
    }
}

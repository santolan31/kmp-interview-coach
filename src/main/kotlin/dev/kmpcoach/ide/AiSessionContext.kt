package dev.kmpcoach.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.kmpcoach.core.AntigravityPlans
import dev.kmpcoach.core.ClaudeSessions
import dev.kmpcoach.core.SessionTurn
import dev.kmpcoach.core.StepParser
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Contexto de la conversación del estudiante con Claude Code en este proyecto: en qué paso va y qué le pidió la IA.
 * Solo lee el final de cada sesión (las sesiones pesan megas) y recuerda lo leído mientras el archivo no cambie.
 * Las búsquedas leen disco: llamar a [find] fuera del hilo de la interfaz; el panel usa [peek].
 */
@Service(Service.Level.PROJECT)
class AiSessionContext(private val project: Project) {

    /** Sesión elegida y lo que el tutor recibe de ella. */
    class Found(val file: File, val excerpt: String, val lastRequest: String?, val automatic: Boolean)

    private class Cached(val length: Long, val modified: Long, val turns: List<SessionTurn>)

    /** El último mensaje de la IA que trae pasos («## Paso 5.1 …»), para la guía del editor. */
    class StepsSource(val file: File, val text: String, val at: String)

    /** Un plan de Antigravity: su archivo Markdown, su texto y cuándo cambió. */
    class Plan(val file: File, val text: String, val modified: Long)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val planCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private val peeked = ConcurrentHashMap<String, Found>()
    private val peekedAt = ConcurrentHashMap<String, Long>()
    private val computing = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Sesión elegida a mano en el panel; null = automática (la más reciente que habla del archivo abierto). */
    @Volatile var chosen: File? = null
        private set

    fun addListener(parent: Disposable, l: () -> Unit) {
        listeners += l
        Disposer.register(parent) { listeners -= l }
    }

    fun enabled(): Boolean = CoachSettings.getInstance().state.readAiSession

    private fun dir(): File? = project.basePath?.let {
        File(System.getProperty("user.home"), ".claude/projects/" + ClaudeSessions.projectDirName(it)).takeIf(File::isDirectory)
    }

    /** Sesiones de los últimos días, de la más reciente a la más antigua. */
    fun candidates(): List<File> {
        val limit = System.currentTimeMillis() - RECENT_MS
        return dir()?.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") && f.lastModified() >= limit }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .take(MAX_SESSIONS)
    }

    /** Mensajes del final de la sesión. Si el archivo no cambió desde la última lectura, no vuelve a leerlo. */
    fun turns(f: File): List<SessionTurn> {
        val length = f.length()
        val modified = f.lastModified()
        cache[f.path]?.takeIf { it.length == length && it.modified == modified }?.let { return it.turns }
        val turns = runCatching { ClaudeSessions.parse(tail(f, TAIL_BYTES).lineSequence()) }.getOrDefault(emptyList())
        cache[f.path] = Cached(length, modified, turns)
        return turns
    }

    private fun tail(f: File, bytes: Long): String = RandomAccessFile(f, "r").use { raf ->
        val start = (raf.length() - bytes).coerceAtLeast(0)
        raf.seek(start)
        val buf = ByteArray((raf.length() - start).toInt())
        raf.readFully(buf)
        String(buf, Charsets.UTF_8)
    }

    /**
     * Extracto de la sesión que corresponde al archivo: la elegida a mano, o la más reciente cuya IA haya mencionado
     * el archivo; si ninguna lo menciona, la más reciente. Lee disco.
     */
    fun find(fileName: String?): Found? {
        if (!enabled()) return null
        chosenPlan()?.let { p -> return Found(p.file, AntigravityPlans.excerpt(p.text), planLabel(p), false) }
        val claude = pick(fileName)
        // Si el plan más reciente que habla del archivo es de Antigravity, el Mentor lee ese.
        antigravityFirst(fileName, claude)?.let { p ->
            return Found(p.file, AntigravityPlans.excerpt(p.text), planLabel(p), true)
        }
        val (f, automatic) = claude ?: return null
        val t = turns(f)
        return ClaudeSessions.excerpt(t, fileName)?.let { Found(f, it, ClaudeSessions.lastRequest(t), automatic) }
    }

    /**
     * Planes de Antigravity de la última semana: los Markdown del «cerebro» de cada conversación (sus conversaciones
     * se guardan en binario y no se pueden leer). Del más reciente al más antiguo. Lee disco.
     */
    fun antigravityPlans(): List<Plan> {
        val home = System.getProperty("user.home")
        val limit = System.currentTimeMillis() - PLAN_RECENT_MS
        return AntigravityPlans.BRAIN_DIRS.flatMap { File(home, it).listFiles()?.toList().orEmpty() }
            .flatMap { dir -> dir.listFiles { f -> f.isFile && AntigravityPlans.isPlanFile(f.name) && f.lastModified() >= limit }.orEmpty().toList() }
            .mapNotNull { f ->
                val text = planCache[f.path]?.takeIf { it.first == f.lastModified() }?.second
                    ?: runCatching { f.readText() }.getOrNull()?.also { planCache[f.path] = f.lastModified() to it }
                    ?: return@mapNotNull null
                if (AntigravityPlans.isPlan(text)) Plan(f, text, f.lastModified()) else null
            }
            .sortedByDescending { it.modified }
    }

    /**
     * El plan de Antigravity que habla del archivo, si es más nuevo que la última vez que Claude Code lo mencionó. Si
     * elegiste una conversación de Claude a mano, manda la tuya.
     */
    private fun antigravityFirst(fileName: String?, claude: Pair<File, Boolean>?): Plan? {
        if (fileName == null || claude?.second == false) return null
        val plan = antigravityPlans().firstOrNull { AntigravityPlans.mentions(it.text, fileName) } ?: return null
        val claudeAt = claude?.first?.let { ClaudeSessions.lastMention(turns(it), fileName) }
        return plan.takeIf { claudeAt == null || iso(it.modified) > claudeAt }
    }

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()

    private fun planLabel(p: Plan) = "Antigravity · " + (AntigravityPlans.title(p.text) ?: p.file.parentFile.name)

    /** El plan de Antigravity que elegiste a mano en el panel, si elegiste uno. */
    private fun chosenPlan(): Plan? = chosen?.takeIf { it.isFile && it.extension == "md" }?.let { f ->
        runCatching { f.readText() }.getOrNull()?.takeIf(AntigravityPlans::isPlan)?.let { Plan(f, it, f.lastModified()) }
    }

    /** La sesión elegida a mano o, si no, la más reciente que habla del archivo. El booleano dice si fue automática. */
    private fun pick(fileName: String?): Pair<File, Boolean>? {
        chosen?.takeIf { it.isFile && it.extension == "jsonl" }?.let { return it to false }
        val sessions = candidates()
        val best = fileName?.let { name ->
            sessions.mapNotNull { f -> ClaudeSessions.lastMention(turns(f), name)?.let { f to it } }.maxByOrNull { it.second }?.first
        } ?: sessions.firstOrNull { turns(it).isNotEmpty() } ?: return null
        return best to true
    }

    /** El último mensaje con pasos de la sesión que corresponde al archivo. Lee disco: fuera del hilo de la interfaz. */
    fun latestSteps(fileName: String?): StepsSource? {
        if (!enabled()) return null
        chosenPlan()?.let { p -> return StepsSource(p.file, AntigravityPlans.toSteps(p.text), iso(p.modified)) }
        val claude = pick(fileName)
        // Un plan de Antigravity se traduce al formato de la guía: cada archivo que cambia es un paso.
        antigravityFirst(fileName, claude)?.let { p -> return StepsSource(p.file, AntigravityPlans.toSteps(p.text), iso(p.modified)) }
        val (f, _) = claude ?: return null
        val turn = turns(f).lastOrNull { it.role == "assistant" && StepParser.hasSteps(it.text) } ?: return null
        return StepsSource(f, turn.text, turn.at)
    }

    /** Para el panel: lo último calculado para ese archivo, y lo recalcula en segundo plano sin bloquear la interfaz. */
    fun peek(fileName: String?): Found? {
        val key = fileName ?: ""
        // Como máximo una lectura cada 10 s: el panel se refresca mientras escribes.
        val now = System.currentTimeMillis()
        val fresh = now - (peekedAt[key] ?: 0L) < PEEK_EVERY_MS
        if (enabled() && !fresh && computing.compareAndSet(false, true)) {
            peekedAt[key] = now
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val found = runCatching { find(fileName) }.getOrNull()
                    val before = peeked[key]
                    if (found == null) peeked.remove(key) else peeked[key] = found
                    if (before?.file != found?.file || before?.lastRequest != found?.lastRequest) listeners.forEach { it() }
                } finally {
                    computing.set(false)
                }
            }
        }
        return peeked[key]
    }

    /** Elige la sesión a mano (null = automática). */
    fun choose(f: File?) {
        chosen = f
        peeked.clear()
        peekedAt.clear()
        listeners.forEach { it() }
    }

    /** Etiqueta de una sesión para elegirla: su último pedido y hace cuánto se usó. */
    fun label(f: File): String {
        val mins = (System.currentTimeMillis() - f.lastModified()) / 60_000
        val ago = when {
            mins < 60 -> "hace $mins min"
            mins < 48 * 60 -> "hace ${mins / 60} h"
            else -> "hace ${mins / 1440} días"
        }
        if (f.extension == "md") return "Plan: " + (runCatching { AntigravityPlans.title(f.readText()) }.getOrNull() ?: f.parentFile.name) + " · " + ago
        return (ClaudeSessions.lastRequest(turns(f)) ?: f.nameWithoutExtension) + " · " + ago
    }

    companion object {
        private const val TAIL_BYTES = 600_000L
        private const val RECENT_MS = 3L * 24 * 3600 * 1000
        /** Un plan dura más que una conversación: se sigue días después de escribirlo. */
        private const val PLAN_RECENT_MS = 7L * 24 * 3600 * 1000
        private const val MAX_SESSIONS = 8
        private const val PEEK_EVERY_MS = 10_000L
        fun getInstance(project: Project): AiSessionContext = project.service()
    }
}

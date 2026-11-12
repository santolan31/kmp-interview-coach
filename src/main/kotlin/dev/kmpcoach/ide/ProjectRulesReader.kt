package dev.kmpcoach.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.kmpcoach.core.ProjectRules
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lee las reglas del proyecto (`AGENTS.md`, o `CLAUDE.md` si no hay) desde la raíz. Guarda lo leído y solo vuelve a
 * leer el archivo si cambió su fecha de modificación.
 */
@Service(Service.Level.PROJECT)
class ProjectRulesReader(private val project: Project) {
    private class Cached(val path: String, val modified: Long, val value: ProjectRules.Found?)

    @Volatile private var cached: Cached? = null
    private val loading = AtomicBoolean(false)

    /** Las reglas, leyendo el disco si hace falta. Llamar fuera del hilo de la interfaz. */
    fun read(): ProjectRules.Found? {
        val base = project.basePath ?: return null
        val file = CANDIDATES.map { File(base, it) }.firstOrNull { it.isFile && hasRules(it) } ?: return null.also { cached = null }
        val c = cached
        if (c != null && c.path == file.path && c.modified == file.lastModified()) return c.value
        val text = runCatching { file.readText() }.getOrNull()
        val value = text?.let(ProjectRules::extract)?.let { ProjectRules.Found(file.name, it) }
        cached = Cached(file.path, file.lastModified(), value)
        return value
    }

    /** Lo último leído, sin tocar el disco (para el panel); lo actualiza en segundo plano. */
    fun peek(): ProjectRules.Found? {
        if (loading.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try { runCatching { read() } } finally { loading.set(false) }
            }
        }
        return cached?.value
    }

    /** Un `CLAUDE.md` que solo dice `@AGENTS.md` no trae reglas propias: se usa el otro. */
    private fun hasRules(f: File): Boolean =
        runCatching { f.readLines().any { it.isNotBlank() && !it.trim().startsWith("@") } }.getOrDefault(false)

    companion object {
        private val CANDIDATES = listOf("AGENTS.md", "CLAUDE.md")
        fun getInstance(project: Project): ProjectRulesReader = project.service()
    }
}

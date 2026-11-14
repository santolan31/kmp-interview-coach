package dev.kmpcoach.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.kmpcoach.core.nfc
import java.util.concurrent.ConcurrentHashMap

/**
 * Encuentra en el proyecto un archivo que la IA nombró sin ruta completa («`HomeViewModel.kt`» o
 * «home/presentation/HomeViewModel.kt»), para la guía de pasos flexible. Devuelve null si no existe, si hay varios con
 * ese nombre y la mención no dice cuál (mejor ningún archivo que el equivocado), o si el IDE está indexando.
 * Recuerda lo encontrado; lo que no encontró mientras indexaba lo vuelve a buscar después.
 */
class ProjectFileFinder(private val project: Project) : (String) -> String? {

    private val found = ConcurrentHashMap<String, String>()
    /** Lo que no se encontró y cuándo: se vuelve a buscar al rato, por si creaste el archivo después. */
    private val missed = ConcurrentHashMap<String, Long>()

    override fun invoke(mention: String): String? {
        found[mention]?.let { return it }
        if (project.isDisposed || DumbService.isDumb(project)) return null
        missed[mention]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return null }
        return search(mention).also { if (it == null) missed[mention] = System.currentTimeMillis() else missed.remove(mention) }
    }

    private fun search(mention: String): String? {
        val clean = nfc(mention.trim().removePrefix("./").trimStart('/'))
        val name = clean.substringAfterLast('/')
        if (name.isEmpty()) return null
        val files = runCatching {
            ReadAction.compute<List<String>, RuntimeException> {
                FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project)).map { nfc(it.path) }
            }
        }.getOrNull() ?: return null
        val matches = if ('/' in clean) files.filter { it.endsWith("/$clean") } else files
        return matches.singleOrNull()?.also { found[mention] = it }
    }

    /** El IDE está indexando: lo que no se encontró puede aparecer después. */
    fun isIndexing(): Boolean = DumbService.isDumb(project)

    private companion object {
        const val RETRY_MS = 60_000L
    }
}

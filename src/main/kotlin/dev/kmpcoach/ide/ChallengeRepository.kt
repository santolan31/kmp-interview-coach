package dev.kmpcoach.ide

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.Language
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import dev.kmpcoach.core.Challenge
import dev.kmpcoach.core.ChallengeParser
import dev.kmpcoach.core.Level
import java.io.File

/**
 * Retos disponibles: el paquete incluido en el plugin más los paquetes del proyecto (archivos `.json` en `.coach/challenges/`).
 * Así cualquiera puede escribir sus propios retos sin tocar el plugin.
 */
@Service(Service.Level.PROJECT)
class ChallengeRepository(private val project: Project) {

    @Volatile private var cache: List<Challenge>? = null
    @Volatile var warnings: List<String> = emptyList()
        private set

    fun all(): List<Challenge> = cache ?: load().also { cache = it }

    fun byLevel(level: Level) = all().filter { it.level == level }

    fun byId(id: String?): Challenge? = id?.let { k -> all().lastOrNull { it.id == k } }

    /** Reto al que pertenece un archivo, según el marcador `// coach:<id>` de su primera línea. */
    fun challengeFor(text: CharSequence): Challenge? = byId(ChallengeParser.markerId(text.take(400).toString()))

    fun reload(): List<String> {
        cache = null
        all()
        return warnings
    }

    private fun load(): List<Challenge> {
        val w = ArrayList<String>()
        val list = ArrayList<Challenge>()
        javaClass.getResourceAsStream("/coach/challenges.json")?.bufferedReader(Charsets.UTF_8)?.use {
            list += ChallengeParser.parsePack(it.readText(), "challenges.json (incluido)", w)
        }
        project.basePath?.let { File(it, ".coach/challenges") }?.takeIf { it.isDirectory }
            ?.listFiles { f -> f.extension.equals("json", true) }?.sortedBy { it.name }
            ?.forEach { f ->
                try { list += ChallengeParser.parsePack(f.readText(Charsets.UTF_8), ".coach/challenges/${f.name}", w) }
                catch (e: Exception) { w += "${f.name}: ${e.message}" }
            }
        if (w.isNotEmpty()) thisLogger().warn("KMP Coach: ${w.joinToString("; ")}")
        warnings = w
        // Un reto del proyecto con el mismo id reemplaza al incluido.
        return list.asReversed().distinctBy { it.id }.asReversed()
    }

    companion object {
        fun getInstance(project: Project): ChallengeRepository = project.service()
    }
}

object ChallengeFiles {

    /** Crea el archivo del reto la primera vez (o lo reabre con tu código) como archivo de borrador del IDE y lo abre en el editor. */
    fun open(project: Project, challenge: Challenge) {
        val kotlin = Language.findLanguageByID("kotlin")
        val text = challenge.header + "\n" + challenge.starter.trimEnd() + "\n"
        val name = "coach_${challenge.id}.kt"
        val root = ScratchRootType.getInstance()
        // Si ya existe, se reabre tal cual: create_if_missing sobrescribiría el código del usuario.
        // Buscar en el sistema de archivos del IDE (VFS) exige una read action, aunque estemos en el hilo de UI.
        val existing = runCatching {
            runReadAction { ScratchFileService.getInstance().findFile(root, name, ScratchFileService.Option.existing_only) }
        }.getOrNull()
        val file = existing ?: root.createScratchFile(project, name, kotlin, text, ScratchFileService.Option.create_new_always) ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /** Abre la solución de referencia en una pestaña de solo lectura. */
    fun openSolution(project: Project, challenge: Challenge) {
        val type = FileTypeManager.getInstance().getFileTypeByExtension("kt")
        val text = "// Solución de referencia · ${challenge.title} (solo lectura)\n\n" + challenge.solution.trimEnd() +
            "\n\n/*\nLo que evalúa el entrevistador:\n" + challenge.notes.joinToString("\n") { "- " + it.replace("`", "") } + "\n*/\n"
        val file = LightVirtualFile("solucion_${challenge.id}.kt", type, text).apply { isWritable = false }
        FileEditorManager.getInstance(project).openFile(file, true)
    }
}

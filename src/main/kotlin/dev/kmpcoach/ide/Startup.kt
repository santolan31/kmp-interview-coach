package dev.kmpcoach.ide

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile

/** Activa el modo en vivo (escucha de cambios en los documentos) al abrir el proyecto. */
class CoachStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        TutorService.getInstance(project).start()
    }
}

/** Si abres de nuevo un archivo revisado, vuelve a dibujar los comentarios del tutor en el editor nuevo. */
class InlayRestoreListener(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val tutor = TutorService.getInstance(project)
        if (tutor.reviewFor(file) != null) tutor.refreshInlays(file)
    }
}

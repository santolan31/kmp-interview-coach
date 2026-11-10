package dev.kmpcoach.ide

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Al abrir el proyecto: activa el modo en vivo, empieza a seguir el plan y el código nuevo según git,
 * y ofrece el repaso del día si toca.
 */
class CoachStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        TutorService.getInstance(project).start()
        ProjectContext.getInstance(project).start()
        withContext(Dispatchers.EDT) { LearningService.getInstance(project).offerDailyReview() }
    }
}

/** Si abres de nuevo un archivo con comentarios o pistas del tutor, los vuelve a dibujar en el editor nuevo. */
class InlayRestoreListener(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val tutor = TutorService.getInstance(project)
        if (tutor.reviewFor(file) != null || tutor.hintSession(file) != null) tutor.refreshInlays(file)
    }
}

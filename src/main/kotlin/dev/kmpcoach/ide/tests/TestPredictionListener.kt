package dev.kmpcoach.ide.tests

import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.project.Project
import dev.kmpcoach.ide.LearningService

/**
 * Escucha el inicio y el fin de cualquier ejecución de tests (JUnit, Gradle…) para la predicción.
 * Es la única clase que usa el test runner del IDE: desde la versión 2026 vive en un plugin aparte
 * (`intellij.testRunner.plugin`), así que se registra con una dependencia opcional en `kmpcoach-tests.xml`.
 */
class TestPredictionListener(private val project: Project) : SMTRunnerEventsAdapter() {
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
        LearningService.getInstance(project).onTestsStarted()
    }

    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
        val leaves = testsRoot.allTests.filter { it.isLeaf }
        LearningService.getInstance(project).onTestsFinished(leaves.filter { it.isDefect }.map { it.name }, leaves.size)
    }
}

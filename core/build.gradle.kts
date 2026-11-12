import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

/*
 * Lógica del Mentor sin interfaz: pasos de la guía, TODO, prompts, revisión, pistas, sesiones de otras IA, clientes de
 * IA y consumo. Compila a dos destinos:
 * - jvm: lo usa el plugin de Android Studio / IntelliJ.
 * - js (Node): lo usa la extensión de VS Code (también Cursor y Antigravity).
 * Lo que depende de la plataforma (HTTP, procesos, fecha de hoy) va en jvmMain / jsMain con expect/actual.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

group = "dev.kmpcoach"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
        // JUnit 5: con JUnit 4, kotlin.test.Test sería org.junit.Test y chocaría con el puente de los tests (org.junit.Assert).
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    js(IR) {
        outputModuleName.set("mentor-core")
        // CommonJS: el formato de módulos que carga una extensión de VS Code (se empaqueta con esbuild).
        useCommonJs()
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
    }
    compilerOptions {
        // Igual que el plugin: no usar APIs de Kotlin más nuevas que la del IDE más antiguo soportado.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
    sourceSets {
        commonMain.dependencies {
            // gradle.properties apaga la biblioteca estándar automática (el IDE trae la suya); aquí sí hace falta.
            implementation(kotlin("stdlib"))
        }
        jvmMain.dependencies {
            // Solo para compilar: en el IDE las corrutinas ya vienen incluidas.
            compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        jsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}

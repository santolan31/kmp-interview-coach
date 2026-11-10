package dev.kmpcoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTest {

    @Test
    fun planWithCheckboxes() {
        val md = """
            # Favoritos en el Home

            ## Datos
            - [x] Crear `FavoriteEntity` y su DAO
            - [/] Repositorio de favoritos
            ## UI
            - [ ] Botón de corazón en la **tarjeta**
            ```
            - [ ] esto es código, no un paso
            ```
        """.trimIndent()
        val plan = PlanParser.parse(md, "plan.md")
        assertEquals("Favoritos en el Home", plan.title)
        assertEquals(3, plan.steps.size)
        assertEquals(1, plan.doneCount)
        assertEquals("Repositorio de favoritos", plan.firstPending?.text)
        assertEquals("Datos", plan.firstPending?.section)
        assertEquals("Botón de corazón en la tarjeta", plan.steps[2].text)
        assertEquals("Plan «Favoritos en el Home», paso 2 de 3: Repositorio de favoritos (sección: Datos)", PlanParser.goal(plan, plan.steps[1]))
    }

    @Test
    fun planWithBacktickCheckboxesNumbersOrHeadings() {
        assertEquals(listOf(false, true), PlanParser.parse("- `[ ]` uno\n- `[x]` dos", "p").steps.map { it.done })
        assertEquals(listOf("Modelo", "Pantalla"), PlanParser.parse("1. Modelo\n   detalle\n2. Pantalla", "p").steps.map { it.text })
        val headings = PlanParser.parse("# T\n## Paso A\ntexto\n### Paso B", "p")
        assertEquals(listOf("Paso A", "Paso B"), headings.steps.map { it.text })
        assertEquals("p", PlanParser.parse("sin pasos", "p").title)
    }

    @Test
    fun parseUnifiedDiffWithoutContext() {
        val diff = """
            diff --git a/app/Home.kt b/app/Home.kt
            index 1..2 100644
            --- a/app/Home.kt
            +++ b/app/Home.kt
            @@ -3,0 +4,2 @@ fun a() {
            +    val x = 1
            +    val y = 2
            @@ -10 +12 @@
            -    old()
            +    new()
            diff --git a/gone.kt b/gone.kt
            --- a/gone.kt
            +++ /dev/null
            @@ -1 +0,0 @@
            -bye
        """.trimIndent()
        assertEquals(listOf(FileDiff("app/Home.kt", listOf(4, 5, 12))), DiffParser.parse(diff))
    }

    @Test
    fun blocksMergeSmallGapsAndSkipTrivialCode() {
        val file = listOf(
            "import a.B",          // 1
            "",                    // 2
            "fun total(items: List<Item>): Int {", // 3
            "    val sum = items.sumOf { it.price }", // 4
            "    log(sum)",        // 5
            "    // comentario",   // 6
            "    return sum",      // 7
            "}",                   // 8
            "",                    // 9
            "val a = 1",           // 10
        )
        val blocks = Blocks.fromAdded("F.kt", listOf(1, 3, 4, 5, 7, 8, 10), file)
        assertEquals(1, blocks.size)
        assertEquals(1, blocks[0].startLine)
        assertEquals(10, blocks[0].endLine)
        assertTrue(Blocks.fromAdded("F.kt", listOf(1, 6, 8), file).isEmpty())
    }

    @Test
    fun longBlocksAreSplitAtBlankLines() {
        val file = (1..80).map { if (it % 20 == 0) "" else "val v$it = $it" }
        val blocks = Blocks.fromAdded("F.kt", (1..80).toList(), file, target = 25, max = 60)
        assertEquals(listOf(1..40, 41..80), blocks.map { it.startLine..it.endLine })
    }

    @Test
    fun locateBlockAfterUnsavedEditsAndFingerprintIgnoresIndentation() {
        val block = CodeBlock("F.kt", 2, 3, "val a = 1\nval b = 2")
        assertEquals(4, Blocks.locate(block, listOf("x", "y", "z", "  val a = 1", "  val b = 2")))
        assertNull(Blocks.locate(block, listOf("val a = 1", "val b = 3")))
        assertEquals(block.id, CodeBlock("G.kt", 9, 10, "    val a = 1\n\n    val b = 2").id)
    }

    @Test
    fun compareGuessedFiles() {
        val changed = listOf("feature/home/HomeViewModel.kt", "feature/home/components/FavoriteButton.kt", "core/data/FavoriteDao.kt")
        val r = FileGuess.compare("Creo que el viewmodel del home, el FavoriteDao.kt y la HomeScreen", changed)
        assertEquals(listOf("feature/home/HomeViewModel.kt", "core/data/FavoriteDao.kt"), r.hits)
        assertEquals(listOf("feature/home/components/FavoriteButton.kt"), r.missed)
        assertEquals(listOf("HomeScreen"), r.wrong)
    }
}

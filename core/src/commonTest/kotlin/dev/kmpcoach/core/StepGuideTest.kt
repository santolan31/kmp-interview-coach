package dev.kmpcoach.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepGuideTest {

    // Imita el mensaje real del paso 5: títulos «## Paso», enlace con líneas antes de cada diff y «Cómo verificarlo».
    private val message = listOf(
        "Aquí va el esqueleto del paso 5.",
        "## El concepto antes de empezar",
        "Hoy la campana parece funcionar.",
        "## Paso 5.1: el componente acepta un callback nulo",
        "[MacoTopBar.kt:1-6](file:///p/MacoTopBar.kt#L1-L6)",
        "```diff",
        " fun MacoTopBar(",
        "     selectedAddress: String,",
        "     onAddressClick: () -> Unit,",
        "-    onNotificationClick: () -> Unit,",
        "+    // TODO 1: que el tipo permita null",
        "+    onNotificationClick: TODO,",
        "     modifier: Modifier = Modifier,",
        " ) {",
        "```",
        "[MacoTopBar.kt:8-13](file:///p/MacoTopBar.kt#L8-L13)",
        "```diff",
        "+        // TODO 2: una variable que diga si la campana está activa",
        "+        val isEnabled = TODO()",
        "+",
        "         Icon(",
        "             imageVector = Icons.Default.Notifications,",
        "-            tint = MaterialTheme.colorScheme.extended.textPlaceholder,",
        "+            // TODO 3: el color según si está activa",
        "+            tint = TODO(),",
        "             modifier = Modifier",
        "                 .size(16.dp)",
        "-                .clickable(onClick = onNotificationClick)",
        "+                // TODO 4: clickable con enabled",
        "+                .clickable(TODO())",
        "         )",
        "```",
        "**Cómo verificarlo:** compila. El Home sigue compilando.",
        "",
        "## Paso 5.3: el Home dice que no hay destino",
        "[HomeScreen.kt:115-119](core/HomeScreen.kt#L115-L119)",
        "```diff",
        "-    onNotificationClick = { onAction(X) }",
        "+    // TODO 6: ¿qué le pasas?",
        "+    onNotificationClick = TODO",
        "```",
        "## Paso 5.4: borrar la acción que ya nadie usa",
        "- [HomeAction.kt:8](file:///p/HomeAction.kt#L8)",
        "```diff",
        "-    data object NotificationClicked: HomeAction",
        "```",
    ).joinToString("\n")

    private val original = listOf(
        "fun MacoTopBar(",
        "    selectedAddress: String,",
        "    onAddressClick: () -> Unit,",
        "    onNotificationClick: () -> Unit,",
        "    modifier: Modifier = Modifier,",
        ") {",
        "    Row {",
        "        Icon(",
        "            imageVector = Icons.Default.Notifications,",
        "            tint = MaterialTheme.colorScheme.extended.textPlaceholder,",
        "            modifier = Modifier",
        "                .size(16.dp)",
        "                .clickable(onClick = onNotificationClick)",
        "        )",
        "    }",
        "}",
    )

    private fun apply(lines: List<String>, r: Replacement) = lines.subList(0, r.from) + r.lines + lines.subList(r.to + 1, lines.size)

    private val steps = StepParser.parse(message, "/base")

    @Test
    fun linksWithEncodedAccentsPointToTheRealFile() {
        // Antigravity y Copilot escriben enlaces file:// con las tildes codificadas (%C3%B3) y a veces en NFD (o + %CC%81).
        val md = listOf(
            "## Paso 1: tilde codificada",
            "[Factura.kt](file:///Users/s/Documents/Facturaci%C3%B3napp/Mi%20App/Factura.kt#L3)",
            "```diff", "+val a = 1", "```",
            "## Paso 2: tilde separada (NFD)",
            "[Factura.kt](file:///Users/s/Documents/Facturacio%CC%81napp/Factura.kt)",
            "```diff", "+val b = 2", "```",
        ).joinToString("\n")
        val steps = StepParser.parse(md, "/Users/s/Documents/Facturaciónapp")
        assertEquals("/Users/s/Documents/Facturaciónapp/Mi App/Factura.kt", steps[0].edits[0].path)
        assertEquals(3, steps[0].edits[0].line)
        assertEquals("las dos formas de la tilde quedan iguales (NFC)", "/Users/s/Documents/Facturaciónapp/Factura.kt", steps[1].edits[0].path)
    }

    @Test
    fun parsesStepsFilesAndChecks() {
        assertTrue(StepParser.hasSteps(message))
        assertEquals(listOf("5.1", "5.3", "5.4"), steps.map { it.number })
        val s1 = steps[0]
        assertEquals("el componente acepta un callback nulo", s1.title)
        assertEquals(listOf("/p/MacoTopBar.kt", "/p/MacoTopBar.kt"), s1.edits.map { it.path })
        assertEquals(listOf(1, 8), s1.edits.map { it.line })
        assertEquals(listOf("TODO 2", "TODO 3", "TODO 4"), s1.edits[1].todos.map { it.first })
        assertTrue(s1.verify!!.startsWith("compila"))
        assertEquals("/base/core/HomeScreen.kt", steps[1].edits.single().path)
        assertTrue(steps[2].edits.single().isDeletion)
    }

    @Test
    fun placesTheSkeletonAndTracksTodos() {
        val edit = steps[0].edits[0]
        val untouched = StepMatcher.status(original, edit, hasErrors = false, seenPlaced = false)
        assertTrue(untouched.canPlaceSkeleton)
        assertFalse(untouched.complete)
        assertEquals("la línea que reemplaza el TODO 1 sigue igual", TodoState.MISSING, untouched.todos.single().state)
        assertEquals(3, untouched.todos.single().line)

        val withSkeleton = apply(original, StepMatcher.placeSkeleton(original, edit)!!)
        assertEquals("    // TODO 1: que el tipo permita null", withSkeleton[3])
        val placed = StepMatcher.status(withSkeleton, edit, hasErrors = true, seenPlaced = false)
        assertTrue(placed.skeletonPlaced)
        assertFalse(placed.canPlaceSkeleton)
        assertEquals(TodoState.PENDING, placed.todos.single().state)
        assertEquals("el cursor queda donde va el código del TODO", 4, placed.cursorLine)
        assertNull("no se vuelve a poner", StepMatcher.placeSkeleton(withSkeleton, edit))

        val solved = withSkeleton.filterNot { "TODO 1" in it }.map { if ("onNotificationClick: TODO" in it) "    onNotificationClick: (() -> Unit)?," else it }
        assertTrue(StepMatcher.status(solved, edit, hasErrors = false, seenPlaced = true).complete)
        assertEquals(TodoState.DONE, StepMatcher.status(solved, edit, hasErrors = false, seenPlaced = true).todos.single().state)
        assertFalse("si no compila, no está completo", StepMatcher.status(solved, edit, hasErrors = true, seenPlaced = true).complete)
    }

    @Test
    fun skeletonInsideTheBodyKeepsTheRestOfTheFile() {
        val edit = steps[0].edits[1]
        val r = StepMatcher.placeSkeleton(original, edit)
        assertNotNull(r)
        val after = apply(original, r!!)
        assertTrue(after.any { "TODO 4" in it })
        assertEquals("    Row {", after[6])
        assertEquals("}", after.last())
    }

    @Test
    fun cursorFollowsTheCodeEvenAfterItChanged() {
        val edited = original.toMutableList().apply { this[3] = "    onNotificationClick: (() -> Unit)?," } + listOf("", "// más código")
        val moved = listOf("import a.b.C", "") + edited
        assertEquals(5, StepMatcher.cursorLine(moved, steps[0].edits[0]))
    }

    @Test
    fun editedByHandWithoutSkeletonIsNotCompleteWhileAReplacedLineRemains() {
        // Tu caso real: cambiaste el clickable a mano, pero el tint (TODO 3) sigue igual.
        val edit = steps[0].edits[1]
        val partial = original.map { if (".clickable(" in it) "                .clickable(onClick = onNotificationClick ?: {})" else it }
        val st = StepMatcher.status(partial, edit, hasErrors = false, seenPlaced = false)
        assertFalse(st.complete)
        assertEquals(
            listOf("TODO 2" to TodoState.MISSING, "TODO 3" to TodoState.MISSING, "TODO 4" to TodoState.DONE),
            st.todos.map { it.label to it.state },
        )
        assertEquals("el TODO 2 va justo antes de Icon(", 7, st.todos[0].line)
        assertEquals("el TODO 3 es la línea del tint que sigue igual", 9, st.todos[1].line)
        assertEquals("«Ir a lo que falta» lleva al primero", 7, st.cursorLine)

        val tintDone = partial.map { if ("tint =" in it) "            tint = if (isEnabled) primary else disabled," else it }
        assertFalse("falta crear la variable del TODO 2", StepMatcher.status(tintDone, edit, hasErrors = false, seenPlaced = false).complete)
        val done = tintDone.toMutableList().apply { add(7, "        val isEnabled = onNotificationClick != null") }
        val all = StepMatcher.status(done, edit, hasErrors = false, seenPlaced = false)
        assertTrue(all.complete)
        assertTrue(all.todos.all { it.state == TodoState.DONE })
    }

    // Tu caso real de MacoTopBar: la flecha de la dirección tiene el mismo `tint = …textPlaceholder` que la campana.
    private val withArrow = listOf(
        "fun MacoTopBar(",                                                     // 0
        "    selectedAddress: String,",
        "    onAddressClick: () -> Unit,",
        "    onNotificationClick: () -> Unit,",
        "    modifier: Modifier = Modifier,",
        ") {",                                                                 // 5
        "    Row {",
        "        Icon(",
        "            imageVector = Icons.Default.ArrowDropDown,",
        "            tint = MaterialTheme.colorScheme.extended.textPlaceholder,",  // 9: la flecha, no es del paso
        "            modifier = Modifier.size(16.dp)",                         // 10
        "        )",
        "        Icon(",
        "            imageVector = Icons.Default.Notifications,",
        "            tint = MaterialTheme.colorScheme.extended.textPlaceholder,",  // 14: la campana
        "            modifier = Modifier",                                     // 15
        "                .size(16.dp)",
        "                .clickable(onClick = onNotificationClick)",
        "        )",
        "    }",
        "}",                                                                   // 20
    )

    @Test
    fun aRepeatedLineElsewhereIsNotTheTodo() {
        val edit = steps[0].edits[1]
        val clickDone = withArrow.map { if (".clickable(" in it) "                .clickable(enabled = isEnabled, onClick = { onNotificationClick?.invoke() })" else it }
            .toMutableList().apply { add(12, "        val isEnabled = onNotificationClick != null") }
        // La campana (ahora línea 15) sigue igual: falta el TODO 3 ahí, no en la flecha (línea 9).
        val pending = StepMatcher.status(clickDone, edit, hasErrors = false, seenPlaced = false)
        assertEquals(TodoState.MISSING, pending.todos[1].state)
        assertEquals(15, pending.todos[1].line)
        assertFalse(pending.complete)

        // Cambias el tint de la campana: completo, aunque la flecha siga con la misma línea.
        val done = clickDone.mapIndexed { i, l -> if (i == 15) "            tint = if (isEnabled) a else b," else l }
        val st = StepMatcher.status(done, edit, hasErrors = false, seenPlaced = false)
        assertTrue(st.todos.all { it.state == TodoState.DONE })
        assertTrue(st.complete)

        // Las líneas del paso: la variable, el tint y el clickable de la campana, nunca la flecha.
        val covered = StepMatcher.covered(done, edit)
        assertTrue(covered.containsAll(listOf(12, 15, 18)))
        assertFalse(9 in covered)
    }

    @Test
    fun deletionIsCompleteWhenTheLineIsGone() {
        val edit = steps[2].edits.single()
        val withLine = listOf("sealed interface HomeAction {", "    data object NotificationClicked: HomeAction", "}")
        assertFalse(StepMatcher.status(withLine, edit, hasErrors = false, seenPlaced = false).complete)
        assertTrue(StepMatcher.status(listOf("sealed interface HomeAction {", "}"), edit, hasErrors = false, seenPlaced = false).complete)
    }

    // Como en el plan real: el ```diff va dentro de una lista, con sangría. La sangría es del bloque, no del diff.
    @Test
    fun diffInsideAListItemKeepsItsKinds() {
        val md = listOf(
            "## Paso 5.4: borrar la acción que ya nadie usa",
            "- [HomeAction.kt:8](file:///p/HomeAction.kt#L8)",
            "  ```diff",
            "  -    data object NotificationClicked: HomeAction",
            "  ```",
            "- [HomeViewModel.kt:207-209](file:///p/HomeViewModel.kt#L207-L209)",
            "  ```diff",
            "  -            HomeAction.NotificationClicked -> {",
            "  -                // Notificaciones",
            "  -            }",
            "  ```",
        ).joinToString("\n")
        val edits = StepParser.parse(md, null).single().edits
        assertEquals(listOf("HomeAction.kt", "HomeViewModel.kt"), edits.map { it.fileName })
        assertTrue(edits.all { it.isDeletion })
        assertEquals("    data object NotificationClicked: HomeAction", edits[0].removed.single())
        assertEquals("            HomeAction.NotificationClicked -> {", edits[1].removed.first())
    }

    // Un paso que solo borra no tiene TODO: lo que falta es lo que sigue escrito, con su línea para llevarte ahí.
    @Test
    fun deletionLeftSaysWhatToDeleteAndWhere() {
        val edit = steps[2].edits.single()
        val withLine = listOf("sealed interface HomeAction {", "    data object NotificationClicked: HomeAction", "}")
        val st = StepMatcher.status(withLine, edit, hasErrors = false, seenPlaced = false)
        val mark = st.todos.single()
        assertEquals(StepMatcher.DELETE_LABEL, mark.label)
        assertEquals("data object NotificationClicked: HomeAction", mark.text)
        assertEquals(TodoState.MISSING, mark.state)
        assertEquals(1, mark.line)
        assertEquals(1, st.cursorLine)
        assertTrue(StepMatcher.status(listOf("sealed interface HomeAction {", "}"), edit, hasErrors = false, seenPlaced = false).todos.isEmpty())
    }

    private val branch = StepEdit("/p/HomeViewModel.kt", 5, listOf(
        DiffLine('-', "            HomeAction.NotificationClicked -> {"),
        DiffLine('-', "                // Notificaciones"),
        DiffLine('-', "            }"),
    ))

    private fun viewModel(vararg branchLines: String) = listOf(
        "fun onAction(action: HomeAction) {",
        "    when (action) {",
        "            HomeAction.AddressClicked -> {",
        "            }",
    ) + branchLines + listOf("    }", "}")

    @Test
    fun halfDeletedBlockIsNotComplete() {
        // Borraste la primera línea de la rama, pero el comentario sigue ahí.
        val half = viewModel("                // Notificaciones", "            }")
        val st = StepMatcher.status(half, branch, hasErrors = false, seenPlaced = false)
        assertFalse(st.complete)
        assertEquals(4, st.todos.single().line)
        assertEquals("// Notificaciones", st.todos.single().text)

        val whole = viewModel("            HomeAction.NotificationClicked -> {", "                // Notificaciones", "            }")
        assertEquals("señala la línea con contenido, no la llave", 4, StepMatcher.status(whole, branch, false, false).todos.single().line)

        assertTrue(StepMatcher.status(viewModel(), branch, hasErrors = false, seenPlaced = false).complete)
    }

    // «Explícame este paso» con lo que ya hiciste: el Mentor no repite lo hecho y va a lo que falta.
    @Test
    fun explainRequestSaysWhatIsDoneAndWhatIsLeft() {
        val deleteLine = StepEdit("/p/HomeAction.kt", 8, listOf(DiffLine('-', "    data object NotificationClicked: HomeAction")))
        val step = GuideStep("5.4", "borrar la acción que ya nadie usa", listOf(deleteLine, branch), null)
        val actionDone = StepMatcher.status(listOf("sealed interface HomeAction {", "}"), deleteLine, false, false)
        val branchLeft = StepMatcher.status(viewModel("            HomeAction.NotificationClicked -> {", "                // Notificaciones", "            }"), branch, false, false)

        val text = StepPrompts.explainRequest(step, listOf(actionDone, branchLeft))
        assertTrue(text, text.contains("ya hice lo de HomeAction.kt"))
        assertTrue(text, text.contains("borrar en HomeViewModel.kt, línea 5 («HomeAction.NotificationClicked -> {»)"))
        assertTrue(text, text.endsWith("Céntrate en lo que me falta."))

        val notStarted = StepMatcher.status(listOf("sealed interface HomeAction {", "    data object NotificationClicked: HomeAction", "}"), deleteLine, false, false)
        val fresh = StepPrompts.explainRequest(step, listOf(notStarted, branchLeft))
        assertFalse("sin empezar no dice cómo vas", fresh.contains("Cómo voy"))
    }

    @Test
    fun theSameLineFarAwayIsNotTheDeletion() {
        // Otro «// Notificaciones» 60 líneas más abajo no es el de la rama que borraste.
        val far = viewModel() + List(60) { "" } + listOf("    // Notificaciones")
        assertTrue(StepMatcher.status(far, branch, hasErrors = false, seenPlaced = false).complete)
    }

    @Test
    fun parsesTheTutorVerdict() {
        assertEquals(StepVerdict(true, emptyList()), StepPrompts.parseVerdict("""{"cumple": true, "faltan": []}"""))
        val v = StepPrompts.parseVerdict("Claro: {\"cumple\": false, \"faltan\": [{\"archivo\": \"MacoTopBar.kt\", \"linea\": 82, \"que\": \"falta el color\"}]}")
        assertEquals(StepVerdict(false, listOf(StepGap("MacoTopBar.kt", 82, "falta el color"))), v)
        assertEquals("respuesta antigua", "falta algo", StepPrompts.parseVerdict("""{"cumple": false, "falta": "falta algo"}""").missing)
    }
}

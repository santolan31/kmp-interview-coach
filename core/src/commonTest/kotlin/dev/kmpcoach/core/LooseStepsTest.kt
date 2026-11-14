package dev.kmpcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guías de personas que no piden un formato: «quiero hacer esto, dime cómo». */
class LooseStepsTest {

    private val base = "/p"
    /** El proyecto falso: cada nombre de archivo tiene una sola ruta. */
    private val project = mapOf(
        "CartItem.kt" to "/p/shared/CartItem.kt",
        "CartViewModel.kt" to "/p/feature/cart/CartViewModel.kt",
    )
    private val finder: (String) -> String? = { project[it.substringAfterLast('/')] }

    private val numbered = """
        Para guardar el carrito sigue estos pasos:

        1. Crea la clase del producto en `CartItem.kt`:
           ```kotlin
           data class CartItem(val id: String, val quantity: Int)
           ```
        2. En tu `CartViewModel.kt` guarda la lista:
           ```kotlin
           private val _items = MutableStateFlow<List<CartItem>>(emptyList())
           // ... el resto de tu ViewModel
           fun add(item: CartItem) { _items.update { it + item } }
           ```
        3. Corre la app y agrega un producto.

        ¡Listo! Con esto el carrito guarda los productos.
    """.trimIndent()

    @Test
    fun aNumberedAnswerWithCodeBecomesAGuide() {
        val steps = StepParser.parse(numbered, base, finder)
        assertEquals(listOf("1", "2", "3"), steps.map { it.number })
        assertEquals("Crea la clase del producto en CartItem.kt", steps[0].title)
        val e = steps[1].edits.single()
        assertEquals("/p/feature/cart/CartViewModel.kt", e.path)
        assertTrue(e.loose)
        assertTrue(steps[2].edits.isEmpty(), "correr la app es un paso sin código: se marca «Hecho»")
        assertTrue(StepParser.isGuide(numbered, base, finder))
        assertFalse(StepParser.hasSteps(numbered), "no es el formato estricto")
    }

    @Test
    fun headingsAndPlainStepsAlsoWork() {
        val headings = "### 1. Crea la clase\n```kotlin\n// CartItem.kt\ndata class CartItem(val id: String)\n```\n### 2. Prueba la app\nCórrela."
        assertEquals("/p/shared/CartItem.kt", StepParser.parse(headings, base, finder)[0].edits.single().path, "el archivo sale del comentario")
        val plain = "**Paso 1:** crea la clase\n```kotlin title=\"CartItem.kt\"\ndata class CartItem(val id: String)\n```\n**Paso 2:** prueba la app"
        assertEquals("/p/shared/CartItem.kt", StepParser.parse(plain, base, finder)[0].edits.single().path, "el archivo sale del encabezado")
    }

    @Test
    fun anExplanationWithAListButNoCodeIsNotAGuide() {
        val md = "Hay dos formas:\n1. Usar Room, que guarda en el teléfono.\n2. Usar el servidor, que sincroniza."
        assertTrue(StepParser.parse(md, base, finder).isEmpty())
    }

    @Test
    fun codeForAnotherProjectIsNotAGuide() {
        // Por ejemplo, una conversación sobre el propio Mentor que nombra archivos de otra carpeta.
        val md = "**1. Guardar el esfuerzo**\n[AiClients.kt:46](/otro/AiClients.kt#L46)\n```diff\n-a\n+b\n```\n**2. Enviarlo**\n[CliClient.kt:80](/otro/CliClient.kt#L80)\n```diff\n-c\n+d\n```"
        assertTrue(StepParser.parse(md, base, finder).isEmpty())
    }

    @Test
    fun aReportOfWhatWasDoneIsNotAGuide() {
        // Respuestas reales: resumen de lo corregido y una revisión. Tienen lista y código, pero no dicen qué hacer.
        val report = "1. El logo cortado (corregido)\n```kotlin\n// CartItem.kt\ndata class CartItem(val id: String)\n```\n2. ¿El precio en rojo?\nLo dejo así."
        assertTrue(StepParser.parse(report, base, finder).isEmpty())
        val review = "1. 🔴 Bug en `CartItem.kt`: el id puede ser vacío\n```kotlin\ndata class CartItem(val id: String)\n```\n2. Los tests no cubren el carrito vacío."
        assertTrue(StepParser.parse(review, base, finder).isEmpty())
    }

    @Test
    fun withoutAFinderOnlyPathsCanBeLocated() {
        val md = "1. Crea la clase en `shared/CartItem.kt`\n```kotlin\ndata class CartItem(val id: String)\n```\n2. Agrega en `CartViewModel.kt`\n```kotlin\nfun add(item: CartItem) { items += item }\n```"
        val steps = StepParser.parse(md, base)
        assertEquals("/p/shared/CartItem.kt", steps[0].edits.single().path)
        assertTrue(steps[1].edits.isEmpty(), "un nombre sin ruta no se puede ubicar sin buscar en el proyecto")
    }

    @Test
    fun theProjectFormatIsReadExactlyAsBefore() {
        // Con «## Paso N», las listas numeradas dentro de un paso no lo parten.
        val md = "## Paso 1: la clase\n1. abre el archivo\n2. agrega el campo\n## Paso 2: probar\nCorre la app."
        assertEquals(listOf("1", "2"), StepParser.parse(md, base, finder).map { it.number })
    }

    @Test
    fun aLooseBlockIsDoneWhenMostOfItsCodeIsThere() {
        val edit = StepParser.parse(numbered, base, finder)[1].edits.single()
        val before = listOf("class CartViewModel {", "}")
        val s0 = StepMatcher.status(before, edit, hasErrors = false, seenPlaced = false)
        assertFalse(s0.complete)
        assertEquals(StepMatcher.ADD_LABEL, s0.todos.single().label)
        assertEquals("private val _items = MutableStateFlow<List<CartItem>>(emptyList())", s0.todos.single().text)

        val written = listOf(
            "class CartViewModel {",
            "    private val _items = MutableStateFlow<List<CartItem>>(emptyList())",
            "    fun add(item: CartItem) { _items.update { it + item } }",
            "}",
        )
        assertTrue(StepMatcher.status(written, edit, hasErrors = false, seenPlaced = false).complete, "el comentario con «...» no cuenta")
        assertFalse(StepMatcher.status(written, edit, hasErrors = true, seenPlaced = false).complete, "si no compila, no está hecho")
    }
}

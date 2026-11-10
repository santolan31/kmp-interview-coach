# KMP Interview Coach

Plugin para **Android Studio** e **IntelliJ IDEA** que te ayuda a **entender el código que escribes** (el tuyo o el que te generó una IA) mientras programas en Android y Kotlin Multiplatform. No te da el código: te guía con pistas, como en una prueba técnica. Cada persona usa **su propia API de IA**.

## Pistas en vez de soluciones

Cuando te atascas, pulsa **Dame una pista** (⌘⌥⇧J / Ctrl+Alt+Mayús+J). Cada vez que la pulsas, la pista sube un escalón:

| Escalón | Qué te da | Ejemplo (filtrar usuarios activos y adultos) |
|---|---|---|
| 1. Pregunta | Una pregunta para que descubras el problema | «`users` es una lista. ¿Qué necesitas hacer con **cada** usuario?» |
| 2. Concepto | La idea que necesitas, sin nombrar la función | «Quieres quedarte con algunos elementos y descartar otros.» |
| 3. Herramienta | La función y un ejemplo con **otros datos** | «Busca `filter`: `listOf(1, 5, 8).filter { it > 4 }` da `[5, 8]`.» |
| 4. Pasos | Los pasos en palabras, sin código | «1) quédate con los activos y adultos, 2) convierte cada uno en su nombre, 3) ordénalos.» |

- La pista aparece **debajo de la línea** a la que se refiere y en el panel.
- El tutor lee los **errores reales del IDE** (por ejemplo `Unresolved reference 'edad'`) y empieza por ellos.
- **Filtro anti-código:** si el modelo intenta darte el código de tu ejercicio, se le pide que lo reescriba; si insiste, ese fragmento se oculta.
- **Me rindo:** te deja elegir entre ver la solución **explicada línea por línea** en el panel o **copiar un mensaje listo** (archivo, errores, pistas y código) para pegarlo en tu chat con Claude.
- **Nuevo problema** reinicia la escalera. Si resuelves los errores, se reinicia sola.
- **Explícame esto:** selecciona código y usa *clic derecho › KMP Coach › Explícame esto*. El tutor te hace 2 o 3 preguntas para comprobar que lo entiendes; respondes en el chat y te dice qué está bien sin darte la respuesta.

## Aprender con IA sin quedarse atrás

La investigación con estudiantes que programan con IA ([Prather et al., 2024](https://arxiv.org/abs/2405.17739)) muestra que el riesgo no es usarla, sino la **ilusión de competencia**: creer que entiendes código que solo leíste. El plugin trabaja con cualquier IA porque no lee la conversación, sino lo que queda en tus archivos.

| Función | Qué hace | En qué se basa |
|---|---|---|
| **Pista en gris** | La pista aparece al final de la línea, como una sugerencia de autocompletado. **Tab** pide la siguiente y **Esc** la oculta. | Andamiaje que tú controlas |
| **Comprensión del código nuevo** | Un icono **?** en el margen marca cada bloque nuevo según `git diff` (lo escribieras tú o una IA) que aún no compruebas. Lo compruebas **prediciendo** qué hace con un ejemplo o **explicándolo sin mirar**: el bloque se pliega mientras lo explicas. | Trace-and-Predict y Lead-and-Reveal ([Kazemitabaar et al., 2025](https://arxiv.org/abs/2410.08922)) |
| **Plan activo** | Lee tu plan de `docs/plans/*.md` (casillas, lista numerada o títulos) y le cuenta al tutor solo el paso actual. Antes de empezar un paso te pregunta qué archivos crees que toca y luego lo compara con lo que cambió. | Metacognición: planear antes de escribir |
| **Predecir los tests** | Antes de ver el resultado de los tests te pregunta si pasarán. Si fallas, te pide tu hipótesis antes de leer el error. | PRIMM: predecir antes de ejecutar |
| **Intenta antes de otra pista** | Si pides otra pista sin haber cambiado nada, primero te pide un intento. | Abuso de ayuda en tutores inteligentes |
| **Ayuda que se retira** | Los conceptos que resuelves con poca ayuda pasan a «dominados» y sus pistas son más breves. | Efecto de inversión de la experiencia |
| **Repaso espaciado** | Lo que te costó vuelve como una pregunta de 30 segundos al día siguiente, y luego cada vez más espaciado (1, 2, 4, 8… días). | Práctica de recuperación espaciada |

Todo se activa o desactiva en **Ajustes › Herramientas › KMP Interview Coach › Aprendizaje**.

> Para que el tutor sepa qué estás haciendo, guarda en `docs/plans/` el plan que hagas con cualquier IA. No hace falta volver a contárselo.

Además, el plugin trabaja en dos capas mientras escribes:

| Capa | Cuándo corre | Qué hace | Costo |
|---|---|---|---|
| **Análisis local** | En cada cambio, como el resaltado del IDE (< 1 ms por archivo) | Subraya patrones que un entrevistador cuestionaría y te pregunta por qué: `!!`, `GlobalScope`, `Thread.sleep` en corrutinas, `catch (e: Exception)` que se traga la cancelación, estado de Compose sin `remember`, `MutableStateFlow` público, etc. En los retos, además, aplica reglas propias y marca los objetivos que ya cumples. | Gratis, sin red |
| **Tutor de IA** | Cuando lo pides (Ctrl+Alt+Mayús+K) o en vivo, al hacer una pausa | Deja comentarios **debajo de la línea exacta** (Error, Mejora, Pregunta, Bien), te dice el siguiente paso sin regalarte la solución y responde tus preguntas en un chat. | Tu clave de API |

## Proveedores de IA compatibles

En **Ajustes › Herramientas › KMP Interview Coach** eliges el proveedor, pegas tu clave y eliges el modelo (el botón **Cargar modelos** muestra los que tu clave puede usar).

| Proveedor | URL base | Clave |
|---|---|---|
| Anthropic (Claude) | `https://api.anthropic.com` | Sí |
| OpenAI | `https://api.openai.com/v1` | Sí |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | Sí |
| Groq | `https://api.groq.com/openai/v1` | Sí |
| OpenRouter | `https://openrouter.ai/api/v1` | Sí |
| Ollama (local) | `http://localhost:11434/v1` | No |
| Otro compatible con OpenAI | La que indique tu servidor (LM Studio, vLLM, LiteLLM…) | Opcional |

- La clave se guarda en el **almacén seguro del IDE** (Llavero de macOS, KWallet/libsecret o KeePass), una por proveedor. Nunca queda en archivos del proyecto.
- Tu código solo sale de tu máquina hacia el proveedor que elijas, y solo cuando pides una revisión, preguntas en el chat o activas el tutor en vivo. Con Ollama no sale de tu computador.
- Para revisiones rápidas conviene un modelo pequeño (por ejemplo `claude-haiku-5-5`, `gpt-6-luna` o `gemini-3.8-flash`). Los modelos cambian seguido: si uno deja de existir, elige otro con **Cargar modelos**.

## Instalar y recibir actualizaciones

El plugin se publica en este repositorio como un **repositorio de plugins propio**. Se configura una sola vez:

1. En Android Studio: **Settings › Plugins › ⚙ › Manage Plugin Repositories…**
2. Pulsa **+** y pega:
   ```
   https://raw.githubusercontent.com/santolan31/kmp-interview-coach/main/updatePlugins.xml
   ```
3. En la pestaña **Marketplace** busca **KMP Interview Coach** e instálalo.

Desde ahí, cuando se publique una versión nueva, Android Studio la muestra en **Settings › Plugins › Installed** con el botón **Update**, igual que cualquier plugin.

### Publicar una versión nueva

1. Sube `pluginVersion` en `gradle.properties`.
2. Haz commit de tus cambios.
3. Ejecuta `./scripts/release.sh`. Compila, corre los tests, actualiza `updatePlugins.xml` y crea el Release de GitHub con el zip.

### Compilar a mano

Necesitas JDK 21 (Android Studio ya trae uno; si Gradle no lo encuentra, el proyecto lo descarga solo).

```bash
./gradlew buildPlugin
```

El instalable queda en `build/distributions/kmp-interview-coach-<versión>.zip`. Para instalarlo sin el repositorio: **Settings › Plugins › ⚙ › Install Plugin from Disk…**

Para probarlo sin instalarlo, `./gradlew runIde` abre un IntelliJ IDEA de prueba con el plugin cargado.

Compatibilidad: Android Studio Meerkat (2024.3) en adelante e IntelliJ IDEA 2024.3+, con Kotlin en modo K1 o K2.

## Cómo se usa

1. Abre el panel **KMP Coach** (barra lateral derecha).
2. Elige nivel (Junior, Semi Senior o Senior) y reto, y pulsa **Abrir**. Se crea un archivo de borrador `coach_<id>.kt` que empieza con `// coach:<id>`; esa línea es la que le dice al plugin qué reto estás resolviendo.
3. Escribe. Los subrayados y los objetivos del panel se actualizan solos. Pasa el cursor sobre un subrayado para leer el aviso, o pulsa **Alt+Enter › Coach: preguntarle al tutor por qué**.
4. Pulsa **Revisar mi código** (o Ctrl+Alt+Mayús+K / ⌘⌥⇧K). Los comentarios aparecen debajo de cada línea y en la vista Problemas.
5. Activa **Tutor en vivo** si quieres que te acompañe sin pedirlo. Actúa cuando haces una pausa al escribir:
   - **Con errores del IDE o un `TODO()`:** si llevas un rato sin reducirlos (45 s por defecto), te da **una** pista. Si quieres más, pulsas *Otra pista*. Así no te interrumpe mientras avanzas.
   - **Sin errores:** revisa tu enfoque como *Revisar mi código*, solo si el código cambió y pasó el tiempo mínimo (12 s por defecto).
6. En el menú contextual del editor, **KMP Coach › Preguntarle al tutor sobre esto** escribe en el chat una pregunta sobre la selección.

El análisis local y el tutor también funcionan en **cualquier archivo Kotlin de tu proyecto**, no solo en los retos. Puedes desactivarlo para archivos que no sean retos en los ajustes.

Los archivos de borrador no pertenecen a un módulo, así que el IDE puede marcar referencias sin resolver (`ViewModel`, `viewModelScope`…). No afecta al análisis ni al tutor. Si prefieres resolución completa, copia el reto a un módulo de tu proyecto y conserva la primera línea `// coach:<id>`.

## Cuánto consume

El panel muestra arriba **qué archivo está mirando el tutor** (nombre y carpeta), y el botón lleva su nombre: «Revisar ChatListViewModel.kt». Más abajo, la sección **Consumo de la API** muestra:

- **Antes de revisar**: los tokens que enviaría la revisión de ese archivo y, si configuraste el precio, su costo aproximado.
- **Después de cada llamada**: los tokens reales que cobró el proveedor (entrada + salida) y el costo.
- **Por tipo**: llamadas y promedio por llamada de «Revisar mi código», «Tutor en vivo» y «Chat», en esta sesión y acumulado.
- **Cupo**: lo que queda de la ventana de límites actual (solicitudes y tokens por minuto), si el proveedor lo informa (Anthropic, OpenAI, Groq).
- **Saldo**: solo OpenRouter permite consultarlo con una clave normal. Para los demás proveedores, el panel enlaza a su consola de facturación.

El costo en dólares se calcula con el precio por millón de tokens que escribes en **Ajustes › Costo**, salvo en OpenRouter, que informa el costo real de cada llamada.

## Escribe tus propios retos

Crea archivos JSON en `.coach/challenges/` dentro de tu proyecto. Al abrir el proyecto, o al pulsar **recargar retos** en el panel, aparecen junto a los incluidos. Hay un ejemplo completo en [`examples/.coach/challenges/mi-paquete.json`](examples/.coach/challenges/mi-paquete.json).

```json
{
  "name": "Mi paquete",
  "challenges": [
    {
      "id": "mio-1",
      "level": "junior",
      "title": "Sumar los precios de un carrito",
      "topic": "Kotlin",
      "minutes": 10,
      "brief": "Devuelve la suma de `price * quantity`. Usa `sumOf`.",
      "starter": "fun total(items: List<Item>): Double {\n    TODO()\n}",
      "checks": [
        { "label": "Usa `sumOf`", "pattern": "\\.sumOf\\s*\\{" },
        { "label": "No deja `TODO()`", "pattern": "TODO\\s*\\(", "not": true }
      ],
      "rules": [
        { "pattern": "\\bfor\\s*\\(", "severity": "warn", "message": "¿Por qué un `for`? `sumOf` lo resuelve en una línea." }
      ],
      "solution": "fun total(items: List<Item>): Double = items.sumOf { it.price * it.quantity }",
      "notes": ["¿Usarías BigDecimal para dinero?"]
    }
  ]
}
```

| Campo | Significado |
|---|---|
| `level` | `junior`, `semi` o `senior` |
| `checks[].pattern` | Regex de Java que debe aparecer en el código sin comentarios. `"not": true` exige que no aparezca; `"min": 2` exige al menos dos coincidencias. |
| `rules[].pattern` | Regex que, si aparece, se subraya con `message`. Busca en código sin comentarios ni textos; con `"onStrings": true` también dentro de textos (útil para SQL de `@Query`). |
| `rules[].severity` | `error`, `warn` o `info` |
| `rules[].focus` | Subpatrón que se subraya dentro de la coincidencia, en vez de toda la coincidencia |
| `rules[].onlyIf` / `unless` | La regla solo aplica si el archivo contiene (o no contiene) esta regex |
| `rules[].special` | Reglas programadas incluidas: `expectActual`, `catchBeforeStateIn`, `withLockStale` |
| `solution`, `notes` | Se muestran en **Ver solución de referencia**. El tutor la usa como criterio, con instrucción de no revelarla. |

Si un reto tiene el mismo `id` que uno incluido, el tuyo lo reemplaza. Las regex inválidas se omiten y se listan al recargar.

Para agregar reglas para todos los archivos Kotlin, edita `GENERAL_RULES` en `src/main/kotlin/dev/kmpcoach/core/Analyzer.kt`.

## Estructura

```
src/main/kotlin/dev/kmpcoach/
  core/            Kotlin puro, sin dependencias del IDE (se prueba con JUnit)
    Analyzer.kt    analizador local y reglas generales
    Challenges.kt  modelo y lector de paquetes de retos
    AiClients.kt   clientes Anthropic y compatibles con OpenAI (java.net.http)
    Tutor.kt       prompts y lectura de la respuesta del tutor
    Hints.kt       escalera de pistas, filtro anti-código, detector de "atascado" y «Me rindo»
    Context.kt     plan en Markdown, git diff, bloques de código nuevo y comparación de archivos
    Learning.kt    anti-abuso de pistas, dominio de conceptos, repaso espaciado y prompts de comprensión
    Json.kt        JSON mínimo
  ide/             integración con el IDE
    Annotators.kt          subrayados locales y del tutor, Alt+Enter
    TutorService.kt        revisiones, comentarios bajo la línea, modo en vivo, chat
    TutorInlayRenderer.kt  dibujo de los comentarios en el editor
    CoachConfigurable.kt   pantalla de ajustes
    ui/CoachPanel.kt       panel lateral
src/main/resources/coach/challenges.json   13 retos incluidos
```

`./gradlew test` valida el paquete incluido: cada solución cumple todos sus objetivos sin avisos, y cada código inicial todavía no los cumple. Úsalo también para validar tus paquetes antes de compartirlos.

## Publicar en JetBrains Marketplace

1. Cambia `<id>` y `<vendor>` en `plugin.xml` por los tuyos.
2. `./gradlew verifyPlugin` para revisar la compatibilidad con varias versiones del IDE.
3. Sube el zip en [plugins.jetbrains.com](https://plugins.jetbrains.com) o configura `publishPlugin` con tu token.

# Mentor para VS Code

Un mentor de IA dentro del editor: te guía con pistas y preguntas en vez de escribir el código por ti. Funciona en
VS Code, Cursor y Antigravity.

> Versión preliminar (0.7.1, la misma que el plugin de Android Studio): panel de conversación, revisión con
> comentarios, pistas escalonadas, «Me rindo», guía de pasos, contexto de tu conversación con Claude Code, GitHub
> Copilot o Antigravity (o un plan pegado), estado de la conexión y proveedores.

## Instalar

1. Descarga `mentor-<versión>.vsix` de los Releases del repositorio.
2. En VS Code: paleta de comandos › **Extensions: Install from VSIX…** › elige el archivo.

## Elegir con quién piensa el Mentor

Paleta de comandos › **Mentor: Elegir proveedor**:

- **Con tu clave de API** (Anthropic, OpenAI, Gemini, Groq, OpenRouter, Ollama…): la clave se guarda en el almacén
  seguro de VS Code, solo en tu equipo.
- **Con tu suscripción**, a través del programa oficial de cada servicio y tu propia sesión:
  - Claude Pro/Max → Claude Code (`claude`)
  - Cursor → `cursor-agent` (experimental)

  Sin costo por llamada, pero unos segundos más lento y gasta la cuota de tu plan. El Mentor nunca ve tus
  credenciales: ejecuta el programa en una carpeta temporal vacía, sin permiso para editar archivos. Si aún no
  iniciaste sesión, **Mentor: Iniciar sesión en mi suscripción (terminal)** abre una terminal con el programa.

**¿Quedó conectado?** Al elegir proveedor, clave o modelo, el Mentor hace una prueba mínima y te avisa: «✓ Mentor
conectado…» o «✗ No pudo conectarse…» con el botón que lo arregla (poner la clave, iniciar sesión). Bajo el modelo,
en el panel, siempre ves el estado: ● verde conectado, ● rojo sin conexión, ● gris sin probar. Para cambiar la clave:
**Mentor: Poner o cambiar la clave de API**.

> **¿Y Gemini / Antigravity?** Los términos de Google Antigravity no permiten usar su suscripción desde otras
> aplicaciones (puede suspenderse la cuenta), así que el Mentor no lo ofrece. Para pensar con Gemini usa una clave
> gratuita de [Google AI Studio](https://aistudio.google.com/) con el proveedor «Google Gemini», que es lo que
> recomienda la propia Google. Los **planes** de Antigravity sí se leen (guía de pasos): solo son archivos de tu disco.

## Usarlo

- **Encenderlo en un archivo:** botón «Mentor» de la barra de estado o `⌘⌥⇧T` (`Ctrl+Alt+Shift+T`).
- **Preguntar:** panel «Mentor» de la barra lateral.
- **Revisar el archivo:** «Revisar archivo» en el panel. Cada comentario aparece bajo su línea con una caja para
  responderle al Mentor y un botón ✓ para resolverlo. Si cambias el código del que habla, se resuelve solo.
- **Pistas:** «Dame una pista» o `⌘⌥⇧H` (`Ctrl+Alt+Shift+H`). Cuatro escalones: pregunta → concepto →
  herramienta → pasos. La pista aparece al final de su línea; pasa el ratón para leerla completa. `Esc` la oculta.
- **Me rindo:** el Mentor te explica la solución paso a paso, en el panel.
- Encendido, lee el plan que te dio tu otra IA y las reglas de tu proyecto (`AGENTS.md` o `CLAUDE.md`).

## ¿De dónde lee tu plan?

De tu conversación más reciente **de este proyecto** que habla del archivo abierto (todo es local, nada se envía):

| IA | Qué lee |
|---|---|
| Claude Code | `~/.claude/projects/…/*.jsonl` |
| GitHub Copilot | sus conversaciones en este editor (VS Code guarda una por proyecto) |
| Antigravity (IDE, app y CLI) | su transcripción y sus planes (`~/.gemini/antigravity…/brain/…`) |
| **Cualquier otra** (Gemini o ChatGPT en el navegador, Cursor…) | **Pegar el plan**: copia el plan y pulsa «Pegar el plan» en el panel |

Si no encuentra nada, el panel te dice dónde buscó y te ofrece **Elegir conversación** o **Pegar el plan**.

## Guía de pasos

Si le pides a tu IA (Claude Code o Antigravity) un plan con pasos, el Mentor lo sigue contigo: arriba del archivo ves
en qué paso vas y qué te falta, con enlaces para **ir a lo que falta**, **poner el esqueleto** (solo los `TODO`),
**¿Cumple el paso?**, **Explícame este paso** y **Avanzar ›** al siguiente archivo.

- **Claude Code, GitHub Copilot y Antigravity:** los pasos que tu IA escribió en la conversación.
- **Planes de Antigravity:** cada archivo que el plan modifica con un `diff` es un paso.
- **Plan pegado:** los pasos del texto que pegaste.

Para que la guía sea más precisa, pega esto en las instrucciones de tu IA: `CLAUDE.md` (Claude Code), `GEMINI.md`
o `AGENTS.md` (Antigravity) o `.github/copilot-instructions.md` (GitHub Copilot):

```markdown
Cuando me des pasos para implementar, usa un título `## Paso N: qué se logra` por paso y, antes de cada bloque
```diff, un enlace al archivo con sus líneas, por ejemplo [Archivo.kt:27-32](ruta/Archivo.kt#L27-L32). En vez de la
solución, deja huecos `// TODO n: qué hacer` para que yo escriba el código. Termina cada paso con
**Cómo verificarlo:** y una frase.
```

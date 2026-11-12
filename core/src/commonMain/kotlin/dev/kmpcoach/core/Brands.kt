package dev.kmpcoach.core

/**
 * El ícono de cada IA, para que en el selector de proveedor y en el contexto se vea de un vistazo con quién piensa el
 * Mentor y de dónde salió el plan. Son íconos propios y simples que evocan cada marca (no los logotipos oficiales,
 * que son marcas registradas con sus propias reglas de uso). Los SVG viven en `src/main/resources/icons/brands/<id>.svg`
 * y la extensión de VS Code los copia al compilar.
 *
 * [color] y [glyph] sirven donde no se puede dibujar un SVG (por ejemplo, texto HTML en Swing).
 */
enum class Brand(val id: String, val color: String, val glyph: String) {
    CLAUDE("claude", "#D97757", "✳"),
    ANTIGRAVITY("antigravity", "#4C8DF6", "▲"),
    GEMINI("gemini", "#4C8DF6", "✦"),
    OPENAI("openai", "#8B949E", "⬡"),
    GROQ("groq", "#F55036", "◉"),
    OPENROUTER("openrouter", "#6566F1", "⇄"),
    OLLAMA("ollama", "#8B949E", "◍"),
    CURSOR("cursor", "#8B949E", "➤"),
    CUSTOM("custom", "#8B949E", "⚙");

    companion object {
        /** El ícono del proveedor: la misma IA con clave de API o con suscripción lleva el mismo ícono. */
        fun ofProvider(id: String?): Brand = when (ProviderPreset.of(id)) {
            ProviderPreset.ANTHROPIC, ProviderPreset.CLAUDE_CLI -> CLAUDE
            ProviderPreset.GEMINI -> GEMINI
            ProviderPreset.OPENAI -> OPENAI
            ProviderPreset.GROQ -> GROQ
            ProviderPreset.OPENROUTER -> OPENROUTER
            ProviderPreset.OLLAMA -> OLLAMA
            ProviderPreset.CURSOR_CLI -> CURSOR
            ProviderPreset.CUSTOM -> CUSTOM
        }

        /** El ícono de la fuente del plan: una conversación de Claude Code o un plan de Antigravity. */
        fun ofPlanFile(path: String): Brand = if ("/.gemini/" in path.replace('\\', '/')) ANTIGRAVITY else CLAUDE
    }
}

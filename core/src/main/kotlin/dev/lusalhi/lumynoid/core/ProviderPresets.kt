package dev.lusalhi.lumynoid.core

data class ProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
)

/** The providers offered as one-tap presets; any other OpenAI-compatible service uses a custom URL; Anthropic speaks its own wire format. */
object ProviderPresets {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        ProviderPreset("groq", "Groq", "https://api.groq.com/openai/v1"),
        ProviderPreset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
        ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1"),
        ProviderPreset("anthropic", "Anthropic", "https://api.anthropic.com/v1", ProviderKind.ANTHROPIC),
    )
}

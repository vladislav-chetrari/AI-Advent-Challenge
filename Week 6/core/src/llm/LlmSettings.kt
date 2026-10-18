package core.llm

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Настройки языковой модели: облако (DeepSeek) или локальная (Ollama).
// Дефолт — DeepSeek, чтобы старое поведение не менялось.
// Локальный режим: Ollama на localhost:11434, OpenAI-совместимый
// endpoint /v1/chat/completions, ключ не нужен.
//
// Мощные модели под MacBook M3 Pro 64 ГБ (unified memory — влезает и 70B Q4):
// - qwen3:32b — баланс качество/скорость, ~19 ГБ в Q4. Рекомендация по умолчанию.
// - qwen2.5-coder:32b — упор в код, понимает структуру проекта.
// - llama3.3:70b — самая сильная из списка, ~40 ГБ в Q4, медленнее.
// - deepseek-r1:32b — рассуждающая, длинные ответы, медленнее.
// - qwen2.5:7b — быстрая, уже стоит у автора (4.7 ГБ).
// Тянуть: `ollama pull qwen3:32b`, проверка: `curl localhost:11434/api/tags`.
@Serializable
data class LlmSettings(
    val provider: String = PROVIDER_DEEPSEEK,
    val model: String = DEFAULT_DEEPSEEK_MODEL,
    val baseUrl: String = DEFAULT_DEEPSEEK_URL,
    val temperature: Double = 0.3,
) {
    val isLocal: Boolean get() = provider == PROVIDER_OLLAMA

    // Полный URL chat-completions с учётом провайдера.
    // Ollama OpenAI-compat живёт под /v1, DeepSeek — без него.
    fun endpoint(): String {
        val b = baseUrl.trim().trimEnd('/').ifBlank {
            if (isLocal) DEFAULT_OLLAMA_URL else DEFAULT_DEEPSEEK_URL
        }
        return if (isLocal && !b.endsWith("/v1")) "$b/v1/chat/completions"
        else "$b/chat/completions"
    }

    // Короткий label для UI: "deepseek:deepseek-chat" | "ollama:qwen3:32b · localhost:11434".
    fun label(): String {
        return if (isLocal) {
            val host = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_OLLAMA_URL }.substringAfter("://")
            "ollama:$model · $host"
        } else {
            "deepseek:$model"
        }
    }

    companion object {
        const val PROVIDER_DEEPSEEK = "deepseek"
        const val PROVIDER_OLLAMA = "ollama"

        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"
        const val DEFAULT_DEEPSEEK_URL = "https://api.deepseek.com"
        const val DEFAULT_OLLAMA_MODEL = "qwen3:32b"
        const val DEFAULT_OLLAMA_URL = "http://localhost:11434"

        // Пресеты LLM для выбора в настройках (имя -> описание).
        val LLM_PRESETS: List<Pair<String, String>> = listOf(
            "qwen3:32b" to "рекомендовано 64 ГБ · баланс",
            "qwen2.5-coder:32b" to "упор в код проекта",
            "llama3.3:70b" to "максимум качества · ~40 ГБ",
            "deepseek-r1:32b" to "рассуждающая · медленно",
            "qwen2.5:7b" to "быстрая · уже скачана",
        )

        // Пресеты эмбеддингов (Ollama). bge-m3 — дефолт, русский хороший.
        val EMBED_PRESETS: List<Pair<String, String>> = listOf(
            "bge-m3" to "дефолт · русский хороший · 1024",
            "mxbai-embed-large" to "быстрее · 1024",
            "nomic-embed-text" to "лёгкая · 768",
            "snowflake-arctic-embed2" to "альтернатива · 1024",
        )
    }
}

class LlmSettingsStore(appDir: File = ApiKeyProvider.appDir()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(appDir, "llm.json")

    fun load(): LlmSettings {
        return try {
            if (!file.isFile) LlmSettings() else json.decodeFromString<LlmSettings>(file.readText())
        } catch (_: Exception) {
            LlmSettings()
        }
    }

    fun save(s: LlmSettings) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(LlmSettings.serializer(), s.copy(baseUrl = s.baseUrl.trim().trimEnd('/'))))
        } catch (_: Exception) {
        }
    }

    // Итоговые настройки: env выше сохранённых (удобно для CLI/CI).
    // WEEK6_LLM_PROVIDER=ollama|deepseek, WEEK6_LLM_MODEL, WEEK6_LLM_URL.
    fun effective(): LlmSettings {
        val saved = load()
        val provider = System.getenv("WEEK6_LLM_PROVIDER")?.trim()?.lowercase()
            ?.takeIf { it == LlmSettings.PROVIDER_OLLAMA || it == LlmSettings.PROVIDER_DEEPSEEK }
            ?: saved.provider.takeIf { it == LlmSettings.PROVIDER_OLLAMA || it == LlmSettings.PROVIDER_DEEPSEEK }
            ?: LlmSettings.PROVIDER_DEEPSEEK
        val defaultModel = if (provider == LlmSettings.PROVIDER_OLLAMA) LlmSettings.DEFAULT_OLLAMA_MODEL else LlmSettings.DEFAULT_DEEPSEEK_MODEL
        val defaultUrl = if (provider == LlmSettings.PROVIDER_OLLAMA) LlmSettings.DEFAULT_OLLAMA_URL else LlmSettings.DEFAULT_DEEPSEEK_URL
        // Совместимость: старый OLLAMA_HOST тоже подхватываем для локального URL.
        val model = System.getenv("WEEK6_LLM_MODEL")?.trim()?.takeIf { it.isNotBlank() }
            ?: saved.model.trim().takeIf { it.isNotBlank() } ?: defaultModel
        val url = System.getenv("WEEK6_LLM_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: System.getenv("OLLAMA_HOST")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() && provider == LlmSettings.PROVIDER_OLLAMA }
            ?: saved.baseUrl.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: defaultUrl
        // Если провайдер сменили через env, а модель/URL остались от другого — подменяем дефолтами.
        val modelFixed = if (saved.provider != provider && System.getenv("WEEK6_LLM_MODEL").isNullOrBlank()) {
            if (model == LlmSettings.DEFAULT_DEEPSEEK_MODEL || model == LlmSettings.DEFAULT_OLLAMA_MODEL) defaultModel else model
        } else model
        // Тот же случай для адреса: сменили провайдера на ollama через env, а в
        // llm.json остался api.deepseek.com — запрос ушёл бы в облако с локальной
        // моделью и упал бы с 401 вместо понятной ошибки.
        val urlFixed = if (saved.provider != provider &&
            System.getenv("WEEK6_LLM_URL").isNullOrBlank() &&
            System.getenv("OLLAMA_HOST").isNullOrBlank() &&
            url == LlmSettings.DEFAULT_DEEPSEEK_URL
        ) defaultUrl else url
        return LlmSettings(provider = provider, model = modelFixed, baseUrl = urlFixed, temperature = saved.temperature)
    }
}

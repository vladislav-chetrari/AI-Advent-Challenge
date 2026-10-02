package core.rag

import core.llm.ApiKeyProvider
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Настройки подключения к локальной эмбеддинг-модели (Ollama).
// Дефолт: http://localhost:11434 + bge-m3 (русский корпус).
// Переопределение: поля ниже (UI) или env WEEK4_OLLAMA_URL / WEEK4_EMBED_MODEL.
@Serializable
data class EmbedSettings(
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_URL,
) {
    companion object {
        const val DEFAULT_MODEL = "bge-m3"
        const val DEFAULT_URL = "http://localhost:11434"
    }
}

class EmbedSettingsStore(appDir: File = ApiKeyProvider.appDir()) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(appDir, "embed.json")

    fun load(): EmbedSettings {
        return try {
            if (!file.isFile) EmbedSettings() else json.decodeFromString<EmbedSettings>(file.readText())
        } catch (_: Exception) {
            EmbedSettings()
        }
    }

    fun save(s: EmbedSettings) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(EmbedSettings.serializer(), s.copy(baseUrl = s.baseUrl.trim().trimEnd('/'))))
        } catch (_: Exception) {
        }
    }

    // Итоговые настройки: env выше сохранённых (удобно для CLI/CI).
    fun effective(): EmbedSettings {
        val saved = load()
        val model = System.getenv("WEEK4_EMBED_MODEL")?.trim()?.takeIf { it.isNotBlank() } ?: saved.model.trim().takeIf { it.isNotBlank() } ?: EmbedSettings.DEFAULT_MODEL
        val url = System.getenv("WEEK4_OLLAMA_URL")?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: saved.baseUrl.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: EmbedSettings.DEFAULT_URL
        return EmbedSettings(model = model, baseUrl = url)
    }
}

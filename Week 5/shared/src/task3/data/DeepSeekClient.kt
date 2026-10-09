package task3.data

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

sealed interface DeepSeekResult {
    data class Ok(val text: String) : DeepSeekResult
    data class HttpError(val code: Int, val detail: String) : DeepSeekResult
    data class NetworkError(val detail: String) : DeepSeekResult
}

/**
 * DeepSeek через Koog (ai.koog:prompt-executor-deepseek-client).
 * Промпт собирается Koog DSL, параметры (temperature) едут внутри Prompt,
 * транспорт/сериализация/таймауты — на Koog (раньше был голый
 * HttpURLConnection + ручной JSON).
 *
 * Создание клиента — платформенное: дефолтный KoogHttpClient доступен
 * только в jvmCommon, поэтому см. expect/actual createDeepSeekExecutor.
 * Контракт для RagRepository не менялся.
 */
object DeepSeekClient {
    const val MODEL = "deepseek-chat"

    /**
     * deepseek-chat нет в предустановленных DeepSeekModels beta-клиента
     * (там только V4-флагманы), поэтому описываем его сами. Capabilities —
     * как у V4Flash: temperature/tools нужны исполнителю для валидации.
     */
    private val deepSeekChat = LLModel(
        provider = LLMProvider.DeepSeek,
        id = MODEL,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            LLMCapability.Schema.JSON.Basic,
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.MultipleChoices,
            LLMCapability.Thinking,
        ),
    )

    // Один клиент на ключ на всё время жизни приложения (создание Ktor-клиента
    // под каждый запрос — лишние потоки/сокеты).
    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cached: LLMClient? = null

    private fun executor(apiKey: String): LLMClient {
        val c = cached
        if (c != null && cachedKey == apiKey) return c
        return createDeepSeekExecutor(apiKey).also {
            cached = it
            cachedKey = apiKey
        }
    }

    suspend fun complete(
        system: String,
        user: String,
        history: List<Pair<String, String>> = emptyList(),
        apiKey: String?,
    ): DeepSeekResult {
        if (apiKey.isNullOrBlank()) return DeepSeekResult.NetworkError("Нет API-ключа DeepSeek")
        return try {
            val response = executor(apiKey).execute(
                prompt("rag-answer", DeepSeekParams(temperature = 0.3)) {
                    system(system)
                    history.takeLast(12).forEach { (role, content) ->
                        if (role == "assistant") assistant(content) else user(content)
                    }
                    user(user)
                },
                deepSeekChat,
                emptyList(),
            )
            val text = response.textContent().trim()
            if (text.isEmpty()) DeepSeekResult.NetworkError("Пустой ответ модели")
            else DeepSeekResult.Ok(text)
        } catch (e: Exception) {
            DeepSeekResult.NetworkError(e.message?.takeIf { it.isNotBlank() } ?: "запрос не удался")
        }
    }
}

/** Платформенное создание Koog-исполнителя (default HttpClient — только jvmCommon). */
expect fun createDeepSeekExecutor(apiKey: String): LLMClient

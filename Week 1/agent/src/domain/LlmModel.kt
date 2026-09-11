package agent.domain

// Конфиг LLM-модели (Task 3+): облако или локалка через OpenAI-совместимый HTTP.
// Лимит контекста и тарифы едут из модели: шапка показывает лимит текущей модели,
// стоимость локальных считается нулевой.
data class LlmModel(
    val id: String,
    val label: String,
    val apiId: String,
    val baseUrl: String,
    val contextLimit: Int,
    val inputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
    val needsApiKey: Boolean,
) {
    fun inputCostUsd(tokens: Int): Double = tokens * inputUsdPerMillion / 1_000_000.0

    fun outputCostUsd(tokens: Int): Double = tokens * outputUsdPerMillion / 1_000_000.0

    companion object {
        val DEEPSEEK: LlmModel = LlmModel(
            id = "deepseek-chat",
            label = "DeepSeek Chat",
            apiId = "deepseek-chat",
            baseUrl = "https://api.deepseek.com",
            contextLimit = 64_000,
            inputUsdPerMillion = 0.14,
            outputUsdPerMillion = 0.28,
            needsApiKey = true,
        )
        val TINYLLAMA: LlmModel = LlmModel(
            id = "tinyllama",
            label = "TinyLlama (local)",
            apiId = "tinyllama",
            baseUrl = "http://localhost:11434/v1",
            contextLimit = 2048,
            inputUsdPerMillion = 0.0,
            outputUsdPerMillion = 0.0,
            needsApiKey = false,
        )

        val ALL: List<LlmModel> = listOf(DEEPSEEK, TINYLLAMA)

        fun byId(id: String?): LlmModel = ALL.firstOrNull { it.id == id } ?: DEEPSEEK
    }
}

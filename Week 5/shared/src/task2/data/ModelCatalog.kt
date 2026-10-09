package task2.data

import task2.domain.AiModel

/**
 * Каталог из 3 моделей, все ChatML-совместимые (единый PromptBuilder).
 * Дефолт — qwen3-1.7b-q4 (баланс ум/ОЗУ для живого диалога).
 * URL указывают на HuggingFace GGUF-кванты Q4_K_M.
 */
object ModelCatalog {
    val QWEN3_06B_Q4 = AiModel(
        id = "qwen3-0.6b-q4",
        label = "Qwen3 0.6B Q4 (~500 МБ, лёгкая)",
        fileName = "Qwen_Qwen3-0.6B-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/main/Qwen_Qwen3-0.6B-Q4_K_M.gguf",
        sizeMb = 500,
        defaultCtx = 2048,
    )
    val QWEN3_17B_Q4 = AiModel(
        id = "qwen3-1.7b-q4",
        label = "Qwen3 1.7B Q4 (дефолт, ~1.3 ГБ)",
        fileName = "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
        sizeMb = 1300,
        defaultCtx = 2048,
    )
    val SMOLLM3_3B_Q4 = AiModel(
        id = "smollm3-3b-q4",
        label = "SmolLM3 3B Q4 (качество, ~1.9 ГБ)",
        fileName = "SmolLM3-3B-Q4_K_M.gguf",
        downloadUrl = "https://huggingface.co/lmstudio-community/SmolLM3-3B-GGUF/resolve/main/SmolLM3-3B-Q4_K_M.gguf",
        sizeMb = 1920,
        defaultCtx = 2048,
    )

    val ALL: List<AiModel> = listOf(QWEN3_06B_Q4, QWEN3_17B_Q4, SMOLLM3_3B_Q4)
    val DEFAULT: AiModel = QWEN3_17B_Q4

    fun resolve(id: String?): AiModel = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

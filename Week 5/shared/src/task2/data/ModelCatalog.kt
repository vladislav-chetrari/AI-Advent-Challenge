package task2.data

import task2.domain.AiModel

/**
 * Локальные модели под LiteRT-LM (.litertlm из litert-community, Apache-2.0,
 * скачивание без логина). Шаблон чата применяет сам рантайм —
 * ChatML-хаки PromptBuilder больше не нужны.
 * Дефолт — Qwen2.5 1.5B Q8 (инструктивная, тянет RAG с цитатами).
 */
object ModelCatalog {
    val QWEN25_15B_Q8 = AiModel(
        id = "qwen25-1.5b-q8",
        label = "Qwen2.5 1.5B Q8 (дефолт, ~1.5 ГБ)",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        sizeMb = 1524,
        defaultCtx = 4096,
    )
    val QWEN3_06B_INT4 = AiModel(
        id = "qwen3-0.6b-int4",
        label = "Qwen3 0.6B int4 (лёгкая, ~330 МБ)",
        fileName = "qwen3_0.6b_q4_block32_ekv1280.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_q4_block32_ekv1280.litertlm",
        sizeMb = 331,
        defaultCtx = 1280,
    )

    val ALL: List<AiModel> = listOf(QWEN25_15B_Q8, QWEN3_06B_INT4)
    val DEFAULT: AiModel = QWEN25_15B_Q8

    fun resolve(id: String?): AiModel = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

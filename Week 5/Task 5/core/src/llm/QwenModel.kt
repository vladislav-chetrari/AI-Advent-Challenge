package core.llm

// Профиль локальной модели. Дефолт qwen2.5:7b (~4.7 ГБ Q4) — проверен на
// RTX 3060 12GB в Task 1: влезает всегда с num_ctx=4096.
// ВАЖНО: contextLimit ниже — потолок модели, а НЕ рабочий контекст.
// Рабочий num_ctx по умолчанию 4096 (DEFAULT_NUM_CTX в QwenClient.kt): 8192+
// на 12GB — лотерея (llama-server падает с cudaMalloc/out-of-memory,
// только один буфер под KV-cache просит ~1.9 ГБ).
data class QwenModel(
    val id: String,
    val label: String,
    val apiId: String,
    val contextLimit: Int,
) {
    companion object {
        val QWEN25_7B: QwenModel = QwenModel(
            id = "qwen2.5-7b",
            label = "Qwen2.5 7B",
            apiId = "qwen2.5:7b",
            contextLimit = 32_768,
        )
        val QWEN3_8B: QwenModel = QwenModel(
            id = "qwen3-8b",
            label = "Qwen3 8B",
            apiId = "qwen3:8b",
            contextLimit = 40_960,
        )

        val ALL: List<QwenModel> = listOf(QWEN25_7B, QWEN3_8B)

        // id или apiId из ALL, иначе — кастомный apiId.
        fun resolve(raw: String?): QwenModel {
            if (raw.isNullOrBlank()) return QWEN25_7B
            ALL.firstOrNull { it.id == raw || it.apiId == raw }?.let { return it }
            return QwenModel(id = raw, label = "$raw (local)", apiId = raw, contextLimit = 32_768)
        }
    }
}

// Дефолты embedding-модели. embeddinggemma:300m — преемница EmbeddingGemma-270M
// из Task 3/4 (та же размерность 768, мультиязычная, русская вики — её профиль),
// ~300 МБ, бежит даже на CPU и не отъедает VRAM у LLM.
object EmbedDefaults {
    const val MODEL: String = "embeddinggemma:300m"
    const val DIM: Int = 768
    // Запасной вариант за качество RU-retrieval (1024d, ~1.2 ГБ):
    // const val MODEL_BIG: String = "bge-m3:567m"
}

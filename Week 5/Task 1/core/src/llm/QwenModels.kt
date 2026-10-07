package core.llm

// Профиль локальной модели. Один и тот же apiId работает и на Mac (M4 Pro 64GB),
// и на ПК (RTX 3060 12GB) — дефолт qwen2.5:7b (~4.7 ГБ Q4) целиком влезает в 12 ГБ VRAM
// даже с num_ctx 32768, а qwen3:8b (~5.2 ГБ) — запасной вариант с лучшим русским.
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

        // id или apiId из ALL, иначе — кастомный apiId (например qwen3-8b-nothink).
        fun resolve(raw: String?): QwenModel {
            if (raw.isNullOrBlank()) return QWEN25_7B
            ALL.firstOrNull { it.id == raw || it.apiId == raw }?.let { return it }
            return QwenModel(id = raw, label = "$raw (local)", apiId = raw, contextLimit = 32_768)
        }
    }
}

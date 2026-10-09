package task3.llama

/**
 * Тонкий порт к embedding-рантайму. Android — LiteRT-LM EmbeddingEngine
 * (бандл EmbeddingGemma), JVM-десктоп — Fake (хеш-вектора для превью).
 * Тексты уже с префиксами, если они нужны модели (EmbeddingGemma — без).
 */
interface EmbedBridge {
    val isReady: Boolean

    /** Фактическая размерность модели (проверяется после load). */
    val dim: Int

    suspend fun load(modelPath: String, nThreads: Int = 4)

    /** Тексты уже с префиксами (search_document/search_query) — их ставит каталог. */
    suspend fun embed(texts: List<String>): List<FloatArray>

    fun close()
}

expect fun createEmbedBridge(): EmbedBridge

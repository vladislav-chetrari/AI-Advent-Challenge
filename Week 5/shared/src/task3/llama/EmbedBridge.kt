package task3.llama

/**
 * Тонкий порт к embedding-режиму llama.cpp (pooling MEAN).
 * Генеративная модель и эмбеддинг живут в разных бриджах и разных
 * нативных хэндлах: держать оба GGUF в RAM одновременно тяжело,
 * поэтому репозиторий выгружает один при долгой работе другого (см. RagRepository).
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

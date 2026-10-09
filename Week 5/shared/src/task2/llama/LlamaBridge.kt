package task2.llama

import kotlinx.coroutines.flow.Flow

/**
 * Тонкий порт к llama.cpp. Реализация — expect/actual:
 * android — JNI к libllama.so, jvm — Fake для десктоп-превью.
 * SOLID (DIP): домен зависит только от этого интерфейса.
 */
interface LlamaBridge {
    val isReady: Boolean
    suspend fun load(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4)
    fun generate(prompt: String): Flow<String>
    fun cancel()
    fun close()
}

expect fun createLlamaBridge(): LlamaBridge

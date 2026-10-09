package task2.llama

import kotlinx.coroutines.flow.Flow

/**
 * Тонкий порт к локальному инференсу. Реализация — expect/actual:
 * android — LiteRT-LM, jvm — LiteRT-LM (десктоп тоже считает по-настоящему).
 * SOLID (DIP): домен зависит только от этого интерфейса.
 *
 * Промпт идёт парой (system, user): шаблон чата применяет сам рантайм
 * (LiteRT-LM), ChatML-строки из прошлого (llama.cpp) больше не нужны.
 */
interface LlamaBridge {
    val isReady: Boolean
    suspend fun load(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4)
    fun generateChat(system: String, user: String): Flow<String>
    fun cancel()
    fun close()
}

expect fun createLlamaBridge(): LlamaBridge

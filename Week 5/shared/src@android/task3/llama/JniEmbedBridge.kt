package task3.llama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import task3.data.l2normalize

/**
 * Android-реализация поверх embedding-режима llama.cpp.
 * Нативные функции живут в том же libtask2bridge.so (bridge.cpp),
 * порядок загрузки .so — как в JniLlamaBridge (idempotent).
 */
class JniEmbedBridge : EmbedBridge {
    private var handle: Long = 0L
    private var ready = false
    private var modelDim: Int = 0
    private var libMissing: String? = null

    init {
        try {
            System.loadLibrary("omp")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("ggml")
            System.loadLibrary("llama")
            System.loadLibrary("task2bridge")
        } catch (e: UnsatisfiedLinkError) {
            libMissing = "нативные .so не найдены в APK (собери по tools/build-llama.sh): ${e.message}"
        }
    }

    override val isReady: Boolean get() = ready && handle != 0L
    override val dim: Int get() = modelDim

    override suspend fun load(modelPath: String, nThreads: Int) = withContext(Dispatchers.IO) {
        libMissing?.let { throw IllegalStateException(it) }
        if (handle != 0L) runCatching { nativeEmbFree(handle) }
        handle = wrapNative { nativeEmbInit(modelPath, nThreads) }
        if (handle == 0L) throw IllegalStateException("llama.cpp не открыл embedding-модель $modelPath")
        modelDim = wrapNative { nativeEmbDim(handle) }
        if (modelDim <= 0) {
            runCatching { nativeEmbFree(handle) }
            handle = 0L
            throw IllegalStateException("embedding-модель вернула dim=$modelDim")
        }
        ready = true
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (!isReady) throw IllegalStateException("Embedding-модель не загружена")
        texts.map { t ->
            l2normalize(wrapNative { nativeEmbEmbed(handle, t) })
        }
    }

    /**
     * Пропавший символ = .so собран из старого bridge.cpp.
     * UnsatisfiedLinkError — это Error, сквозь catch(Exception) он ронял
     * приложение, поэтому транслируем в понятное исключение сразу на границе JNI.
     */
    private inline fun <T> wrapNative(block: () -> T): T = try {
        block()
    } catch (e: UnsatisfiedLinkError) {
        throw IllegalStateException(
            "нативный мост устарел (нет символа ${e.message}): " +
                "пересобери libtask2bridge через tools/build-llama.sh",
            e,
        )
    }

    override fun close() {
        if (handle != 0L) {
            runCatching { nativeEmbFree(handle) }
            handle = 0L
            ready = false
            modelDim = 0
        }
    }

    private external fun nativeEmbInit(modelPath: String, nThreads: Int): Long
    private external fun nativeEmbDim(handle: Long): Int
    private external fun nativeEmbEmbed(handle: Long, text: String): FloatArray
    private external fun nativeEmbFree(handle: Long)
}

actual fun createEmbedBridge(): EmbedBridge = JniEmbedBridge()

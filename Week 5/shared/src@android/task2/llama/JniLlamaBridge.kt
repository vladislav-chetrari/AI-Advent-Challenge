package task2.llama

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Android-реализация поверх llama.cpp (libtask2bridge.so + libllama.so +
 * libggml*.so из jniLibs/<abi>). Порядок загрузки — от зависимостей к мосту.
 * Если .so нет — load() кинет понятную ошибку, а не UnsatisfiedLinkError.
 */
class JniLlamaBridge : LlamaBridge {
    private var handle: Long = 0L
    private var ready = false
    private var libMissing: String? = null

    init {
        try {
            // Порядок важен: сначала зависимости, потом JNI-мост.
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

    override suspend fun load(modelPath: String, nCtx: Int, nThreads: Int) =
        withContext(Dispatchers.IO) {
            libMissing?.let { throw IllegalStateException(it) }
            if (handle != 0L) nativeFree(handle)
            handle = nativeInit(modelPath, nCtx, nThreads)
            if (handle == 0L) throw IllegalStateException("llama.cpp не смог открыть $modelPath (OOM или битый GGUF)")
            ready = true
        }

    override fun generate(prompt: String): Flow<String> = flow {
        if (!isReady) throw IllegalStateException("Модель не загружена")
        // V1: натив возвращает полный текст, стриммим в UI пословно.
        // V2: заменить на token-callback из common/sampling.
        val full = nativeGenerate(handle, prompt)
        val words = full.split(" ")
        for (w in words) {
            ensureActive()
            emit(if (w == words.last()) w else "$w ")
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        if (handle != 0L) runCatching { nativeCancel(handle) }
    }

    override fun close() {
        if (handle != 0L) {
            runCatching { nativeFree(handle) }
            handle = 0L
            ready = false
        }
    }

    private suspend fun ensureActive() {
        currentCoroutineContext().ensureActive()
    }

    private external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String): String
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
}

actual fun createLlamaBridge(): LlamaBridge = JniLlamaBridge()

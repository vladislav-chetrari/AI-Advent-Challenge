package task2.llama

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Десктоп-заглушка: на JVM нет libllama.so в V1, поэтому desktopApp
 * показывает тот же UI/стримминг на canned-ответе. На Android работает JNI.
 */
class FakeLlamaBridge : LlamaBridge {
    private var modelPath: String? = null

    override val isReady: Boolean get() = modelPath != null

    override suspend fun load(modelPath: String, nCtx: Int, nThreads: Int) {
        delay(400)
        this.modelPath = modelPath
    }

    override fun generate(prompt: String): Flow<String> = flow {
        val user = prompt.substringAfterLast("<|im_start|>user").substringBefore("<|im_end|>").trim().take(120)
        val answer = "Это десктоп-превью без нативного llama.cpp. " +
            "На Android тот же запрос уйдёт в Qwen3-1.7B-Q4 через JNI. " +
            "Ваш вопрос: «$user». Соберите libllama.so по tools/build-llama.sh для on-device генерации."
        for (w in answer.split(" ")) {
            delay(25)
            emit("$w ")
        }
    }

    override fun cancel() {}
    override fun close() {
        modelPath = null
    }
}

actual fun createLlamaBridge(): LlamaBridge = FakeLlamaBridge()

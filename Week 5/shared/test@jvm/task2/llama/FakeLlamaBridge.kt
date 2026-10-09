package task2.llama

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Тестовая заглушка генеративного бриджа (жизнь в test-сорссете:
 * в main на всех платформах теперь настоящий LiteRT-LM).
 */
class FakeLlamaBridge : LlamaBridge {
    private var modelPath: String? = null

    override val isReady: Boolean get() = modelPath != null

    override suspend fun load(modelPath: String, nCtx: Int, nThreads: Int) {
        this.modelPath = modelPath
    }

    override fun generateChat(system: String, user: String): Flow<String> = flow {
        emit("fake-reply")
    }

    override fun cancel() {}
    override fun close() {
        modelPath = null
    }
}

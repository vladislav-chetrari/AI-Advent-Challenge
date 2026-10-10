package task2.llama

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Десктоп-реализация поверх litertlm-jvm: тот же код, что на Android,
 * настоящий инференс (больше никакого Fake на JVM).
 * Бэкенд: сначала GPU с пробой полного пути, при отказе — молча CPU
 * (см. android-версию). Темплейт свой (CHATML_TEMPLATE, общая константа).
 */
class LiteRtLlamaBridge : LlamaBridge {
    private var engine: Engine? = null

    @Volatile
    private var active: Conversation? = null

    override val isReady: Boolean get() = engine?.isInitialized() == true

    override suspend fun load(modelPath: String, nCtx: Int, nThreads: Int) =
        withContext(Dispatchers.IO) {
            close()
            val gpuOk = tryOpen(modelPath, Backend.GPU(), probe = true)
            if (!gpuOk) {
                tryOpen(
                    modelPath,
                    Backend.CPU(threadCount = nThreads.takeIf { it > 0 }),
                    probe = false,
                )
            }
        }

    private fun tryOpen(modelPath: String, backend: Backend, probe: Boolean): Boolean {
        val e = Engine(EngineConfig(modelPath = modelPath, backend = backend))
        try {
            e.initialize()
            if (probe) {
                e.createConversation(
                    ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.5),
                        maxOutputToken = 16,
                        chatTemplate = CHATML_TEMPLATE,
                    ),
                ).use { conv ->
                    conv.sendMessage(Message.user("hi"))
                }
            }
        } catch (t: Throwable) {
            runCatching { e.close() }
            if (backend is Backend.CPU) {
                throw IllegalStateException("LiteRT не смог открыть $modelPath: ${t.message}")
            }
            return false
        }
        engine = e
        return true
    }

    override fun generateChat(system: String, user: String): Flow<String> = flow {
        val e = engine?.takeIf { it.isInitialized() }
            ?: throw IllegalStateException("Модель не загружена")
        val conv = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.5),
                chatTemplate = CHATML_TEMPLATE,
            ),
        )
        active = conv
        try {
            conv.sendMessageAsync(Message.user(user)).collect { msg ->
                currentCoroutineContext().ensureActive()
                emit(msg.toString())
            }
        } finally {
            if (active === conv) active = null
            runCatching { conv.close() }
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel() {
        runCatching { active?.close() }
        active = null
    }

    override fun close() {
        runCatching { active?.close() }
        active = null
        runCatching { engine?.close() }
        engine = null
    }
}

actual fun createLlamaBridge(): LlamaBridge = LiteRtLlamaBridge()

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
 * Инференс через LiteRT-LM (Google AI Edge): Engine на .litertlm-модели,
 * свежий Conversation на каждый запрос (история уже вшита в user-текст),
 * дельты из sendMessageAsync стримятся в UI как есть.
 * Бэкенд: сначала GPU (на флагманах префилл быстрее в разы),
 * при отказе (эмулятор, старый девайс) — молча CPU.
 * Темплейт свой (CHATML_TEMPLATE): встроенный в бандл падает на
 * системном сообщении, см. комментарий к константе.
 */
class LiteRtLlamaBridge : LlamaBridge {
    private var engine: Engine? = null

    @Volatile
    private var active: Conversation? = null

    override val isReady: Boolean get() = engine?.isInitialized() == true

    override suspend fun load(modelPath: String, nCtx: Int, nThreads: Int) =
        withContext(Dispatchers.IO) {
            close()
            // GPU быстрее на разы, но есть не везде — пробуем первым,
            // при отказе уходим на CPU (всегда работает).
            val gpuError = runCatching { open(modelPath, Backend.GPU()) }.exceptionOrNull()
            if (gpuError != null) {
                open(modelPath, Backend.CPU(threadCount = nThreads.takeIf { it > 0 }))
            }
        }

    private fun open(modelPath: String, backend: Backend) {
        val e = Engine(EngineConfig(modelPath = modelPath, backend = backend))
        try {
            // Грузится до ~10 секунд на больших моделях — статус LoadingModel уже висит.
            e.initialize()
        } catch (t: Throwable) {
            runCatching { e.close() }
            throw IllegalStateException("LiteRT не смог открыть $modelPath: ${t.message}")
        }
        engine = e
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
        // Обрыв нативного декода: закрытие активного Conversation.
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

package agent

import agent.data.CompressionSettings
import agent.data.CompressionStore
import agent.data.ModelStore
import agent.data.db.SqliteChatRepository
import agent.domain.ChatMessage
import agent.domain.ChatRepository
import agent.domain.LlmModel
import agent.domain.SUMMARY_ROLE
import agent.network.LlmClient
import agent.network.LlmResult
import agent.network.TokenUsage
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Fake для Task 4: скриптованные ответы, пишет wire-роли для проверки маппинга summary->system.
private class FakeLlmClient : LlmClient(model = "fake", temperature = 0.0, baseUrl = "http://localhost:1") {
    var calls: Int = 0
    var lastRoles: List<String> = emptyList()
    var lastContents: List<String> = emptyList()
    // Все вызовы: ask сам перезаписывается follow-up суммаризацией, поэтому ищем по всем.
    val allContents: MutableList<List<String>> = mutableListOf()

    override suspend fun complete(history: List<ChatMessage>, apiKey: String?): LlmResult {
        calls++
        lastRoles = history.map { it.role }
        lastContents = history.map { it.content }
        allContents += lastContents.toList()
        val isSummary = history.any { it.content.contains("fold into the summary") }
        return if (isSummary) {
            LlmResult.Ok("test-summary", TokenUsage(promptTokens = 50, completionTokens = 20, totalTokens = 70))
        } else {
            LlmResult.Ok("reply-$calls", TokenUsage(promptTokens = 100 * calls, completionTokens = 10, totalTokens = 100 * calls + 10))
        }
    }
}

class AgentTest {
    private fun tempDb(): java.io.File {
        val dir = Files.createTempDirectory("agent-test").toFile()
        dir.deleteOnExit()
        return java.io.File(dir, "chat.db")
    }

    @Test
    fun `empty prompt is rejected`() {
        runBlocking {
            Agent.apiKeyOverride = "dummy-for-validation"
            try {
                val agent = Agent(dbFile = tempDb())
                try {
                    val result = agent.ask("   ")
                    assertIs<AgentResult.Failure>(result)
                } finally {
                    agent.close()
                }
            } finally {
                Agent.apiKeyOverride = null
            }
        }
    }

    @Test
    fun `missing key short-circuits without network`() {
        runBlocking {
            Agent.apiKeyOverride = "" // форсировать MissingKey детерминированно
            try {
                val agent = Agent(dbFile = tempDb())
                try {
                    val result = agent.ask("ping")
                    assertIs<AgentResult.Failure>(result)
                    assertIs<AgentError.MissingKey>(result.error)
                } finally {
                    agent.close()
                }
            } finally {
                Agent.apiKeyOverride = null
            }
        }
    }

    @Test
    fun `dotenv or env key is picked up when no override`() {
        runBlocking {
            Agent.apiKeyOverride = null
            val key = Agent.resolveApiKey()
            // В репо лежит корневой .env с ключом — агент обязан его найти сам.
            assertTrue(!key.isNullOrBlank(), "expected agent to find DEEPSEEK_API_KEY via env/.env")
        }
    }

    @Test
    fun `sqlite repository survives restart`() {
        val db = tempDb()
        val repo1: ChatRepository = SqliteChatRepository(db)
        repo1.replaceAll(messages = listOf(ChatMessage("user", "меня зовут Тест"), ChatMessage("assistant", "привет, Тест")))
        repo1.close()

        // "Перезапуск": новый репозиторий на том же файле обязан вернуть историю.
        val repo2: ChatRepository = SqliteChatRepository(db)
        try {
            val loaded = repo2.load()
            assertEquals(2, loaded.size)
            assertEquals("меня зовут Тест", loaded[0].content)
            assertEquals("привет, Тест", loaded[1].content)
        } finally {
            repo2.close()
        }
    }

    @Test
    fun `agent restores history from sqlite on restart`() {
        val db = tempDb()
        val seed: ChatRepository = SqliteChatRepository(db)
        seed.replaceAll(messages = listOf(ChatMessage("user", "ping")))
        seed.close()

        Agent.apiKeyOverride = ""
        try {
            val agent = Agent(dbFile = db)
            try {
                val snap = agent.historySnapshot()
                assertEquals(listOf("user" to "ping"), snap)
            } finally {
                runBlocking { agent.close() }
            }
        } finally {
            Agent.apiKeyOverride = null
        }
    }

    @Test
    fun `clearHistory wipes sqlite`() {
        val db = tempDb()
        val agent = Agent(dbFile = db)
        try {
            agent.clearHistory()
            assertTrue(agent.historySnapshot().isEmpty())

            val check: ChatRepository = SqliteChatRepository(db)
            try {
                assertTrue(check.load().isEmpty())
            } finally {
                check.close()
            }
        } finally {
            runBlocking { agent.close() }
        }
    }

    @Test
    fun `sqlite repository roundtrips tokens with default 0 for old messages`() {
        val db = tempDb()
        val repo1: ChatRepository = SqliteChatRepository(db)
        // Старое сообщение без замера — все нули по умолчанию.
        repo1.replaceAll(
            messages = listOf(
                ChatMessage("user", "привет"),
                ChatMessage("assistant", "здравствуй", promptTokens = 100, completionTokens = 20, totalTokens = 120, tokens = 20, costUsd = 0.0000056),
                ChatMessage("user", "как дела", tokens = 30, costUsd = 0.0000042),
            ),
        )
        repo1.close()

        val repo2: ChatRepository = SqliteChatRepository(db)
        try {
            val loaded = repo2.load()
            assertEquals(3, loaded.size)
            assertEquals(0, loaded[0].tokens)
            assertEquals(0.0, loaded[0].costUsd)
            assertEquals(100, loaded[1].promptTokens)
            assertEquals(20, loaded[1].tokens)
            assertEquals(30, loaded[2].tokens)
            assertTrue(loaded[2].costUsd > 0.0)
        } finally {
            repo2.close()
        }
    }

    @Test
    fun `agent hydrates token stats from sqlite on restart`() {
        val db = tempDb()
        val seed: ChatRepository = SqliteChatRepository(db)
        seed.replaceAll(
            messages = listOf(
                ChatMessage("user", "ping", tokens = 40, costUsd = 0.0000056),
                ChatMessage("assistant", "pong", promptTokens = 50, completionTokens = 10, totalTokens = 60, tokens = 10, costUsd = 0.0000028),
            ),
        )
        seed.close()

        Agent.apiKeyOverride = ""
        try {
            val agent = Agent(dbFile = db)
            try {
                val withTokens = agent.historyWithTokens()
                assertEquals(2, withTokens.size)
                assertEquals(40, withTokens[0].tokens)
                assertEquals(10, withTokens[1].tokens)
                val stats = agent.statsSnapshot()
                assertEquals(50, stats.contextTokens)
                assertEquals(50, stats.sessionTokens)
                assertTrue(stats.sessionCostUsd > 0.0)
            } finally {
                runBlocking { agent.close() }
            }
        } finally {
            Agent.apiKeyOverride = null
        }
    }

    @Test
    fun `token pricing formats to 10 decimals`() {
        assertEquals("0.0000056000", agent.domain.formatCostUsd(agent.domain.TokenPricing.outputCostUsd(20)))
        assertEquals("0.0000042000", agent.domain.formatCostUsd(agent.domain.TokenPricing.inputCostUsd(30)))
    }

    @Test
    fun `switchModel wipes history and stats`() {
        val db = tempDb()
        val seed: ChatRepository = SqliteChatRepository(db)
        seed.replaceAll(
            messages = listOf(
                ChatMessage("user", "ping", tokens = 40, costUsd = 0.0000056),
                ChatMessage("assistant", "pong", promptTokens = 50, completionTokens = 10, totalTokens = 60, tokens = 10, costUsd = 0.0000028),
            ),
        )
        seed.close()

        Agent.apiKeyOverride = ""
        try {
            val agent = Agent(dbFile = db)
            try {
                assertEquals(2, agent.historyWithTokens().size)
                agent.switchModel(LlmModel.TINYLLAMA)
                assertTrue(agent.historyWithTokens().isEmpty())
                assertTrue(agent.historySnapshot().isEmpty())
                val stats = agent.statsSnapshot()
                assertEquals(0, stats.sessionTokens)
                assertEquals(0.0, stats.sessionCostUsd)
                assertEquals(0, stats.contextTokens)
                assertEquals(LlmModel.TINYLLAMA.id, agent.currentModel.id)
                // Выбор сохранился в файл рядом с БД.
                assertEquals(
                    LlmModel.TINYLLAMA.id,
                    ModelStore.load(db.parentFile).id,
                )
            } finally {
                runBlocking { agent.close() }
            }
        } finally {
            Agent.apiKeyOverride = null
        }
    }

    @Test
    fun `local model does not require api key`() {
        val db = tempDb()
        Agent.apiKeyOverride = "" // пустой ключ
        try {
            val agent = Agent(dbFile = db)
            try {
                agent.switchModel(LlmModel.TINYLLAMA)
                val result = runBlocking { agent.ask("ping") }
                val failure = assertIs<AgentResult.Failure>(result)
                // Ключ не требуется: любая ошибка, кроме MissingKey
                // (обычно Network — Ollama не запущена; при запущенной может и Success).
                assertTrue(failure.error !is AgentError.MissingKey)
            } finally {
                runBlocking { agent.close() }
            }
        } finally {
            Agent.apiKeyOverride = null
        }
    }

    @Test
    fun `model choice survives restart via file`() {
        val db = tempDb()
        ModelStore.save(db.parentFile, LlmModel.TINYLLAMA)
        Agent.apiKeyOverride = ""
        try {
            val agent = Agent(dbFile = db)
            try {
                assertEquals(LlmModel.TINYLLAMA.id, agent.currentModel.id)
            } finally {
                runBlocking { agent.close() }
            }
        } finally {
            Agent.apiKeyOverride = null
        }
    }

    // --- Task 4: сжатие истории ---

    private fun compressionAgent(
        db: java.io.File,
        fake: FakeLlmClient,
        n: Int,
        enabled: Boolean = true,
    ): Agent = Agent(
        llmModel = LlmModel.TINYLLAMA,
        repository = SqliteChatRepository(db),
        conversationId = ChatRepository.DEFAULT_CONVERSATION,
        llm = fake,
        modelDir = db.parentFile,
        compression = CompressionSettings(enabled = enabled, keepLastN = n),
    )

    @Test
    fun `compression collapses old messages keeping last N-1 live`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        val agent = compressionAgent(db, fake, n = 4)
        try {
            runBlocking {
                repeat(3) { i ->
                    assertIs<AgentResult.Success>(agent.ask("q$i"))
                }
            }
            // 3 витка = 6 живых; N=4 -> держим 3 последних + 1 summary.
            assertEquals(3, agent.historyWithTokens().size)
            val snap = agent.compressionSnapshot()
            assertTrue(snap.summaryText.isNotBlank())
            assertEquals("test-summary", snap.summaryText)
            // Summary скрыто из снапшотов, в БД лежит ровно одно.
            val raw: ChatRepository = SqliteChatRepository(db)
            try {
                val all = raw.load()
                assertEquals(1, all.count { it.role == SUMMARY_ROLE })
            } finally {
                raw.close()
            }
            // На провод summary уходит как system, роль summary наружу не течёт.
            assertTrue(fake.lastRoles.none { it == SUMMARY_ROLE })
            // Счётчики монотонны: суммарно больше нуля и не упали после схлопывания.
            assertTrue(agent.statsSnapshot().sessionTokens > 0)
        } finally {
            runBlocking { agent.close() }
        }
    }

    @Test
    fun `summary survives restart and stays hidden`() {
        val db = tempDb()
        val fake1 = FakeLlmClient()
        val agent1 = compressionAgent(db, fake1, n = 4)
        runBlocking {
            repeat(3) { i -> agent1.ask("q$i") }
            agent1.close()
        }
        val summaryBefore = agent1.compressionSnapshot().summaryText

        val fake2 = FakeLlmClient()
        val agent2 = compressionAgent(db, fake2, n = 4)
        try {
            assertEquals(summaryBefore, agent2.compressionSnapshot().summaryText)
            assertEquals(3, agent2.historyWithTokens().size)
            assertTrue(agent2.historySnapshot().none { it.first == SUMMARY_ROLE })
            // Следующий запрос подмешивает summary как system (ищем по всем вызовам:
            // follow-up суммаризация перезаписывает lastContents).
            runBlocking { agent2.ask("next") }
            assertTrue(fake2.lastRoles.none { it == SUMMARY_ROLE })
            assertTrue(fake2.allContents.any { call -> call.any { it.contains("Previous conversation summary") } })
        } finally {
            runBlocking { agent2.close() }
        }
    }

    @Test
    fun `disabled compression never summarizes`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        val agent = compressionAgent(db, fake, n = 4, enabled = false)
        try {
            runBlocking {
                repeat(3) { i ->
                    assertIs<AgentResult.Success>(agent.ask("q$i"))
                }
            }
            assertEquals(6, agent.historyWithTokens().size)
            assertEquals("", agent.compressionSnapshot().summaryText)
        } finally {
            runBlocking { agent.close() }
        }
    }

    @Test
    fun `keepLastN never below 1 and survives restart via file`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        val agent = compressionAgent(db, fake, n = 10)
        try {
            agent.setKeepLastN(0)
            assertEquals(1, agent.compressionSnapshot().keepLastN)
            agent.setKeepLastN(-5)
            assertEquals(1, agent.compressionSnapshot().keepLastN)
            agent.setKeepLastN(7)
            assertEquals(7, agent.compressionSnapshot().keepLastN)
            assertEquals(7, CompressionStore.load(db.parentFile).keepLastN)
            agent.setCompressionEnabled(false)
            assertEquals(false, CompressionStore.load(db.parentFile).enabled)
        } finally {
            runBlocking { agent.close() }
        }
        // Перезапуск читает N из файла.
        val agent2 = Agent(dbFile = db)
        try {
            assertEquals(7, agent2.compressionSnapshot().keepLastN)
        } finally {
            runBlocking { agent2.close() }
        }
    }
}

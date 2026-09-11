package agent

import agent.data.ModelStore
import agent.data.db.SqliteChatRepository
import agent.domain.ChatMessage
import agent.domain.ChatRepository
import agent.domain.LlmModel
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
}

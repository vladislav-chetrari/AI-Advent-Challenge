package agent

import agent.data.db.SqliteChatRepository
import agent.domain.ChatMessage
import agent.domain.ChatRepository
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
}

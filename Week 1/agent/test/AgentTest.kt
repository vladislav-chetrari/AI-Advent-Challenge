package agent

import agent.data.BranchStore
import agent.data.db.SqliteBranchRepository
import agent.domain.Branch
import agent.data.CompressionSettings
import agent.data.CompressionStore
import agent.data.FactsStore
import agent.data.ModelStore
import agent.data.StrategyStore
import agent.data.db.SqliteChatRepository
import agent.domain.ChatMessage
import agent.domain.ChatRepository
import agent.domain.LlmModel
import agent.domain.StrategyType
import agent.domain.SUMMARY_ROLE
import agent.domain.branchPath
import agent.domain.buildFactsBlock
import agent.domain.parseFactsJson
import agent.network.LlmClient
import agent.network.LlmResult
import agent.network.TokenUsage
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Fake для Task 4+5: скриптованные ответы, пишет wire-роли для проверки маппинга summary->system.
private class FakeLlmClient : LlmClient(model = "fake", temperature = 0.0, baseUrl = "http://localhost:1") {
    var calls: Int = 0
    var lastRoles: List<String> = emptyList()
    var lastContents: List<String> = emptyList()
    // Все вызовы: ask сам перезаписывается follow-up суммаризацией, поэтому ищем по всем.
    val allContents: MutableList<List<String>> = mutableListOf()
    var factsJson: String = "{\"цель\": \"собрать ТЗ\"}"

    override suspend fun complete(history: List<ChatMessage>, apiKey: String?): LlmResult {
        calls++
        lastRoles = history.map { it.role }
        lastContents = history.map { it.content }
        allContents += lastContents.toList()
        val joined = history.joinToString("\n") { it.content }
        val isFacts = joined.contains("merged JSON of all durable facts") ||
            joined.contains("You extract sticky facts")
        if (isFacts) {
            return LlmResult.Ok(factsJson, TokenUsage(promptTokens = 40, completionTokens = 10, totalTokens = 50))
        }
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
        strategy = StrategyType.SUMMARY_LEGACY,
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

    // --- Task 5: стратегии контекста ---

    private fun strategyAgent(
        db: java.io.File,
        fake: FakeLlmClient,
        strategy: StrategyType,
        n: Int = 4,
    ): Agent = Agent(
        llmModel = LlmModel.TINYLLAMA,
        repository = SqliteChatRepository(db),
        conversationId = ChatRepository.DEFAULT_CONVERSATION,
        llm = fake,
        modelDir = db.parentFile,
        compression = CompressionSettings(enabled = false, keepLastN = n),
        strategy = strategy,
    )

    @Test
    fun `sliding window sends only last N`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        val agent = strategyAgent(db, fake, StrategyType.SLIDING, n = 4)
        try {
            runBlocking {
                repeat(4) { i ->
                    assertIs<AgentResult.Success>(agent.ask("q$i"))
                }
            }
            // 4 витка = 8 живых, N=4 -> на провод ушли ровно 4 последних.
            assertEquals(8, agent.historyWithTokens().size)
            assertEquals(4, fake.lastContents.size)
            assertTrue(fake.lastContents.none { it.contains("q0") })
            assertTrue(fake.lastContents.any { it.contains("q3") })
            // Summary/facts не подмешиваются.
            assertTrue(fake.lastContents.none { it.contains("Previous conversation summary") })
            assertTrue(fake.lastContents.none { it.contains("Sticky facts") })
        } finally {
            runBlocking { agent.close() }
        }
    }

    @Test
    fun `facts strategy injects block and merges via llm`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        fake.factsJson = "{\"цель\": \"собрать ТЗ\", \"платформа\": \"KMP\"}"
        val agent = strategyAgent(db, fake, StrategyType.FACTS, n = 6)
        try {
            runBlocking {
                assertIs<AgentResult.Success>(agent.ask("мы делаем KMP приложение"))
            }
            val snap = agent.strategySnapshot()
            assertEquals("собрать ТЗ", snap.facts["цель"])
            assertEquals("KMP", snap.facts["платформа"])
            // Следующий запрос везёт блок facts как system.
            runBlocking {
                assertIs<AgentResult.Success>(agent.ask("продолжаем"))
            }
            assertTrue(fake.allContents.any { call -> call.any { it.contains("Sticky facts") } })
            assertTrue(fake.allContents.any { call -> call.any { it.contains("платформа") } })
            // Facts переживают рестарт через файл.
            assertEquals("KMP", FactsStore.load(db.parentFile, ChatRepository.DEFAULT_CONVERSATION)["платформа"])
        } finally {
            runBlocking { agent.close() }
        }
    }

    @Test
    fun `facts strategy records all facts without cap but sends last N`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        // 25 фактов — старого cap 20 нет, мерджатся все.
        fake.factsJson = (1..25).joinToString(prefix = "{", postfix = "}") { "\"k$it\": \"v$it\"" }
        val agent = strategyAgent(db, fake, StrategyType.FACTS, n = 4)
        try {
            runBlocking {
                repeat(3) { i ->
                    assertIs<AgentResult.Success>(agent.ask("q$i"))
                }
            }
            assertEquals(25, agent.strategySnapshot().facts.size)
            assertEquals("v25", agent.strategySnapshot().facts["k25"])
            // N режет историю: 6 живых, в последнем ask ушли блок фактов + 4 последних.
            // (lastContents перезаписан follow-up экстрактором — смотрим последний не-экстрактор вызов.)
            val lastAsk = fake.allContents.last { call -> call.none { it.contains("merged JSON") } }
            assertEquals(6, agent.historyWithTokens().size)
            assertEquals(5, lastAsk.size)
            assertTrue(lastAsk.any { it.contains("q2") })
            assertTrue(lastAsk.none { it.contains("q0") })
        } finally {
            runBlocking { agent.close() }
        }
    }

    @Test
    fun `parseFactsJson tolerates markdown fences`() {
        val parsed = parseFactsJson("```json\n{\"a\": \"1\", \"b\": 2}\n```")
        assertEquals("1", parsed["a"])
        assertEquals("2", parsed["b"])
        assertTrue(buildFactsBlock(mapOf("a" to "1")).contains("Sticky facts"))
    }

    @Test
    fun `branching forks isolate histories and switch works`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        val agent = strategyAgent(db, fake, StrategyType.BRANCHING, n = 10)
        try {
            runBlocking {
                assertIs<AgentResult.Success>(agent.ask("общее начало ТЗ"))
            }
            val a = agent.createBranch("kmp-вариант")
            runBlocking {
                assertIs<AgentResult.Success>(agent.ask("выбираем KMP"))
            }
            val aHistory = agent.historySnapshot().map { it.second }
            assertTrue(aHistory.any { it.contains("KMP") })

            // Вторая ветка от того же места? Эмулируем: назад на main, форк второй.
            assertTrue(agent.switchBranch(null))
            val b = agent.createBranch("flutter-вариант")
            runBlocking {
                assertIs<AgentResult.Success>(agent.ask("выбираем Flutter"))
            }
            val bHistory = agent.historySnapshot().map { it.second }
            assertTrue(bHistory.any { it.contains("Flutter") })
            assertTrue(bHistory.none { it.contains("KMP") })

            // Переключение назад в A возвращает KMP-контекст.
            assertTrue(agent.switchBranch(a.id))
            val backA = agent.historySnapshot().map { it.second }
            assertTrue(backA.any { it.contains("KMP") })
            assertTrue(backA.none { it.contains("Flutter") })

            // Реестр веток сохранился в БД (таблица branch), переживает рестарт.
            assertEquals(2, agent.strategySnapshot().branches.size)
            val branchRepo = SqliteBranchRepository(db)
            try {
                assertEquals(2, branchRepo.list().size)
            } finally {
                // SqliteBranchRepository пула не держит, закрывать нечего.
            }
            // Ветки лежат в SQLite под branch: id.
            val raw: ChatRepository = SqliteChatRepository(db)
            try {
                assertTrue(raw.load(b.conversationId).isNotEmpty())
            } finally {
                raw.close()
            }
        } finally {
            runBlocking { agent.close() }
        }
    }

    @Test
    fun `nested branch builds slash path and blank name autogenerates`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        val agent = strategyAgent(db, fake, StrategyType.BRANCHING, n = 10)
        try {
            runBlocking { assertIs<AgentResult.Success>(agent.ask("общее")) }
            val a = agent.createBranch("тексты")
            assertEquals(null, a.parentId)
            val b1 = agent.createBranch("")
            val b2 = agent.createBranch("")
            // Автоимена уникальны и не пустые.
            assertTrue(b1.name.startsWith("branch-"))
            assertTrue(b2.name.startsWith("branch-"))
            assertTrue((setOf(a.name, b1.name, b2.name)).size == 3)
            // Ветка от ветки: родитель — активная (b2), путь через слеш.
            val c = agent.createBranch("правки")
            assertEquals(b2.id, c.parentId)
            val all = agent.strategySnapshot().branches
            assertEquals("тексты", branchPath(all, a))
            assertTrue(branchPath(all, c).contains("/"))
            assertTrue(branchPath(all, c).endsWith("правки"))
        } finally {
            runBlocking { agent.close() }
        }
    }

    @Test
    fun `clear in branch truncates to fork point`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        val agent = strategyAgent(db, fake, StrategyType.BRANCHING, n = 10)
        try {
            runBlocking {
                assertIs<AgentResult.Success>(agent.ask("раз"))
                assertIs<AgentResult.Success>(agent.ask("два"))
            }
            // 4 живых на main; форк запоминает fromSize=4.
            val a = agent.createBranch("a")
            assertEquals(4, a.fromSize)
            runBlocking { assertIs<AgentResult.Success>(agent.ask("в ветке")) }
            assertEquals(6, agent.historyWithTokens().size)
            agent.clearHistory()
            // Откат к форку: 4 живых + system, ветка жива.
            assertEquals(4, agent.historyWithTokens().size)
            assertEquals(a.id, agent.strategySnapshot().activeBranchId)
            assertEquals(1, agent.strategySnapshot().branches.size)
        } finally {
            runBlocking { agent.close() }
        }
    }

    @Test
    fun `clear on main deletes all branches`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        val agent = strategyAgent(db, fake, StrategyType.BRANCHING, n = 10)
        try {
            runBlocking { assertIs<AgentResult.Success>(agent.ask("общее")) }
            val a = agent.createBranch("a")
            assertTrue(agent.switchBranch(null))
            agent.createBranch("b")
            assertEquals(2, agent.strategySnapshot().branches.size)
            assertTrue(agent.switchBranch(null))
            agent.clearHistory()
            assertTrue(agent.strategySnapshot().branches.isEmpty())
            assertEquals(0, agent.historyWithTokens().size)
            // Сообщения веток тоже удалены из SQLite.
            val raw: ChatRepository = SqliteChatRepository(db)
            try {
                assertTrue(raw.load(a.conversationId).isEmpty())
            } finally {
                raw.close()
            }
            assertTrue(SqliteBranchRepository(db).list().isEmpty())
        } finally {
            runBlocking { agent.close() }
        }
    }

    @Test
    fun `delete branch removes data and reparents children`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        val agent = strategyAgent(db, fake, StrategyType.BRANCHING, n = 10)
        try {
            runBlocking { assertIs<AgentResult.Success>(agent.ask("общее")) }
            val a = agent.createBranch("a")
            val c = agent.createBranch("child")
            assertEquals(a.id, c.parentId)
            // Удаление родителя: ребёнок переподвешивается на main (parent=null).
            assertTrue(agent.deleteBranch(a.id))
            val after = agent.strategySnapshot()
            assertEquals(1, after.branches.size)
            assertEquals(null, after.branches.first().parentId)
            assertEquals("child", branchPath(after.branches, after.branches.first()))
            // Удаление активной ветки переключает на main.
            assertEquals(c.id, after.activeBranchId)
            assertTrue(agent.deleteBranch(c.id))
            assertEquals(null, agent.strategySnapshot().activeBranchId)
            assertTrue(agent.strategySnapshot().branches.isEmpty())
            // Несуществующая и main (null) не удаляются.
            assertTrue(!agent.deleteBranch("nope"))
        } finally {
            runBlocking { agent.close() }
        }
    }

    @Test
    fun `branches survive restart via db`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        val agent = strategyAgent(db, fake, StrategyType.BRANCHING, n = 10)
        runBlocking {
            assertIs<AgentResult.Success>(agent.ask("общее"))
            agent.createBranch("a")
            agent.close()
        }
        val agent2 = Agent(dbFile = db)
        try {
            assertEquals(1, agent2.strategySnapshot().branches.size)
            assertEquals("a", agent2.strategySnapshot().branches.first().name)
        } finally {
            runBlocking { agent2.close() }
        }
    }

    @Test
    fun `strategy choice survives restart via file`() {
        val db = tempDb()
        val fake = FakeLlmClient()
        val agent = strategyAgent(db, fake, StrategyType.FACTS, n = 4)
        try {
            agent.setStrategy(StrategyType.BRANCHING)
            assertEquals(StrategyType.BRANCHING, StrategyStore.load(db.parentFile))
            runBlocking { agent.close() }
            val agent2 = Agent(dbFile = db)
            try {
                assertEquals(StrategyType.BRANCHING, agent2.strategySnapshot().strategy)
            } finally {
                runBlocking { agent2.close() }
            }
        } finally {
            try {
                runBlocking { agent.close() }
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun `cleared branches do not resurrect from legacy json on restart`() {
        val db = tempDb()
        // Легаси-файл с мусором при пустой таблице: разово импортируется и удаляется.
        BranchStore.save(
            db.parentFile,
            listOf(Branch(id = "z1", name = "zombie", conversationId = "branch:z1")),
        )
        val agent = Agent(dbFile = db)
        try {
            assertEquals(1, agent.strategySnapshot().branches.size)
            agent.clearHistory() // на main: сносит реестр
            assertTrue(agent.strategySnapshot().branches.isEmpty())
        } finally {
            runBlocking { agent.close() }
        }
        // Файл-источник удалён — рестарт ничего не воскрешает.
        assertTrue(!BranchStore.fileFor(db.parentFile).exists())
        val agent2 = Agent(dbFile = db)
        try {
            assertTrue(agent2.strategySnapshot().branches.isEmpty())
        } finally {
            runBlocking { agent2.close() }
        }
    }
}

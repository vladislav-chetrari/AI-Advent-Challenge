package agent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentTest {
    @Test
    fun `empty prompt is rejected`() {
        runBlocking {
            Agent.apiKeyOverride = "dummy-for-validation"
            try {
                val agent = Agent()
                val result = agent.ask("   ")
                assertIs<AgentResult.Failure>(result)
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
                val agent = Agent()
                val result = agent.ask("ping")
                assertIs<AgentResult.Failure>(result)
                assertIs<AgentError.MissingKey>(result.error)
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
}

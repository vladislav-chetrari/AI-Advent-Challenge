package task3.data

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient

actual fun createDeepSeekExecutor(apiKey: String): LLMClient = DeepSeekLLMClient(apiKey)

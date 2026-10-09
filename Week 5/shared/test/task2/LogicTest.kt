package task2

import task2.data.FactExtractor
import task2.data.ModelCatalog
import task2.data.PromptBuilder
import task2.data.RoomMemoryStore
import task2.domain.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogicTest {
    @Test
    fun defaultModelIsQwen317b() {
        assertEquals("qwen3-1.7b-q4", ModelCatalog.DEFAULT.id)
        assertEquals(3, ModelCatalog.ALL.size)
    }

    @Test
    fun promptUsesQwenChatMl() {
        val history = listOf(ChatMessage(1, ChatMessage.Role.USER, "привет"))
        val p = PromptBuilder.build(history, "как дела?")
        assertTrue(p.contains("<|im_start|>system"))
        assertTrue(p.contains("<|im_start|>user\nкак дела?"))
        assertTrue(p.endsWith(PromptBuilder.NO_THINK_SUFFIX))
    }

    @Test
    fun promptDetectsLanguageFromDialogue() {
        val p = PromptBuilder.build(emptyList(), "Hello, how are you?")
        assertTrue(p.contains("на том же языке"))
        assertTrue(!p.contains("Отвечай по-русски"))
        assertTrue(p.contains("/no_think"))
    }

    @Test
    fun cleanReplyStripsThink() {
        assertEquals(
            "Привет! Как дела?",
            PromptBuilder.cleanReply("<think>\nрассуждаю\n</think>\nПривет! Как дела?"),
        )
        assertEquals(
            "Привет!",
            PromptBuilder.cleanReply("Привет!<|im_end|>"),
        )
        assertEquals(
            "начало",
            PromptBuilder.cleanReply("начало<think>оборвали"),
        )
    }

    @Test
    fun slidingWindowKeepsLast10() {
        val history = (0..13).map { i ->
            val role = if (i % 2 == 0) ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT
            ChatMessage(i.toLong(), role, "msg-$i")
        }
        val p = PromptBuilder.build(history, "вопрос")
        assertTrue(p.contains("msg-13"))
        assertTrue(p.contains("msg-4"))
        assertTrue(!p.contains("msg-3"), "окно 10 должно вытеснить старые реплики")
    }

    @Test
    fun factsInjectedIntoSystem() {
        val p = PromptBuilder.build(
            history = emptyList(),
            userPrompt = "как меня зовут?",
            facts = listOf("Имя пользователя — Влад"),
        )
        assertTrue(p.contains("Факты о собеседнике"))
        assertTrue(p.contains("- Имя пользователя — Влад"))
        assertTrue(p.indexOf("Факты о собеседнике") < p.indexOf("<|im_start|>user"))
    }

    @Test
    fun extractNameRu() {
        assertEquals(
            listOf("Имя пользователя — Влад"),
            FactExtractor.extractFrom("Меня зовут Влад"),
        )
    }

    @Test
    fun extractNameEn() {
        assertEquals(
            listOf("Имя пользователя — Vlad"),
            FactExtractor.extractFrom("My name is Vlad, remember it"),
        )
    }

    @Test
    fun extractLikes() {
        assertEquals(
            listOf("Нравится: кофе"),
            FactExtractor.extractFrom("Я люблю кофе"),
        )
        assertEquals(
            listOf("Нравится: coffee"),
            FactExtractor.extractFrom("I love coffee"),
        )
    }

    @Test
    fun extractRemember() {
        assertEquals(
            listOf("я работаю врачом"),
            FactExtractor.extractFrom("запомни: я работаю врачом"),
        )
    }

    @Test
    fun extractIgnoresChitchat() {
        assertEquals(emptyList(), FactExtractor.extractFrom("Привет, как дела?"))
        assertEquals(emptyList(), FactExtractor.extractFrom("I'm fine today"))
        assertEquals(emptyList(), FactExtractor.extractFrom("remember it"))
    }

    @Test
    fun sameFactDedupesCaseInsensitive() {
        assertTrue(RoomMemoryStore.sameFact("Меня зовут Влад", "меня зовут влад"))
        assertTrue(!RoomMemoryStore.sameFact("Любит кофе", "Любит чай"))
    }
}

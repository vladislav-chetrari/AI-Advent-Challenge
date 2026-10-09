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
    fun defaultModelIsQwen2515b() {
        assertEquals("qwen25-1.5b-q8", ModelCatalog.DEFAULT.id)
        assertEquals(2, ModelCatalog.ALL.size)
    }

    @Test
    fun chatPromptHasNoChatMl() {
        val history = listOf(ChatMessage(1, ChatMessage.Role.USER, "привет"))
        val system = PromptBuilder.chatSystem()
        val user = PromptBuilder.chatUser(history, "как дела?")
        assertTrue(!system.contains("<|im_start|>"), "LiteRT сам применяет шаблон")
        assertTrue(!user.contains("<|im_start|>"))
        assertTrue(user.contains("Пользователь: как дела?"))
        assertTrue(user.contains("Пользователь: привет"))
    }

    @Test
    fun promptDetectsLanguageFromDialogue() {
        val s = PromptBuilder.chatSystem()
        assertTrue(s.contains("на том же языке"))
        assertTrue(!s.contains("Отвечай по-русски"))
        assertTrue(!s.contains("/no_think"))
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
        val u = PromptBuilder.chatUser(history, "вопрос")
        assertTrue(u.contains("msg-13"))
        assertTrue(u.contains("msg-4"))
        assertTrue(!u.contains("msg-3"), "окно 10 должно вытеснить старые реплики")
    }

    @Test
    fun factsInjectedIntoSystem() {
        val s = PromptBuilder.chatSystem(facts = listOf("Имя пользователя — Влад"))
        assertTrue(s.contains("Факты о собеседнике"))
        assertTrue(s.contains("- Имя пользователя — Влад"))
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

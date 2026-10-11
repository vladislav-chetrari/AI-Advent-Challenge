package task3

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import task3.data.QueryRouter

/** Rule-роутер small-talk: приветствия — свободно, вопросы — в retrieval. */
class QueryRouterTest {
    @Test
    fun greetingsAreSmallTalk() {
        assertTrue(QueryRouter.isSmallTalk("привет"))
        assertTrue(QueryRouter.isSmallTalk("Привет!"))
        assertTrue(QueryRouter.isSmallTalk("как дела?"))
        assertTrue(QueryRouter.isSmallTalk("спасибо"))
        assertTrue(QueryRouter.isSmallTalk("hello"))
        assertTrue(QueryRouter.isSmallTalk("hi"))
    }

    @Test
    fun questionsAreNotSmallTalk() {
        assertFalse(QueryRouter.isSmallTalk("расскажи про воронов"))
        assertFalse(QueryRouter.isSmallTalk("расскажи про дроздов"))
        assertFalse(QueryRouter.isSmallTalk("привет, расскажи про воронов"))
        assertFalse(QueryRouter.isSmallTalk(""))
        assertFalse(QueryRouter.isSmallTalk("а вороны крупнее воронов?"))
    }
}

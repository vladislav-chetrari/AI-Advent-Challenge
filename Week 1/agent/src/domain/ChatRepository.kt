package agent.domain

// ISP: маленький интерфейс, data-слой зависит от него, а не наоборот.
interface ChatRepository {
    fun load(conversationId: String = DEFAULT_CONVERSATION): List<ChatMessage>
    fun replaceAll(conversationId: String = DEFAULT_CONVERSATION, messages: List<ChatMessage>)
    fun clear(conversationId: String = DEFAULT_CONVERSATION)
    fun close()

    companion object {
        const val DEFAULT_CONVERSATION = "default"
    }
}

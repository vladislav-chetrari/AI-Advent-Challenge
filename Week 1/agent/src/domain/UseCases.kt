package agent.domain

// KISS: тонкие юзкейсы поверх репозитория, вся бизнес-логика диалога — в Agent.
class LoadHistoryUseCase(private val repo: ChatRepository) {
    fun execute(conversationId: String = ChatRepository.DEFAULT_CONVERSATION): List<ChatMessage> =
        repo.load(conversationId)
}

class ClearHistoryUseCase(private val repo: ChatRepository) {
    fun execute(conversationId: String = ChatRepository.DEFAULT_CONVERSATION) =
        repo.clear(conversationId)
}

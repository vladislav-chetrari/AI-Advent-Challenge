package agent.data.db

import agent.domain.ChatMessage
import agent.domain.ChatRepository
import java.io.File
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// KISS: вся персистентность — replaceAll/load через Exposed DSL, транзакции короткие.
class SqliteChatRepository(
    dbFile: File = DatabaseFactory.defaultDbFile(),
) : ChatRepository {
    private val db: Database = DatabaseFactory.open(dbFile)

    override fun load(conversationId: String): List<ChatMessage> = transaction(db) {
        ensureConversation(conversationId)
        ChatMessages.selectAll()
            .where { ChatMessages.conversationId eq conversationId }
            .orderBy(ChatMessages.id)
            .map { ChatMessage(role = it[ChatMessages.role], content = it[ChatMessages.content]) }
    }

    override fun replaceAll(conversationId: String, messages: List<ChatMessage>) {
        transaction(db) {
            ensureConversation(conversationId)
            ChatMessages.deleteWhere { ChatMessages.conversationId eq conversationId }
            if (messages.isNotEmpty()) {
                ChatMessages.batchInsert(messages) { m ->
                    this[ChatMessages.conversationId] = conversationId
                    this[ChatMessages.role] = m.role
                    this[ChatMessages.content] = m.content
                    this[ChatMessages.createdAt] = System.currentTimeMillis()
                }
            }
        }
    }

    override fun clear(conversationId: String) {
        transaction(db) {
            ChatMessages.deleteWhere { ChatMessages.conversationId eq conversationId }
        }
    }

    override fun close() {
        // Exposed поверх DriverManager: пула нет, закрывать нечего.
        // Метод оставлен по ISP, чтобы upper-слои не зависели от деталей драйвера.
    }

    private fun ensureConversation(conversationId: String) {
        val exists = Conversations.selectAll()
            .where { Conversations.id eq conversationId }
            .empty().not()
        if (!exists) {
            Conversations.insert {
                it[id] = conversationId
                it[title] = conversationId
                it[createdAt] = System.currentTimeMillis()
            }
        }
    }
}

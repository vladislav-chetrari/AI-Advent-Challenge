package agent.data.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

// Отражает состояние после ВСЕХ миграций (V1+V2). Схему создаёт Flyway, а не Exposed.
object Conversations : Table("conversation") {
    val id = text("id")
    val title = text("title")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ChatMessages : Table("chat_message") {
    val id = integer("id").autoIncrement()
    val conversationId = text("conversation_id").references(Conversations.id, onDelete = ReferenceOption.CASCADE)
    val role = text("role")
    val content = text("content")
    val createdAt = long("created_at")
    val model = text("model").nullable()
    override val primaryKey = PrimaryKey(id)
}

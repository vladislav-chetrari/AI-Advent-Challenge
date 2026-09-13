package agent.data.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

// Отражает состояние после ВСЕХ миграций (V1..V5). Схему создаёт Flyway, а не Exposed.
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
    val promptTokens = integer("prompt_tokens").default(0)
    val completionTokens = integer("completion_tokens").default(0)
    val totalTokens = integer("total_tokens").default(0)
    val tokens = integer("tokens").default(0)
    val costUsd = double("cost_usd").default(0.0)
    override val primaryKey = PrimaryKey(id)
}

// V5: реестр веток диалога. parent_id строит граф, conversation_id ссылается
// на conversation(id) — сообщения ветки лежат в chat_message под этим id.
object Branches : Table("branch") {
    val id = text("id")
    val name = text("name")
    val parentId = text("parent_id").nullable()
    val conversationId = text("conversation_id")
    val fromSize = integer("from_size").default(0)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

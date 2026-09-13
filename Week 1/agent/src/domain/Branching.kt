package agent.domain

import kotlinx.serialization.Serializable

// Task 5, стратегия 3: Branching (ветки диалога).
// Каждая ветка — отдельный conversationId в той же таблице chat_message:
// main -> "default", ветки -> "branch:<id>". Реестр веток — в таблице branch
// (см. V5__create_branch.sql): parentId строит граф (ветка от ветки),
// сообщения ссылаются на ветку через conversation_id.
@Serializable
data class Branch(
    val id: String,
    val name: String,
    val conversationId: String,
    val parentId: String? = null,
    val fromSize: Int = 0,
    val createdAt: Long = 0L,
) {
    companion object {
        const val PREFIX: String = "branch:"

        fun convId(id: String): String = "$PREFIX$id"

        fun isBranchConv(conversationId: String): Boolean =
            conversationId.startsWith(PREFIX)
    }
}

/** Путь ветки в графе через слеш: "тексты / правки". Циклы рвутся посещёнными. */
fun branchPath(all: List<Branch>, branch: Branch): String {
    val byId = all.associateBy { it.id }
    val parts = ArrayDeque<String>()
    val seen = mutableSetOf<String>()
    var cur: Branch? = branch
    while (cur != null && seen.add(cur.id)) {
        parts.addFirst(cur.name)
        cur = cur.parentId?.let { byId[it] }
    }
    return parts.joinToString(" / ")
}

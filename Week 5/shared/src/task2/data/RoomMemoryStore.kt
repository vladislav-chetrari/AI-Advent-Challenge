package task2.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import task2.db.ChatDao
import task2.db.FactEntity
import task2.domain.ConversationFact
import task2.domain.MemoryStore

/**
 * Факты в Room (таблица facts) вместо memory.json.
 * Интерфейс MemoryStore не менялся: происхождение фактов
 * (правила сейчас, LLM позже) для репозитория прозрачно.
 */
class RoomMemoryStore(
    private val dao: ChatDao,
    private val maxFacts: Int = 20,
) : MemoryStore {
    private val _facts = MutableStateFlow<List<ConversationFact>>(emptyList())
    override val facts: StateFlow<List<ConversationFact>> = _facts.asStateFlow()

    override suspend fun load() {
        runCatching {
            _facts.value = dao.getFacts()
                .takeLast(maxFacts)
                .map { ConversationFact(it.text, it.createdAt) }
        }
    }

    override suspend fun addFacts(texts: List<String>) {
        val fresh = texts.map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { cand -> _facts.value.none { sameFact(it.text, cand) } }
        if (fresh.isEmpty()) return
        val now = nowMillis()
        runCatching { dao.insertFacts(fresh.map { FactEntity(text = it, createdAt = now) }) }
        runCatching { dao.trimFacts(maxFacts) }
        _facts.update { (_facts.value + fresh.map { ConversationFact(it, now) }).takeLast(maxFacts) }
    }

    override suspend fun clear() {
        runCatching { dao.clearFacts() }
        _facts.value = emptyList()
    }

    companion object {
        /** Дедуп без учёта регистра: "Меня зовут Влад" == "меня зовут влад". */
        fun sameFact(a: String, b: String): Boolean {
            val x = a.trim().lowercase()
            val y = b.trim().lowercase()
            return x.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
        }
    }
}

expect fun nowMillis(): Long

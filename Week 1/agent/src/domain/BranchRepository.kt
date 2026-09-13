package agent.domain

// ISP: реестр веток отдельно от истории сообщений. Реализация — SQLite (V5).
interface BranchRepository {
    fun list(): List<Branch>
    fun upsert(branch: Branch)
    fun delete(id: String)
    fun clear()
}

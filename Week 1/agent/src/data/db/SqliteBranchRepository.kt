package agent.data.db

import agent.domain.Branch
import agent.domain.BranchRepository
import java.io.File
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// KISS: CRUD реестра веток через Exposed DSL, короткие транзакции.
// Делит файл БД с SqliteChatRepository (Flyway-миграции идемпотентны).
class SqliteBranchRepository(
    dbFile: File = DatabaseFactory.defaultDbFile(),
) : BranchRepository {
    private val db: Database = DatabaseFactory.open(dbFile)

    override fun list(): List<Branch> = transaction(db) {
        Branches.selectAll().map {
            Branch(
                id = it[Branches.id],
                name = it[Branches.name],
                conversationId = it[Branches.conversationId],
                parentId = it[Branches.parentId],
                fromSize = try { it[Branches.fromSize] } catch (_: Exception) { 0 },
                createdAt = it[Branches.createdAt],
            )
        }
    }

    override fun upsert(branch: Branch) {
        transaction(db) {
            Branches.deleteWhere { Branches.id eq branch.id }
            Branches.insert {
                it[id] = branch.id
                it[name] = branch.name
                it[parentId] = branch.parentId
                it[conversationId] = branch.conversationId
                it[fromSize] = branch.fromSize
                it[createdAt] = branch.createdAt
            }
        }
    }

    override fun delete(id: String) {
        transaction(db) {
            Branches.deleteWhere { Branches.id eq id }
        }
    }

    override fun clear() {
        transaction(db) {
            Branches.deleteWhere { Op.TRUE }
        }
    }
}

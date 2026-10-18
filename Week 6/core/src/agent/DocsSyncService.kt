package core.agent

import core.rag.RagService
import core.rag.buildDocsSyncSystem
import core.rag.buildDocsSyncUser
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ===== Task 5: оркестрация docs-sync =====
//
// Слой между git и агентом Task 4. Он делает четыре вещи, которых у голого
// агента нет:
//
//  1. СКАЗЫВАЕТ, ЧТО ИМЕННО ИЗМЕНИЛОСЬ. Самостоятельный git diff в агенте
//     означал бы, что модель тратит шаги на разведку. Здесь список файлов
//     приходит готовым — остаётся работа по существу.
//  2. ВЫБИРАЕТ КАНДИДАТОВ-доков детерминированно (grep по именам файлов +
//     приоритет docs/ и README). Это единственная часть, которую нельзя
//     отдать модели: она не знает структуру проекта.
//  3. ОГРАНИЧИВАЕТ ЗАПИСЬ документацией через DocsOnlyGate.
//  4. Собирает РЕЗУЛЬТАТ для PR: список файлов + полные содержимое, потому
//     что VPS отдаёт их по HTTP, а Action применяет у себя.
//
// Чего здесь нет намеренно: своей петли агента. Цикл, редьюсер, бюджет и
// разбор вызовов — из Task 4. Дублировать их ради одной задачи — значит
// завести две реализации одного, которые со временем разъедутся.

/**
 * Один вызов агента под нужной ролью.
 *
 * Функция, а не прямой вызов AgentRunner, — чтобы verify-docs прогонял весь
 * слой docs-sync без сети и без LLM: подменяем один этот вызов.
 */
class DocsSyncService(
    private val rag: RagService,
    private val config: AgentConfig = AgentConfig(),
    private val onLog: (String) -> Unit = {},
) {

    /**
     * Полный прогон: изменения -> кандидаты -> агент -> результат для PR.
     *
     * Ничего не бросает наружу: ошибка агента или сети возвращается полем
     * [DocsSyncResult.error], чтобы Action показал её в логе и не открыл
     * пустой PR.
     */
    suspend fun run(
        project: String,
        workspace: Workspace,
        changeSet: DocsChangeSet,
        apiKey: String? = null,
        maxDocCandidates: Int = 8,
        dryRun: Boolean = false,
        onProgress: (String) -> Unit = onLog,
    ): DocsSyncResult {
        if (!changeSet.worthRunning) {
            onProgress("пропуск: ${changeSet.note}")
            return DocsSyncResult(
                project = project,
                base = changeSet.base,
                head = changeSet.head,
                changeNote = changeSet.note,
                skipped = true,
                skipReason = changeSet.note,
            )
        }
        val candidates = DocsSync.candidateDocs(workspace, changeSet, maxDocCandidates)
        onProgress("изменено файлов кода: ${changeSet.codeFiles.size}; кандидаты-доки: ${candidates.size}")
        candidates.forEach { onProgress("  · $it") }

        // dry-run: git и подбор кандидатов уже сделаны — LLM не зовём, файлы
        // не трогаем. Это дымовой тест в CI без DEEPSEEK_API_KEY.
        if (dryRun) {
            onProgress("dry-run: модель не вызывается, файлы не меняются")
            return DocsSyncResult(
                project = project,
                base = changeSet.base,
                head = changeSet.head,
                changedCodeFiles = changeSet.codeFiles.map { it.path },
                candidateDocs = candidates,
                changeNote = changeSet.note,
                skipped = true,
                skipReason = "dry-run: изменения разобраны, агент не запускался",
            )
        }

        val runStore = RunStore()
        val runner = AgentRunner(
            rag = rag,
            workspace = workspace,
            runStore = runStore,
            gate = DocsOnlyGate(),
            config = config,
            onUpdate = {},
            onLog = onProgress,
        )
        val run = try {
            runner.run(
                goal = DEFAULT_GOAL,
                project = project,
                apiKey = apiKey ?: rag.resolveApiKey(),
                systemPrompt = { tools -> buildDocsSyncSystem(project, tools, candidates) },
                userPrompt = { ctx -> buildDocsSyncUser(DEFAULT_GOAL, ctx, changeSet.codeFiles, candidates) },
            )
        } catch (e: Exception) {
            val msg = e.message?.take(200) ?: (e::class.simpleName ?: "ошибка агента")
            onProgress("агент не отработал: $msg")
            return DocsSyncResult(
                project = project,
                base = changeSet.base,
                head = changeSet.head,
                changedCodeFiles = changeSet.codeFiles.map { it.path },
                changeNote = changeSet.note,
                skipped = true,
                skipReason = "агент не отработал: $msg",
                error = msg,
            )
        }

        val updates = collectUpdates(workspace, run.changedFiles)
        val failed = run.state is RunState.Failed
        // Отказ по гейту — ожидаемый исход, а не авария: значит, агент
        // попытался править не-документацию. Говорим об этом прямо в PR,
        // иначе выглядит как «сломалась синхронизация».
        val warning = if (failed && run.error.contains(DENIED_MARK)) {
            "Агент попытался изменить файл вне документации — правка отклонена гейтом, код не тронут."
        } else {
            ""
        }
        val result = DocsSyncResult(
            project = project,
            base = changeSet.base,
            head = changeSet.head,
            changedCodeFiles = changeSet.codeFiles.map { it.path },
            updatedFiles = run.changedFiles.filter { DocsSync.isDocPath(it) },
            updates = updates,
            diff = run.diff,
            summary = run.summary,
            candidateDocs = candidates,
            changeNote = changeSet.note,
            skipped = updates.isEmpty(),
            skipReason = if (updates.isEmpty()) {
                "агент не внёс правок в документацию" +
                    (if (run.summary.isBlank()) "" else ": ${run.summary.take(120)}")
            } else {
                ""
            },
            warning = warning,
            runId = run.id,
            steps = run.steps,
            error = if (failed) run.error else "",
        )
        return result.copy(
            prTitle = DocsSyncPr.title(result.changedCodeFiles.size, project),
            prBody = DocsSyncPr.body(result),
        )
    }

    /** Откат по снапшотам: возвращает клону проекта исходное состояние. */
    fun revert(result: DocsSyncResult, workspace: Workspace): Int {
        if (result.runId.isBlank()) return 0
        val store = RunStore()
        val run = store.get(result.runId) ?: return 0
        return store.revert(run, workspace)
    }

    /**
     * Содержимое изменённых файлов — читается ДО отката.
     *
     * Порядок важен: агент пишет прямо в рабочую папку проекта (на VPS это
     * клон репозитория), а вернуть наружу надо ИТОГОВОЕ содержимое.
     */
    private fun collectUpdates(workspace: Workspace, files: List<String>): List<DocFileUpdate> =
        files.filter { DocsSync.isDocPath(it) }.mapNotNull { path ->
            try {
                DocFileUpdate(path, workspace.readText(path))
            } catch (_: Exception) {
                null // файл исчез между правкой и сбором — не пишем мусор
            }
        }

    companion object {
        const val DEFAULT_GOAL =
            "Синхронизируй документацию проекта с изменениями в коде. " +
                "Правь только те документы, где изменения отражены на поведении."

        // Подстрока, которой PermissionGate помечает отказ (см. AgentRunner:
        // "изменение X отклонено"). Нужна, чтобы отличить отказ гейта от сети.
        const val DENIED_MARK = "отклонено"
    }
}

/**
 * Сбор изменений + прогон одним вызовом — так это делают CLI и сервер.
 *
 * onLog получает и сводку по git, и журнал агента: в CI это единственное,
 * что видно, пока прогон идёт несколько минут.
 */
suspend fun syncDocs(
    service: DocsSyncService,
    project: String,
    dir: File,
    base: String?,
    head: String = "HEAD",
    apiKey: String? = null,
    maxDocCandidates: Int = 8,
    dryRun: Boolean = false,
    onLog: (String) -> Unit = {},
): DocsSyncResult = withContext(Dispatchers.IO) {
    val changeSet = DocsSync.changedFiles(dir, base, head)
    onLog("изменения ${changeSet.base.ifBlank { "(рабочее дерево)" }}...${changeSet.head}: ${changeSet.note}")
    service.run(
        project = project,
        workspace = Workspace(dir),
        changeSet = changeSet,
        apiKey = apiKey,
        maxDocCandidates = maxDocCandidates,
        dryRun = dryRun,
        onProgress = onLog,
    )
}
# Core API (`core/`)

## `ChatService` (`core/src/ChatService.kt:42`) — фасад, всё через него

### Создание дерева

- `createProject(name): Project` — только узел проекта, без стартовых доков/чатов (`core/src/ChatService.kt:55`)
- `createTask(projectId, name): Task` — узел задачи + сразу `TaskState(PLANNING, ACTIVE, defaultStepsFor(PLANNING))` (`core/src/ChatService.kt:61`)
- `createChat(name, scope, parentId): Chat` — `parentId`: `projectId` для `PROJECT`, `taskId` для `TASK`, `null` для `GENERAL` (`core/src/ChatService.kt:73`)
- `createMemoryDoc(title, scope, parentId): MemoryDoc` — дедуп по `title` в том же `scope+parent` (`core/src/ChatService.kt:79`)

### Память

- `relevantDocs(chat): List<MemoryDoc>` — наследование `GENERAL → PROJECT → TASK`, только `active=true` (`core/src/ChatService.kt:136`)
- `relevantInvariants(chat): List<InvariantDoc>` — то же наследование, но `GENERAL`-чатам всегда пусто (`core/src/ChatService.kt:163`)
- `invariantsTextForChat(chat): String` — склейка активных инвариантов, cap 3200 симв (`core/src/ChatService.kt:183`)
- `buildSystemPrompt(chat): String` — `relevantDocs` + `activeProfile()` + `TaskState` (только TASK-чаты) + `invariantsTextForChat` → `PromptBuilder.buildSystemPrompt` (`core/src/ChatService.kt:187`)
- `docContent(doc): String`, `toggleDocActive(docId)`, `deleteDoc(docId)` (`core/src/ChatService.kt:91`)
- `commitFact(docId, fact)` — `Store.appendFact` (дедуп, `core/src/ChatService.kt:329`)
- `lastSystemPrompt`, `lastPromptTokens` — для UI/отладки (`core/src/ChatService.kt:46`)

### Профили — День 12 (`core/src/ChatService.kt:201`)

- `activeProfile(): UserProfile?` — по `state.activeProfileId`, `null` = Аноним
- `createProfile(name, style, format, constraints): UserProfile` — сразу делает активным (`core/src/ChatService.kt:208`)
- `updateProfile(id, name, style, format, constraints)` (`core/src/ChatService.kt:220`)
- `deleteProfile(id)` — чистит `activeProfileId`, если удалён активный (`core/src/ChatService.kt:233`)
- `setActiveProfile(id: String?)` — `null` = Аноним; чужой `id` игнорируется (`core/src/ChatService.kt:242`)

Поля `UserProfile` (`core/src/domain/Models.kt:77`): `name` ≤60, `style/format/constraints` ≤500 каждое.

### Инварианты — День 14 (`core/src/ChatService.kt:104`)

- `createInvariantDoc(title, scope, parentId): InvariantDoc` — дедуп по `title` в том же `scope+parent`, контент-файл создаётся пустым (`core/src/ChatService.kt:106`)
- `toggleInvariantActive(docId)`, `deleteInvariant(docId)`, `invariantContent(doc): String` (`core/src/ChatService.kt:117`)
- `saveInvariantContent(docId, content)` — прямая запись текста ≤8000 симв, без дистилляции (`core/src/ChatService.kt:130`)
- `validateAgainstInvariants(taskId, chatId): ValidationResult` (suspend) — инварианты + все сообщения EXECUTION → `VALIDATE_SYSTEM` → `parseValidationJson`; без инвариантов/сообщений/ключа — `SUCCESS` с поясняющим `note`, результат пишется в `TaskState.lastValidation` (`core/src/ChatService.kt:656`)
- `advanceToValidationWithCheck(taskId, chatId, reason): ValidationResult?` — переход `EXECUTION→VALIDATION` + автопроверка (`core/src/ChatService.kt:726`)
- `retryExecution(taskId, reason): Boolean` — только `VALIDATION→EXECUTION` (`TaskStateMachine.canRetry`), шаги сбрасываются в `done=false`, `lastValidation` сохраняется для UI (`core/src/ChatService.kt:807`)
- `replanTask(taskId, reason): Boolean` — любой этап (кроме DONE/PLANNING) → PLANNING с дефолтными шагами + `lastValidation=null`; реплики чистит вызывающий код (`clearTaskChats`) (`core/src/ChatService.kt:786`)
- `clearTaskChats(taskId)` — удалить ВСЕ реплики всех чатов задачи (для REPLAN) (`core/src/ChatService.kt:739`); `clearExecutionReplies(taskId)` — удалить только `[авто]`-пары user+assistant (для RETRY, планировочный диалог сохраняется) (`core/src/ChatService.kt:758`)

### Диалог

- `appendUserMessage(chat, text)` (sync) — дописать user-сообщение в стор (`core/src/ChatService.kt:256`)
- `completeAsk(chat): AskResult` (suspend) — `ApiKeyProvider.resolve()` → `buildSystemPrompt` → `effectiveHistory(N=12)` → `LlmClient.complete` → дописать assistant; при HTTP/сетевой ошибке или отсутствии ключа user-сообщение откатывается (`core/src/ChatService.kt:270`). `Success(text, promptTokens)` / `Failure(message)` (`core/src/ChatService.kt:36`)
- `ask(chat, prompt): AskResult` — тонкая обёртка: `appendUserMessage` + `completeAsk` (эквивалент старого поведения одним вызовом) (`core/src/ChatService.kt:306`). Разбиение нужно UI для optimistic echo: `AppViewModel.send` кладёт сообщение синхронно и делает `refresh` до ответа LLM
- `clearChat(chatId)` — стереть историю чата (память и профили не трогает) (`core/src/ChatService.kt:248`)
- `distill(raw): List<String>` — сырец → 1–3 факта через `DISTILL_SYSTEM` (`core/src/ChatService.kt:315`, `core/src/domain/Memory.kt:7`)

Состояние: `state(): AppState`, `store: Store` открыт для `ViewModel`.

## Task State Machine — День 13 (`core/src/ChatService.kt:333`, `core/src/domain/TaskState.kt:77`)

- `getTaskState(taskId): TaskState?`, `ensureTaskState(taskId): TaskState` — ленивая инициализация (`core/src/ChatService.kt:335`)
- `updateTaskState(taskId, fn)` — точечное обновление + `updatedAt` (`core/src/ChatService.kt:344`)
- `advanceTask(taskId, reason, rawPlan): Boolean` — только вперёд по `canTransition`, `DONE` терминален; блок `PLANNING` сохраняет чистый план в док `план` (`core/src/ChatService.kt:492`)
- `finalizePlanAndAdvance(taskId, chatId, reason): Boolean` (suspend) — главный переход `planning → execution`: дистилляция ВСЕГО разговора (`distillPlanFromChat`, `PLAN_DISTILL_SYSTEM`) → `setTaskSteps` → `savePlanDoc` → `advanceTask` (`core/src/ChatService.kt:459`)
- `distillPlanFromChat(chatId): List<String>` — полный транскрипт (≤8000 симв, не окно N=12) → 3–7 шагов; fallback — эвристика `parsePlanSteps` по всему разговору (`core/src/ChatService.kt:389`)
- `parsePlanSteps(text): List<String>` — эвристика `1./1)/-/•` и `Шаг N:` (≤10 шагов) (`core/src/ChatService.kt:364`); `importPlanStepsFromText(taskId, text): Boolean` — импорт при ≥2 шагов (`core/src/ChatService.kt:377`)
- `buildPlanMarkdown(taskId, sourceNote): String` — рендер дока `# План: <имя>` + этап/история/шаги/`nextAction` (сырец НЕ кладётся) (`core/src/ChatService.kt:415`); `savePlanDoc(taskId, sourceNote): MemoryDoc` — перезаписываемый док scope=TASK title=`план` (`PLAN_DOC_TITLE`, `core/src/ChatService.kt:352`); `planDoc(taskId): MemoryDoc?` (`core/src/ChatService.kt:449`)
- `autoExecuteCurrentStep(chat): AskResult` (suspend) — только `EXECUTION+ACTIVE`: постит `[авто] Давай код — выполни шаг i/N` (+ кусок плана ≤2000 симв, явная просьба кода в обход запрета BASE) и ждёт LLM (`core/src/ChatService.kt:540`)
- `isExecutionComplete(taskId): Boolean` — все шаги `done` (`core/src/ChatService.kt:560`)
- `pauseTask/resumeTask` — `ACTIVE↔PAUSED` (не для DONE) (`core/src/ChatService.kt:586`); `nextStep/prevStep/completeCurrentStep` — навигация/пометка шагов; `setTaskSteps(taskId, titles)` — замена шагов (≤10, ≤80 симв); `updateNextAction(taskId, action)` (≤200 симв); `setTaskStage(taskId, stage)` — только через `requestTransition` с гардами (прямой прыжок `*→DONE` закрыт)
- `findTaskChat(taskId): Chat?` — первый TASK-чат задачи (для авто) (`core/src/ChatService.kt:356`); `lastAssistantText(chatId): String?` (`core/src/ChatService.kt:359`)

## `Store` (`core/src/data/Store.kt:11`)

- `Store(Store.defaultDir())` → `~/.ai-advent-week2/` (`state.json`, `memory/`, `invariants/`) (`core/src/data/Store.kt:111`)
- `state: AppState` (в памяти, `update { }` + persist под `synchronized(lock)`, `core/src/data/Store.kt:48`)
- `load()` — чтение `state.json` с fallback `AppState()` (`core/src/data/Store.kt:31`)
- `readDocContent / writeDocContent / appendFact / deleteDocContent` — операции с `.md` (`core/src/data/Store.kt:57`). `appendFact` дописывает `- факт` с дедупом по строке; если файл пуст — пишет `# memory\n\n- факт`
- `readInvariantContent / writeInvariantContent / deleteInvariantContent` — операции с `invariants/<id>.md`, прямая запись без дистилляции (`core/src/data/Store.kt:92`)
- Ошибки диска глушатся — память/UI не падают

`AppState` (`core/src/domain/Models.kt:86`): `projects`, `tasks`, `chats`, `messages: Map<chatId, List<ChatMessage>>`, `memoryDocs`, `invariantDocs` (контент в `invariants/*.md`), `profiles`, `activeProfileId`, `taskStates: Map<taskId, TaskState>`.

## `LlmClient` (`core/src/network/LlmClient.kt:64`)

- `LlmClient(model="deepseek-chat", temperature=0.7, baseUrl="https://api.deepseek.com")`
- `complete(history: List<ChatMessage>, apiKey): LlmResult` (`Ok(text, usage)` / `HttpError(code, detail)` / `NetworkError` / `Empty`, `core/src/network/LlmClient.kt:56`)
- `open` — можно подменить фейком в тестах. `close()` — закрыть engine
- Только `core.domain.ChatMessage(role, content)` на входе; истории/памяти не знает; маппит в `WireMessage` и `ChatRequest` (`core/src/network/LlmClient.kt:21`)

`TokenUsage` (`core/src/network/LlmClient.kt:50`): `promptTokens`, `completionTokens`, `totalTokens`.

## `PromptBuilder` (`core/src/domain/Memory.kt:108`)

- `BASE` — базовый system prompt: кратко, по-русски, сначала план/варианты, код только по явной просьбе (`core/src/domain/Memory.kt:109`)
- `PER_SCOPE_CAP_CHARS = 3200`, `SLIDING_N = 12` (`core/src/domain/Memory.kt:115`)
- `profileBlock(p: UserProfile?): String?` — `null` если `p==null` или все поля пустые, иначе `[Профиль: name]\nСтиль: ...\nФормат: ...\nОграничения: ...` (`core/src/domain/Memory.kt:121`)
- `taskStateBlock(state: TaskState?, taskName: String?): String?` — `null` если состояния нет; иначе `[Задача: name]\nЭтап: STAGE (label)\nСтатус: ACTIVE/PAUSED\nШаг: i/N — title\nПройдено: ...\nОжидаемое действие: ...\nИстория переходов: ...` (`core/src/domain/Memory.kt:149`)
- `invariantsBlock(text: String?): String?` — `null` если пусто; иначе `[Инварианты — НЕ НАРУШАТЬ]\n<текст capped 3200>\nПравила выше обязательны...` (`core/src/domain/Memory.kt:137`)
- `buildSystemPrompt(docs, profile = null, taskState = null, taskName = null, invariants = null): String` — склейка `BASE + profileBlock + taskStateBlock + invariantsBlock + [Память: title]` с cap'ами (`core/src/domain/Memory.kt:178`)
- `effectiveHistory(systemPrompt, live, n=12): List<ChatMessage>` — `system + tail(n)` (`core/src/domain/Memory.kt:202`)
- `estimateTokens(text): Int` — `chars/4` (`core/src/domain/Memory.kt:118`)
- `DISTILL_SYSTEM`, `parseDistillJson(raw): List<String>` — дистилляция save-flow, 1–3 факта, ` ```json ` stripping, lenient JSON (`core/src/domain/Memory.kt:7`)
- `PLAN_DISTILL_SYSTEM`, `parsePlanDistillJson(raw): List<String>` — дистилляция всего разговора в 3–7 шагов плана (≤80 симв, ≤10 шт) (`core/src/domain/Memory.kt:27`)
- `VALIDATE_SYSTEM`, `parseValidationJson(raw): Pair<success, errors>` — аудит EXECUTION по инвариантам, строгий контракт `success | failure+errors` (`rule/quote/fix` ≤200 симв, ≤5 ошибок) (`core/src/domain/Memory.kt:16`)

## `ApiKeyProvider` (`core/src/network/ApiKeyProvider.kt:8`)

`override` → `env DEEPSEEK_API_KEY` → `.env` вверх от `user.dir` (6 уровней, как в Week 1) → `~/.ai-advent-week2/.env`. `resolve(): String?` (`core/src/network/ApiKeyProvider.kt:12`).

## Модели (`core/src/domain/Models.kt`, `core/src/domain/TaskState.kt`)

- `Scope` (`core/src/domain/Models.kt:6`): `GENERAL("general")`, `PROJECT("project")`, `TASK("task")`
- `Project(id, name)`, `Task(id, projectId, name)`, `Chat(id, name, scope, parentId)` (`core/src/domain/Models.kt:13`)
- `MemoryDoc(id, title, scope, parentId, active=true)` (`core/src/domain/Models.kt:28`)
- `InvariantDoc(id, title, scope, parentId, active=true)` — День 14, контент в `invariants/<id>.md` (`core/src/domain/Models.kt:39`)
- `ValidationVerdict(SUCCESS/FAILURE)`, `InvariantViolation(rule, evidence, fix)`, `ValidationResult(verdict, violations, at, note)` (`core/src/domain/Models.kt:48`)
- `ChatMessage(role, content, id)` — `role` = `user` | `assistant` (`system` только в `effectiveHistory`); `id=newId()` — стабильный ключ списка реплик (`core/src/domain/Models.kt:66`)
- `UserProfile(id, name, style, format, constraints)` (`core/src/domain/Models.kt:77`)
- `TaskStage(PLANNING/EXECUTION/VALIDATION/DONE)`, `TaskStatus(ACTIVE/PAUSED/DONE)`, `TaskStep(id, title, done, note)`, `StateTransition(from, to, at, reason)`, `TaskState(taskId, stage, status, stepIndex, steps, nextAction, history, updatedAt, lastValidation)` (`core/src/domain/TaskState.kt:5`)
- `TaskStateMachine` (`core/src/domain/TaskState.kt:77`): `canTransition` (только вперёд по цепочке), `canPause/canResume/canAdvance`, `nextStage`, `canRetry` (только `VALIDATION→EXECUTION`), `nextAction`; `defaultNextAction/defaultStepsFor` — дефолтные шаги и ожидаемые действия per-stage
- День 15: `TransitionResult(Allowed/Denied(reason))`, `TransitionContext(status, hasApprovedPlan, allStepsDone, validation)`, `requestTransition(from, to, ctx)` — гарды (`PLANNING→EXECUTION` с планом ≥2 шагов + док `план`, `EXECUTION→VALIDATION` при всех `done`, `VALIDATION→DONE` при `SUCCESS`), `stageRules(stage)` — запреты этапа для промпта
- День 15 в `ChatService`: `transitionContext(taskId)`, `tryTransition(taskId, to)`, `completeTask(taskId)` (только `VALIDATION→DONE` при `SUCCESS`), `checkStageRefusal(chat)` (отказ без LLM: код в PLANNING, финал в EXECUTION, код в VALIDATION, всё в DONE/PAUSED), `lastDeniedReason` для UI; `send` в DONE TASK-чате блокируется
- `newId(): String` — `UUID.take(8)` (`core/src/domain/Models.kt:102`)

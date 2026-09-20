# Core API (`core/`)

## `ChatService` (`core/src/ChatService.kt:26`) — фасад, всё через него

### Создание дерева

- `createProject(name): Project` — только узел проекта, без стартовых доков/чатов (`core/src/ChatService.kt:39`)
- `createTask(projectId, name): Task` — только узел задачи (`core/src/ChatService.kt:45`)
- `createChat(name, scope, parentId): Chat` — `parentId`: `projectId` для `PROJECT`, `taskId` для `TASK`, `null` для `GENERAL` (`core/src/ChatService.kt:51`)
- `createMemoryDoc(title, scope, parentId): MemoryDoc` — дедуп по `title` в том же `scope+parent` (`core/src/ChatService.kt:57`)

### Память

- `relevantDocs(chat): List<MemoryDoc>` — наследование `GENERAL → PROJECT → TASK`, только `active=true` (`core/src/ChatService.kt:84`)
- `buildSystemPrompt(chat): String` — `relevantDocs` + `activeProfile()` → `PromptBuilder.buildSystemPrompt` (`core/src/ChatService.kt:109`)
- `docContent(doc): String`, `toggleDocActive(docId)`, `deleteDoc(docId)` (`core/src/ChatService.kt:69`)
- `commitFact(docId, fact)` — `Store.appendFact` (дедуп, `core/src/ChatService.kt:242`)
- `lastSystemPrompt`, `lastPromptTokens` — для UI/отладки (`core/src/ChatService.kt:30`)

### Профили — День 12 (`core/src/ChatService.kt:114`)

- `activeProfile(): UserProfile?` — по `state.activeProfileId`, `null` = Аноним
- `createProfile(name, style, format, constraints): UserProfile` — сразу делает активным (`core/src/ChatService.kt:121`)
- `updateProfile(id, name, style, format, constraints)` (`core/src/ChatService.kt:133`)
- `deleteProfile(id)` — чистит `activeProfileId`, если удалён активный (`core/src/ChatService.kt:146`)
- `setActiveProfile(id: String?)` — `null` = Аноним; чужой `id` игнорируется (`core/src/ChatService.kt:155`)

Поля `UserProfile` (`core/src/domain/Models.kt:45`): `name` ≤60, `style/format/constraints` ≤500 каждое.

### Диалог

- `appendUserMessage(chat, text)` (sync) — дописать user-сообщение в стор (`core/src/ChatService.kt:169`)
- `completeAsk(chat): AskResult` (suspend) — `ApiKeyProvider.resolve()` → `buildSystemPrompt` → `effectiveHistory(N=12)` → `LlmClient.complete` → дописать assistant; при HTTP/сетевой ошибке или отсутствии ключа user-сообщение откатывается (`core/src/ChatService.kt:183`). `Success(text, promptTokens)` / `Failure(message)` (`core/src/ChatService.kt:20`)
- `ask(chat, prompt): AskResult` — тонкая обёртка: `appendUserMessage` + `completeAsk` (эквивалент старого поведения одним вызовом) (`core/src/ChatService.kt:219`). Разбиение нужно UI для optimistic echo: `AppViewModel.send` кладёт сообщение синхронно и делает `refresh` до ответа LLM
- `clearChat(chatId)` — стереть историю чата (память и профили не трогает) (`core/src/ChatService.kt:161`)
- `distill(raw): List<String>` — сырец → 1–3 факта через `DISTILL_SYSTEM` (`core/src/ChatService.kt:228`, `core/src/domain/Memory.kt:7`)

Состояние: `state(): AppState`, `store: Store` открыт для `ViewModel`.

## `Store` (`core/src/data/Store.kt:10`)

- `Store(Store.defaultDir())` → `~/.ai-advent-week2/` (`state.json`, `memory/`) (`core/src/data/Store.kt:86`)
- `state: AppState` (в памяти, `update { }` + persist под `synchronized(lock)`, `core/src/data/Store.kt:44`)
- `load()` — чтение `state.json` с fallback `AppState()` (`core/src/data/Store.kt:27`)
- `readDocContent / writeDocContent / appendFact / deleteDocContent` — операции с `.md` (`core/src/data/Store.kt:53`). `appendFact` дописывает `- факт` с дедупом по строке; если файл пуст — пишет `# memory\n\n- факт`
- Ошибки диска глушатся — память/UI не падают

`AppState` (`core/src/domain/Models.kt:54`): `projects`, `tasks`, `chats`, `messages: Map<chatId, List<ChatMessage>>`, `memoryDocs`, `profiles`, `activeProfileId`.

## `LlmClient` (`core/src/network/LlmClient.kt:64`)

- `LlmClient(model="deepseek-chat", temperature=0.7, baseUrl="https://api.deepseek.com")`
- `complete(history: List<ChatMessage>, apiKey): LlmResult` (`Ok(text, usage)` / `HttpError(code, detail)` / `NetworkError` / `Empty`, `core/src/network/LlmClient.kt:56`)
- `open` — можно подменить фейком в тестах. `close()` — закрыть engine
- Только `core.domain.ChatMessage(role, content)` на входе; истории/памяти не знает; маппит в `WireMessage` и `ChatRequest` (`core/src/network/LlmClient.kt:21`)

`TokenUsage` (`core/src/network/LlmClient.kt:50`): `promptTokens`, `completionTokens`, `totalTokens`.

## `PromptBuilder` (`core/src/domain/Memory.kt:34`)

- `BASE` — базовый system prompt: кратко, по-русски, сначала план/варианты, код только по явной просьбе (`core/src/domain/Memory.kt:35`)
- `PER_SCOPE_CAP_CHARS = 3200`, `SLIDING_N = 12` (`core/src/domain/Memory.kt:41`)
- `profileBlock(p: UserProfile?): String?` — `null` если `p==null` или все поля пустые, иначе `[Профиль: name]\nСтиль: ...\nФормат: ...\nОграничения: ...` (`core/src/domain/Memory.kt:47`)
- `buildSystemPrompt(docs: List<Pair<title, content>>, profile: UserProfile? = null): String` — склейка `BASE + profileBlock + [Память: title]` с cap'ами (`core/src/domain/Memory.kt:62`)
- `effectiveHistory(systemPrompt, live, n=12): List<ChatMessage>` — `system + tail(n)` (`core/src/domain/Memory.kt:79`)
- `estimateTokens(text): Int` — `chars/4` (`core/src/domain/Memory.kt:44`)
- `DISTILL_SYSTEM`, `parseDistillJson(raw): List<String>` — дистилляция save-flow, 1–3 факта, ` ```json ` stripping, lenient JSON (`core/src/domain/Memory.kt:7`)

## `ApiKeyProvider` (`core/src/network/ApiKeyProvider.kt:8`)

`override` → `env DEEPSEEK_API_KEY` → `.env` вверх от `user.dir` (6 уровней, как в Week 1) → `~/.ai-advent-week2/.env`. `resolve(): String?` (`core/src/network/ApiKeyProvider.kt:12`).

## Модели (`core/src/domain/Models.kt`)

- `Scope` (`core/src/domain/Models.kt:6`): `GENERAL("general")`, `PROJECT("project")`, `TASK("task")`
- `Project(id, name)`, `Task(id, projectId, name)`, `Chat(id, name, scope, parentId)` (`core/src/domain/Models.kt:12`)
- `MemoryDoc(id, title, scope, parentId, active=true)` (`core/src/domain/Models.kt:28`)
- `ChatMessage(role, content)` — `role` = `user` | `assistant` | `system` (system только в `effectiveHistory`) (`core/src/domain/Models.kt:36`)
- `UserProfile(id, name, style, format, constraints)` (`core/src/domain/Models.kt:45`)
- `newId(): String` — `UUID.take(8)` (`core/src/domain/Models.kt:66`)

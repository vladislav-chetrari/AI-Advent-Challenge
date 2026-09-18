# Core API (`core/`)

## `ChatService` (`core/src/ChatService.kt`) — фасад, всё через него

Создание дерева:
- `createProject(name): Project` (+ стартовые `tech-stack.md`, `project-goal.md`)
- `createTask(projectId, name): Task` (+ стартовый `task-state.md`)
- `createChat(name, scope, parentId): Chat` — `parentId`: `projectId` для
  `PROJECT`, `taskId` для `TASK`, `null` для `GENERAL`
- `createMemoryDoc(title, scope, parentId): MemoryDoc` (дедуп по title в scope)

Память:
- `relevantDocs(chat): List<MemoryDoc>` — наследование general→project→task
- `buildSystemPrompt(chat): String` — через `PromptBuilder`
- `docContent(doc): String`, `toggleDocActive(docId)`, `deleteDoc(docId)`
- `commitFact(docId, fact)` — append `- факт` в `.md`
- `lastSystemPrompt`, `lastPromptTokens` — для UI/отладки

Диалог:
- `ask(chat, prompt): AskResult` (`Success(text, promptTokens)` / `Failure(message)`).
  Пишет user → шлёт `system + tail(N=12)` → пишет assistant; при HTTP/сетевой
  ошибке user-сообщение откатывается. Без ключа — `Failure` с подсказкой.
- `clearChat(chatId)` — стереть историю чата (память не трогает).
- `distill(raw): List<String>` — сырец → 1–3 факта (см. `docs/MEMORY.md`).

Состояние: `state(): AppState`, `store: Store` открыт для ViewModel.

## `Store` (`core/src/data/Store.kt`)

- `Store(Store.defaultDir())` → `~/.ai-advent-week2/` (`state.json`, `memory/`).
- `state: AppState` (в памяти, `update { }` + persist под lock).
- `readDocContent / writeDocContent / appendFact / deleteDocContent`.
- Ошибки диска глушатся — память/UI не падают.

## `LlmClient` (`core/src/network/LlmClient.kt`)

- `LlmClient(model="deepseek-chat", temperature=0.7, baseUrl="https://api.deepseek.com")`.
- `complete(history: List<ChatMessage>, apiKey): LlmResult`
  (`Ok(text, usage)` / `HttpError(code, detail)` / `NetworkError` / `Empty`).
- `open` — можно подменить фейком в тестах. `close()` — закрыть engine.
- Только `core.domain.ChatMessage(role, content)` на входе; истории не хранит.

## `PromptBuilder` (`core/src/domain/Memory.kt`)

- `BASE` — базовый system prompt.
- `buildSystemPrompt(docs: List<Pair<title, content>>)` — склейка с cap'ами.
- `effectiveHistory(systemPrompt, live, n=12)` — `system + tail(n)`.
- `estimateTokens(text)` — `chars/4`.
- `DISTILL_SYSTEM`, `parseDistillJson(raw)` — дистилляция save-flow.

## `ApiKeyProvider` (`core/src/network/ApiKeyProvider.kt`)

`override` → `env DEEPSEEK_API_KEY` → `.env` (`Week 2/.env`, `.env`,
`~/.ai-advent-week2/.env`). `resolve(): String?`.

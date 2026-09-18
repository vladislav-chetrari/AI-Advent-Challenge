# Модель памяти

Маппинг на требование задачи (День 11: краткосрочная / рабочая / долговременная):

| Слой задачи      | Сущность                              | Хранение              |
|------------------|---------------------------------------|-----------------------|
| Краткосрочная    | `messages: Map<chatId, List<ChatMessage>>` (живая история чата) | `state.json` |
| Рабочая          | `MemoryDoc(scope=PROJECT, parentId=projectId)` и `MemoryDoc(scope=TASK, parentId=taskId)` | `memory/<docId>.md` |
| Долговременная   | `MemoryDoc(scope=GENERAL)` — профиль, решения, общие знания | `memory/<docId>.md` |

Код: `core/src/domain/Models.kt`, `core/src/data/Store.kt`.

## Правила

1. **Память принадлежит scope, чат только читает/пишет.** Два чата одной задачи
   (`task_x_chat_1/2`) делят один `task-state.md` — дублей нет.
2. **Task всегда внутри Project** (`Task.projectId` обязателен).
3. **Наследование при чтении** (`ChatService.relevantDocs`, `core/src/ChatService.kt`):
   - `GENERAL`-чат → только general-доки;
   - `PROJECT(P)`-чат → general + доки проекта `P`;
   - `TASK(T in P)`-чат → general + доки проекта `P` + доки задачи `T`.
   Порядок инжекта: general → project → task. Только `active=true`.
4. **Окно малое by design**: Sliding Window `N=12` (`PromptBuilder.SLIDING_N`),
   cap `~800` токенов (`3200` символов) на scope (`PromptBuilder.PER_SCOPE_CAP_CHARS`,
   `core/src/domain/Memory.kt`). Суммаризации нет — старое молча отбрасывается,
   важное живёт в фактах.

## Сборка system prompt

```
BASE ("Ты coding-агент...")
+ [Память: <title>]\n<content capped>   × N релевантных доков
+ последние N сообщений (effectiveHistory: system + tail)
```

Посмотреть собранный промпт: шапка чата → `▼ prompt` (оценка `chars/4` +
`prompt_tokens` прошлого ответа API). Выключение дока чекбоксом в дереве
сразу меняет следующий system prompt — на этом строится демо «как влияет».

## Save-flow (явное сохранение — требование «вы явно выбираете, что и куда»)

1. `⋯ → Сохранить в память` на реплике (`desktop/src/main.kt: MessageBubble`).
2. `AppViewModel.startSave` → `ChatService.distill(raw)`:
   system `DISTILL_SYSTEM` → JSON-массив `["факт1", ...]` (1–3 шт, ≤200 симв),
   парсинг `parseDistillJson`. Без ключа/API — fallback: сырой текст ≤500 симв.
3. Диалог: факт **редактируемый**, цель — scope (дефолт = scope текущего чата) +
   parent (проект/задача) + существующий `.md` **или** новый (title).
4. `commitSave` → `Store.appendFact` (дописывает `- факт`, дедуп по строке).

## Стартовые доки

- `createProject` → `tech-stack.md`, `project-goal.md` (scope PROJECT).
- `createTask` → `task-state.md` (scope TASK).
- General-доки создаются только через save-диалог («новый документ», scope GENERAL).

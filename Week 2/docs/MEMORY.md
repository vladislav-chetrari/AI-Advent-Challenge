# Модель памяти

Маппинг на требования задач:

| Требование | Слой | Сущность | Хранение | Код |
|---|---|---|---|---|
| День 11: краткосрочная (текущий диалог) | Краткосрочная | `messages: Map<chatId, List<ChatMessage>>` — живая история чата, режется окном `N=12` | `state.json` | `core/src/domain/Models.kt:54`, `core/src/data/Store.kt` |
| День 11: рабочая (данные текущей задачи) | Рабочая | `MemoryDoc(scope=PROJECT, parentId=projectId)` и `MemoryDoc(scope=TASK, parentId=taskId)` | `memory/<docId>.md` | `core/src/domain/Models.kt:28` |
| День 11: долговременная (профиль, решения, знания) | Долговременная | `MemoryDoc(scope=GENERAL)` — общие знания, решения | `memory/<docId>.md` | `core/src/domain/Models.kt:28` |
| День 12: персонализация | Персонализация | `UserProfile(name, style, format, constraints)` + `activeProfileId` (null = Аноним) | `state.json` | `core/src/domain/Models.kt:45` |

## Правила

1. **Память принадлежит scope, чат только читает/пишет.** Два чата одной задачи
   (`task_x_chat_1/2`) делят один `task`-док — дублей нет (`core/src/ChatService.kt:84`).
2. **Task всегда внутри Project** (`Task.projectId` обязателен, `core/src/domain/Models.kt:15`).
3. **Наследование при чтении** (`ChatService.relevantDocs`, `core/src/ChatService.kt:84`):
   - `GENERAL`-чат → только `GENERAL`-доки;
   - `PROJECT(P)`-чат → `GENERAL` + доки проекта `P`;
   - `TASK(T in P)`-чат → `GENERAL` + доки проекта `P` + доки задачи `T`.
   Порядок инжекта: `GENERAL → PROJECT → TASK`. Только `active=true`, сортировка `scope.ordinal → title`.
4. **Профили — глобальные**, не привязаны к scope. Один активный на всё приложение (`AppState.activeProfileId`). `null` = Аноним — в промпт ничего не добавляется. Хранятся в `state.json`, а не в `.md`, т.к. это не факты задачи, а предпочтения пользователя (`core/src/ChatService.kt:116`).
5. **Окно малое by design**: Sliding Window `N=12` (`PromptBuilder.SLIDING_N`, `core/src/domain/Memory.kt:42`),
   cap `~800` токенов (`3200` символов) на док (`PromptBuilder.PER_SCOPE_CAP_CHARS`).
   Суммаризации нет — старое молча отбрасывается, важное живёт в фактах / профиле.

## Сборка system prompt

```
BASE ("Ты coding-агент...")                          // core/src/domain/Memory.kt:35
+ [Профиль: <name>]\nСтиль: ...\nФормат: ...\nОграничения: ...   // если activeProfile != null и есть непустые поля, PromptBuilder.profileBlock
+ [Память: <title>]\n<content capped 3200>            // × N релевантных доков (general → project → task)
+ последние N сообщений (effectiveHistory: system + tail)        // core/src/domain/Memory.kt:79
```

Код сборки: `ChatService.buildSystemPrompt` (`core/src/ChatService.kt:109`) → `PromptBuilder.buildSystemPrompt` (`core/src/domain/Memory.kt:62`).

Посмотреть собранный промпт: шапка чата → `▼ system prompt` / `▲ system prompt` (оценка `chars/4` +
`prompt_tokens` прошлого ответа API, `desktop/src/main.kt:291`). Выключение дока чекбоксом в дереве
или смена профиля в кружке внизу слева сразу меняет следующий system prompt — на этом строятся демо
«как влияет память» (Task 1) и «как влияет профиль» (Task 2).

## Save-flow — явное сохранение (требование «вы явно выбираете, что и куда»)

1. ПКМ по реплике → `Сохранить в память` (`desktop/src/main.kt:363`, `MessageBubble` — `PointerEventPass.Initial` гасит дефолтное меню `SelectionContainer`).
2. `AppViewModel.startSave` (`desktop/src/ui/AppViewModel.kt:255`) → `ChatService.distill(raw)` (`core/src/ChatService.kt:228`):
   system `DISTILL_SYSTEM` (`core/src/domain/Memory.kt:7`) → JSON-массив `["факт1", ...]` (1–3 шт, ≤200 симв каждый),
   парсинг `parseDistillJson`. Без ключа/API — fallback: сырой текст ≤500 симв.
3. Диалог: факт **редактируемый**, цель — scope (дефолт = scope текущего чата) +
   parent (проект/задача) + существующий `.md` **или** новый (title) (`desktop/src/main.kt:536`).
4. `commitSave` → `Store.appendFact` (дописывает `- факт`, дедуп по строке, `core/src/data/Store.kt:67`).

## Персонализация (День 12)

- Создание/правка: круглая кнопка внизу дерева → диалог профиля (`desktop/src/main.kt:168`, `desktop/src/main.kt:630`).
  Поля: `name` (до 60 симв), `style`, `format`, `constraints` (до 500 симв каждое, `core/src/domain/Models.kt:45`).
- Выбор: чипы `Аноним` / `<профиль>` — клик сразу переключает `activeProfileId` (`AppViewModel.setProfileSelected`, `desktop/src/ui/AppViewModel.kt:343`) и вызывает `refresh()` → следующий `buildSystemPrompt` уже с новым блоком.
- Инжект: `PromptBuilder.profileBlock` (`core/src/domain/Memory.kt:47`) возвращает `null`, если профиль `null` или все три поля пустые — промпт не меняется. Иначе блок `[Профиль: ...]` вставляется сразу после `BASE`, до памяти.
- Проверка: один и тот же вопрос при `Аноним` vs `Кратко` vs `Подробно с примерами кода` даёт видимую разницу (демо Task 2 — переключение профиля без изменения чата/памяти).

## Стартовые доки

`createProject` / `createTask` создают только узлы дерева (папки) без `.md` (`desktop/src/ui/AppViewModel.kt:207`). Стартовые `tech-stack.md` / `project-goal.md` / `task-state.md` из ранней версии Task 1 убраны — все документы теперь создаются только через save-диалог (явный выбор). General-доки также только через save-диалог (scope `GENERAL`).

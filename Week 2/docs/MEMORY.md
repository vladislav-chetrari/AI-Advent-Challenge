# Модель памяти

Маппинг на требования задач:

| Требование | Слой | Сущность | Хранение | Код |
|---|---|---|---|---|
| День 11: краткосрочная (текущий диалог) | Краткосрочная | `messages: Map<chatId, List<ChatMessage>>` — живая история чата, режется окном `N=12` | `state.json` | `core/src/domain/Models.kt:86`, `core/src/data/Store.kt` |
| День 11: рабочая (данные текущей задачи) | Рабочая | `MemoryDoc(scope=PROJECT, parentId=projectId)` и `MemoryDoc(scope=TASK, parentId=taskId)` | `memory/<docId>.md` | `core/src/domain/Models.kt:28` |
| День 11: долговременная (профиль, решения, знания) | Долговременная | `MemoryDoc(scope=GENERAL)` — общие знания, решения; док `план` (scope=TASK, title=`план`) — зафиксированный план задачи | `memory/<docId>.md` | `core/src/domain/Models.kt:28`, `core/src/ChatService.kt:352` |
| День 12: персонализация | Персонализация | `UserProfile(name, style, format, constraints)` + `activeProfileId` (null = Аноним) | `state.json` | `core/src/domain/Models.kt:77` |
| День 13: состояние задачи | Состояние (FSM) | `TaskState(stage, status, stepIndex, steps, nextAction, history, lastValidation)` per-task | `state.json` (`taskStates`) | `core/src/domain/TaskState.kt:35` |
| День 14: инварианты | Ограничения | `InvariantDoc(scope, parentId, active)` — правила, которые нельзя нарушать | `invariants/<docId>.md` (+ метаданные в `state.json`) | `core/src/domain/Models.kt:39` |

## Правила

1. **Память принадлежит scope, чат только читает/пишет.** Два чата одной задачи
   (`task_x_chat_1/2`) делят один `task`-док — дублей нет (`core/src/ChatService.kt:136`).
2. **Task всегда внутри Project** (`Task.projectId` обязателен, `core/src/domain/Models.kt:16`).
3. **Наследование при чтении** (`ChatService.relevantDocs`, `core/src/ChatService.kt:136`):
   - `GENERAL`-чат → только `GENERAL`-доки;
   - `PROJECT(P)`-чат → `GENERAL` + доки проекта `P`;
   - `TASK(T in P)`-чат → `GENERAL` + доки проекта `P` + доки задачи `T`.
   Порядок инжекта: `GENERAL → PROJECT → TASK`. Только `active=true`, сортировка `scope.ordinal → title`.
4. **Профили — глобальные**, не привязаны к scope. Один активный на всё приложение (`AppState.activeProfileId`). `null` = Аноним — в промпт ничего не добавляется. Хранятся в `state.json`, а не в `.md`, т.к. это не факты задачи, а предпочтения пользователя (`core/src/ChatService.kt:203`).
5. **Окно малое by design**: Sliding Window `N=12` (`PromptBuilder.SLIDING_N`, `core/src/domain/Memory.kt:116`),
   cap `~800` токенов (`3200` символов) на док (`PromptBuilder.PER_SCOPE_CAP_CHARS`).
   Суммаризации нет — старое молча отбрасывается, важное живёт в фактах / профиле.
6. **Инварианты принадлежат scope, но GENERAL-чатам не инжектятся** (`relevantInvariants`, `core/src/ChatService.kt:163`): `PROJECT`-чат → инварианты проекта, `TASK(T in P)`-чат → инварианты проекта `P` + задачи `T`. Только `active=true`. Хранятся отдельно от диалога (`invariants/*.md`), правятся текстом напрямую без дистилляции.
7. **Состояние задачи — отдельно от диалога** (`taskStates` в `state.json`): этап/статус/шаги/`nextAction`/история переходов. Переходы только через `requestTransition` с гардами (День 15): `PLANNING→EXECUTION` с утверждённым планом, `EXECUTION→VALIDATION` при всех `done`, `VALIDATION→DONE` при `SUCCESS`-валидации + `canRetry VALIDATION→EXECUTION` + возврат `REPLAN →PLANNING` (`TaskStateMachine`, `core/src/domain/TaskState.kt:77`). Пауза возможна на любом этапе кроме DONE. Блок `[Задача]` содержит «Запреты этапа» — LLM отказывает на просьбу перепрыгнуть этап.

## Сборка system prompt

```
BASE ("Ты coding-агент...")                          // core/src/domain/Memory.kt:109
+ [Профиль: <name>]\nСтиль: ...\nФормат: ...\nОграничения: ...   // если activeProfile != null и есть непустые поля, PromptBuilder.profileBlock
+ [Задача: <name>]\nЭтап: ...\nСтатус: ...\nШаг: i/N\nОжидаемое действие: ...   // только TASK-чаты, PromptBuilder.taskStateBlock
+ [Инварианты — НЕ НАРУШАТЬ]\n<текст capped 3200>    // если есть релевантные инварианты, PromptBuilder.invariantsBlock
+ [Память: <title>]\n<content capped 3200>            // × N релевантных доков (general → project → task)
+ последние N сообщений (effectiveHistory: system + tail)        // core/src/domain/Memory.kt:202
```

Код сборки: `ChatService.buildSystemPrompt` (`core/src/ChatService.kt:187`) → `PromptBuilder.buildSystemPrompt` (`core/src/domain/Memory.kt:178`).

Посмотреть собранный промпт: шапка чата → `▼ system prompt` / `▲ system prompt` (оценка `chars/4` +
`prompt_tokens` прошлого ответа API). Выключение дока чекбоксом в дереве
или смена профиля в кружке внизу слева сразу меняет следующий system prompt — на этом строятся демо
«как влияет память» (Task 1) и «как влияет профиль» (Task 2). Блок `[Задача]` меняется кнопками `TaskStateBar` (Task 3), блок `[Инварианты]` — тумблером `🛡` и текстом `InvariantPane` (Task 4).

## Save-flow — явное сохранение (требование «вы явно выбираете, что и куда»)

1. ПКМ по реплике → `Сохранить в память`.
2. `AppViewModel.startSave` (`desktop/src/ui/AppViewModel.kt:344`) → `ChatService.distill(raw)` (`core/src/ChatService.kt:315`):
   system `DISTILL_SYSTEM` (`core/src/domain/Memory.kt:7`) → JSON-массив `["факт1", ...]` (1–3 шт, ≤200 симв каждый),
   парсинг `parseDistillJson`. Без ключа/API — fallback: сырой текст ≤500 симв.
3. Диалог: факт **редактируемый**, цель — scope (дефолт = scope текущего чата) +
   parent (проект/задача) + существующий `.md` **или** новый (title).
4. `commitSave` → `Store.appendFact` (дописывает `- факт`, дедуп по строке, `core/src/data/Store.kt:71`).

## Персонализация (День 12)

- Создание/правка: круглая кнопка внизу дерева → диалог профиля.
  Поля: `name` (до 60 симв), `style`, `format`, `constraints` (до 500 симв каждое, `core/src/domain/Models.kt:77`).
- Выбор: чипы `Аноним` / `<профиль>` — клик сразу переключает `activeProfileId` (`AppViewModel.setProfileSelected`, `desktop/src/ui/AppViewModel.kt:432`) и вызывает `refresh()` → следующий `buildSystemPrompt` уже с новым блоком.
- Инжект: `PromptBuilder.profileBlock` (`core/src/domain/Memory.kt:121`) возвращает `null`, если профиль `null` или все три поля пустые — промпт не меняется. Иначе блок `[Профиль: ...]` вставляется сразу после `BASE`, до памяти.
- Проверка: один и тот же вопрос при `Аноним` vs `Кратко` vs `Подробно с примерами кода` даёт видимую разницу (демо Task 2 — переключение профиля без изменения чата/памяти).

## Состояние задачи (День 13)

- Создание: `createTask` сразу кладёт `TaskState(PLANNING, ACTIVE)` в `taskStates` (`core/src/ChatService.kt:61`). Старые задачи без состояния подтягиваются через `ensureTaskState` (`core/src/ChatService.kt:337`).
- Этапы: `PLANNING → EXECUTION → VALIDATION → DONE` (`TaskStage`, `core/src/domain/TaskState.kt:5`). Прямые прыжки запрещены, `DONE` терминален (кроме `REPLAN`/`Retry`, см. ниже). Статус `PAUSED` — пауза на любом этапе кроме DONE (`TaskStatus`, `core/src/domain/TaskState.kt:12`).
- План: переход `planning → execution` (`finalizePlanAndAdvance`, `core/src/ChatService.kt:459`) дистиллирует ВЕСЬ разговор (`distillPlanFromChat`, `PLAN_DISTILL_SYSTEM`, `core/src/domain/Memory.kt:27` → 3–7 шагов) и перезаписывает отдельный док `план` (scope=TASK, `buildPlanMarkdown/savePlanDoc`, `core/src/ChatService.kt:415`). Сырец диалога в док НЕ кладётся — документ = чистый план. Fallback без API — эвристика `parsePlanSteps` (`1./-•/Шаг N:`, `core/src/ChatService.kt:364`).
- Выполнение: авто-цикл EXECUTION (`AppViewModel.startAutoExecutionLocked`, `desktop/src/ui/AppViewModel.kt:556`) — `[авто] Давай код — выполни шаг i/N` (`autoExecuteCurrentStep`, `core/src/ChatService.kt:540`) → ответ LLM → `nextStep` → следующий шаг. После последнего шага — автопереход в VALIDATION. Пауза (`pauseTask`) останавливает цикл на невыполненном шаге (in-flight шаг не помечается `done`), resume продолжает с него же.
- Продолжение без повторов: каждый `ask` в TASK-чате несёт блок `[Задача]` (этап/шаг/`nextAction`/история переходов) — LLM видит где остановились (`taskStateBlock`, `core/src/domain/Memory.kt:149`).
- REPLAN (`replanTask`, `core/src/ChatService.kt:786` + `clearTaskChats`, `core/src/ChatService.kt:739`): стоп авто → чистка ВСЕХ реплик чатов задачи → возврат в PLANNING с дефолтными шагами.

## Инварианты (День 14)

- Сущность: `InvariantDoc(title, scope, parentId, active)` (`core/src/domain/Models.kt:39`) — только PROJECT/TASK scope. Метаданные в `state.json`, текст в `invariants/<id>.md` (`Store`, `core/src/data/Store.kt:92`). Создание через `+ → Инварианты` (`CreateKind.INVARIANTS`, `desktop/src/ui/AppViewModel.kt:40`).
- Редактирование: `InvariantPane` — прямое текстовое поле с автосейвом (debounce 600мс, cap 8000 симв, `onInvariantEdit`, `desktop/src/ui/AppViewModel.kt:330`). Дистилляции нет — инвариант это правило, а не факт из диалога.
- Инжект: `relevantInvariants` (наследование как у памяти, `GENERAL` — пусто, `core/src/ChatService.kt:163`) → `invariantsTextForChat` (cap 3200) → `invariantsBlock` с директивой отказа при конфликте (`core/src/domain/Memory.kt:137`). Тумблер `🛡` в дереве/`InvariantsPanel` включает/выключает инжект без удаления текста.
- Проверка: переход `EXECUTION→VALIDATION` (`advanceToValidationWithCheck`, `core/src/ChatService.kt:726`) запускает `validateAgainstInvariants` (`core/src/ChatService.kt:656`): инварианты + транскрипт EXECUTION (≤8000 симв) → `VALIDATE_SYSTEM` (`core/src/domain/Memory.kt:16`) → `parseValidationJson` (`success | failure+errors`, `core/src/domain/Memory.kt:55`) → `TaskState.lastValidation`. Без инвариантов/сообщений/ключа — `SUCCESS` с `note` (решает пользователь).
- Конфликт: `FAILURE` показывает карточки `rule/evidence/fix` в `TaskStateBar` + кнопки `↻ Retry EXECUTION` / `Проверить снова`. Retry (`retryExecution`, `core/src/ChatService.kt:807`): `VALIDATION→EXECUTION` (`canRetry`), чистка только `[авто]`-реплик (`clearExecutionReplies` — планировочный диалог живёт), сброс шагов в `done=false`, рестарт авто-цикла (`AppViewModel.retryExecution`, `desktop/src/ui/AppViewModel.kt:731`).
- Ручной ре-чек в VALIDATION: кнопка `↻ Проверить` (`runValidation`, `desktop/src/ui/AppViewModel.kt:715`).

## Стартовые доки

`createProject` / `createTask` создают только узлы дерева (папки) без `.md` (`desktop/src/ui/AppViewModel.kt:231`). Стартовые `tech-stack.md` / `project-goal.md` / `task-state.md` из ранней версии Task 1 убраны — все документы теперь создаются только через save-диалог (явный выбор). General-доки также только через save-диалог (scope `GENERAL`). Исключения: док `план` создаётся автоматически при финализации planning (см. День 13 выше), файлы инвариантов — при `+ → Инварианты` (пустые, заполняются вручную).

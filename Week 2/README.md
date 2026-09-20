# Week 2 — Memory Agent + Personalization + Task State + Invariants (Desktop, Compose Multiplatform + DeepSeek)

Кодинг-агент с явной трёхуровневой моделью памяти + персонализацией + конечным автоматом задачи + инвариантами. Задачи: `Task 1.md` (День 11 — модель памяти), `Task 2.md` (День 12 — персонализация), `Task 3.md` (День 13 — Task State Machine), `Task 4.md` (День 14 — инварианты).

Видео-демо: `Task 1 demo.mp4`, `Task 2 demo.mp4`, `Task 3 demo.mp4`.

## Запуск

```sh
cd "Week 2"
./kotlin run :desktop      # GUI
./kotlin build             # только сборка
```

Нужен `DEEPSEEK_API_KEY`: env-переменная или `.env` (`DEEPSEEK_API_KEY=...`)
рядом с проектом / в корне репо (поиск вверх 6 уровней как в Week 1) / в `~/.ai-advent-week2/.env`.
Модель фиксирована: `deepseek-chat` (`core/src/network/LlmClient.kt:64`).

Данные лежат в `~/.ai-advent-week2/` (`core/src/data/Store.kt:11`): `state.json` + `memory/*.md` + `invariants/*.md`.

## Структура

```
Week 2/
  project.yaml            # модули: core, desktop
  kotlin, kotlin.bat      # враппер Kotlin Toolchain 0.12.0 (как в Week 1)
  core/src/
    domain/Models.kt      # Project, Task, Chat, Scope, MemoryDoc, InvariantDoc, ValidationResult, ChatMessage, UserProfile, AppState
    domain/TaskState.kt   # TaskStage (PLANNING→EXECUTION→VALIDATION→DONE), TaskStatus, TaskStep, StateTransition, TaskState, TaskStateMachine
    domain/Memory.kt      # DISTILL_SYSTEM, VALIDATE_SYSTEM, PLAN_DISTILL_SYSTEM, parseDistillJson/parseValidationJson/parsePlanDistillJson, PromptBuilder (BASE, profileBlock, invariantsBlock, taskStateBlock, buildSystemPrompt, effectiveHistory)
    data/Store.kt         # state.json + memory/*.md + invariants/*.md, defaultDir ~/.ai-advent-week2
    network/LlmClient.kt  # DeepSeek chat/completions, LlmResult (Ok/HttpError/NetworkError/Empty)
    network/ApiKeyProvider.kt # override → env → .env вверх → ~/.ai-advent-week2/.env
    ChatService.kt        # фасад: дерево + память + профили + FSM + план + инварианты + ask + distill/commit
  desktop/src/
    main.kt               # Window, дерево (чаты/M-/🛡-доки), TaskStateBar, ChatPane/MemoryPane/InvariantPane, InvariantsPanel, диалоги (Create/Save/Profile)
    ui/AppViewModel.kt    # Selection (ChatSel/DocSel/InvariantSel), CreateKind (+INVARIANTS), SaveDialog, ProfileDialog, UiState, refresh/send/toggle + FSM/авто-цикл/валидация
    ui/ChatMarkdown.kt    # chatMarkdownTypography() — компактная типографика для markdown
  docs/
    MEMORY.md             # модель памяти (3 слоя), скоупы, наследование, персонализация, Task State, инварианты, сборка промпта, save-flow
    CORE.md               # API core-модуля (ChatService, Store, LlmClient, PromptBuilder, ApiKeyProvider, FSM)
    UI.md                 # дерево, TaskStateBar, чат, viewer'ы, диалоги, профиль, инварианты, сценарии демо
```

## Ключевые решения

- **Память shared по scope**, а не per-chat (`docs/MEMORY.md:15`): два чата одной задачи делят один `task`-док — дублей нет.
- **Без суммаризации**: Sliding Window `N=12` + инжект фактов в system prompt (`core/src/domain/Memory.kt:42`). Cap `~800` токенов (`3200` символов) на док. Старое молча отбрасывается, важное живёт в фактах.
- **Сохранение явное**: `ПКМ → Сохранить в память` → LLM-дистилляция (`DISTILL_SYSTEM`) → редактируемый факт → выбор цели (scope + parent + `.md`). Сырец не хранится (`docs/MEMORY.md:40`).
- **Хранилища разделены**: сообщения чата — `state.json` (краткосрочная), память — `.md` файлы (рабочая/долговременная).
- **Персонализация (День 12)** — `UserProfile(name, style, format, constraints)` в `state.json` (`core/src/domain/Models.kt:77`). `activeProfileId == null` → Аноним, в промпт ничего не добавляется (`core/src/domain/Memory.kt:121`, `core/src/ChatService.kt:203`). Активный профиль инжектится блоком `[Профиль: ...]` перед блоками памяти при каждом `buildSystemPrompt` (`core/src/ChatService.kt:187`). Переключение чипами в диалоге профиля мгновенно меняет следующий system prompt — на этом строится демо Task 2.
- **Стартовые доки не создаются автоматически**: `createProject`/`createTask` создают только папки (`desktop/src/ui/AppViewModel.kt:239`); все `.md` — только через save-диалог (явный выбор). Исключение: `createTask` сразу инициализирует `TaskState(PLANNING)` (`core/src/ChatService.kt:61`), а документ `план` (scope=TASK, title=`план`) появляется только при финализации planning (`finalizePlanAndAdvance`, `core/src/ChatService.kt:459`).
- **Состояние задачи — FSM (День 13)**: `TaskState(stage, status, stepIndex, steps, nextAction, history)` per-task в `state.json` (`core/src/domain/TaskState.kt:35`, `core/src/domain/Models.kt:99`). Переходы только `PLANNING→EXECUTION→VALIDATION→DONE` (`TaskStateMachine.canTransition`, `core/src/domain/TaskState.kt:78`), пауза/продолжение на любом этапе кроме `DONE` (`canPause/canResume`). `TASK`-чат инжектит блок `[Задача]` (этап, шаг, ожидаемое действие, история переходов) в system prompt (`core/src/domain/Memory.kt:149`, `core/src/ChatService.kt:187`) — LLM продолжает без повторных объяснений. `planning → execution` дистиллирует ВЕСЬ разговор в чистый план (3–7 шагов, `PLAN_DISTILL_SYSTEM`) в отдельный док `план` (`core/src/ChatService.kt:459`), затем авто-цикл EXECUTION шаг за шагом выдаёт код через `[авто]`-запросы (`autoExecuteCurrentStep`, `core/src/ChatService.kt:540`, цикл `AppViewModel.startAutoExecutionLocked`, `desktop/src/ui/AppViewModel.kt:556`). Пауза останавливает цикл на невыполненном шаге, resume продолжает с него же. После последнего шага — автопереход в VALIDATION. `REPLAN` (`replanTask`, `core/src/ChatService.kt:786`): стоп авто + чистка всех реплик + возврат в PLANNING.
- **Инварианты (День 14)** — отдельный тип сущности `InvariantDoc(scope, parentId, active)` (`core/src/domain/Models.kt:39`), контент в `invariants/<id>.md` (`core/src/data/Store.kt:16`), метаданные в `state.json`. Редактируются напрямую текстом с автосейвом (debounce 600мс, без дистилляции), в отличие от памяти-фактов (`desktop/src/ui/AppViewModel.kt:330`). Наследование как у памяти, но `GENERAL`-чатам не инжектятся (`relevantInvariants`, `core/src/ChatService.kt:163`). В system prompt — блок `[Инварианты — НЕ НАРУШАТЬ]` между `[Задача]` и `[Память:]` с требованием отказа при конфликте (`core/src/domain/Memory.kt:137`). Переход `EXECUTION→VALIDATION` запускает автопроверку: транскрипт EXECUTION + инварианты → `VALIDATE_SYSTEM` → `success | failure+errors` (`validateAgainstInvariants`, `core/src/ChatService.kt:656`, `parseValidationJson`, `core/src/domain/Memory.kt:55`). `failure` → `Retry EXECUTION` (откат `VALIDATION→EXECUTION` через `canRetry`, чистка только `[авто]`-реплик `clearExecutionReplies`, рестарт авто, `core/src/ChatService.kt:807`, `desktop/src/ui/AppViewModel.kt:731`).

## Текущие упрощения

1. ПКМ по реплике открывает контекстное меню строго в позиции курсора (`Копировать`, `Сохранить в память`).
2. Сохраняется вся реплика, не выделение текста (`SelectionContainer` есть).
3. Нет удаления/переименования чатов, проектов, задач (только `MemoryDoc`, `InvariantDoc` и `UserProfile` удаляются).
4. Профили — глобальные (не привязаны к проекту/задаче), один активный на всё приложение.
5. В DONE-задаче запрещены новые чаты/инварианты (`AppViewModel.commitCreate`, `desktop/src/ui/AppViewModel.kt:262`).
6. Инварианты GENERAL-scope не поддерживаются UI (только PROJECT/TASK через `+ → Инварианты`); `GENERAL`-чатам инварианты не инжектятся.

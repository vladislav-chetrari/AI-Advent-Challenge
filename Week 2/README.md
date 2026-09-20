# Week 2 — Memory Agent + Personalization (Desktop, Compose Multiplatform + DeepSeek)

Кодинг-агент с явной трёхуровневой моделью памяти + персонализацией. Задачи: `Task 1.md` (День 11 — модель памяти) и `Task 2.md` (День 12 — персонализация).

Видео-демо: `Task 1 demo.mp4`, `Task 2 demo.mp4`.

## Запуск

```sh
cd "Week 2"
./kotlin run :desktop      # GUI
./kotlin build             # только сборка
```

Нужен `DEEPSEEK_API_KEY`: env-переменная или `.env` (`DEEPSEEK_API_KEY=...`)
рядом с проектом / в корне репо (поиск вверх 6 уровней как в Week 1) / в `~/.ai-advent-week2/.env`.
Модель фиксирована: `deepseek-chat` (`core/src/network/LlmClient.kt:64`).

Данные лежат в `~/.ai-advent-week2/` (`core/src/data/Store.kt:86`): `state.json` + `memory/*.md`.

## Структура

```
Week 2/
  project.yaml            # модули: core, desktop (agent — пустой, резерв)
  kotlin, kotlin.bat      # враппер Kotlin Toolchain 0.12.0 (как в Week 1)
  core/src/
    domain/Models.kt      # Project, Task, Chat, Scope, MemoryDoc, ChatMessage, UserProfile, AppState
    domain/Memory.kt      # DISTILL_SYSTEM, parseDistillJson, PromptBuilder (BASE, profileBlock, buildSystemPrompt, effectiveHistory)
    data/Store.kt         # state.json + memory/*.md, defaultDir ~/.ai-advent-week2
    network/LlmClient.kt  # DeepSeek chat/completions, LlmResult (Ok/HttpError/NetworkError/Empty)
    network/ApiKeyProvider.kt # override → env → .env вверх → ~/.ai-advent-week2/.env
    ChatService.kt        # фасад: дерево + память + профили + ask + distill/commit
  desktop/src/
    main.kt               # Window, дерево, ChatPane/MemoryPane, диалоги (Create/Save/Profile)
    ui/AppViewModel.kt    # Selection, CreateKind, SaveDialog, ProfileDialog, UiState, refresh/send/toggle
    ui/ChatMarkdown.kt    # chatMarkdownTypography() — компактная типографика для markdown
  docs/
    MEMORY.md             # модель памяти (3 слоя), скоупы, наследование, персонализация, сборка промпта, save-flow
    CORE.md               # API core-модуля (ChatService, Store, LlmClient, PromptBuilder, ApiKeyProvider)
    UI.md                 # дерево, чат, viewer, диалоги, профиль, сценарии демо
```

## Ключевые решения

- **Память shared по scope**, а не per-chat (`docs/MEMORY.md:15`): два чата одной задачи делят один `task`-док — дублей нет.
- **Без суммаризации**: Sliding Window `N=12` + инжект фактов в system prompt (`core/src/domain/Memory.kt:42`). Cap `~800` токенов (`3200` символов) на док. Старое молча отбрасывается, важное живёт в фактах.
- **Сохранение явное**: `ПКМ → Сохранить в память` → LLM-дистилляция (`DISTILL_SYSTEM`) → редактируемый факт → выбор цели (scope + parent + `.md`). Сырец не хранится (`docs/MEMORY.md:40`).
- **Хранилища разделены**: сообщения чата — `state.json` (краткосрочная), память — `.md` файлы (рабочая/долговременная).
- **Персонализация (День 12)** — `UserProfile(name, style, format, constraints)` в `state.json` (`core/src/domain/Models.kt:45`). `activeProfileId == null` → Аноним, в промпт ничего не добавляется (`core/src/domain/Memory.kt:47`, `core/src/ChatService.kt:116`). Активный профиль инжектится блоком `[Профиль: ...]` перед блоками памяти при каждом `buildSystemPrompt` (`core/src/ChatService.kt:109`). Переключение чипами в диалоге профиля мгновенно меняет следующий system prompt — на этом строится демо Task 2.
- **Стартовые доки не создаются автоматически**: `createProject`/`createTask` создают только папки (`desktop/src/ui/AppViewModel.kt:207`); все `.md` — только через save-диалог (явный выбор).

## Текущие упрощения

1. ПКМ по реплике открывает контекстное меню строго в позиции курсора (`Копировать`, `Сохранить в память`) — `desktop/src/main.kt:363`.
2. Сохраняется вся реплика, не выделение текста (`SelectionContainer` есть).
3. Нет удаления/переименования чатов, проектов, задач (только `MemoryDoc` и `UserProfile` удаляются).
4. Профили — глобальные (не привязаны к проекту/задаче), один активный на всё приложение.

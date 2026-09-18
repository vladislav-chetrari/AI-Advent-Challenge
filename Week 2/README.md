# Week 2 — Memory Agent (Desktop, Compose Multiplatform + DeepSeek)

Кодинг-агент с явной трёхуровневой моделью памяти. Задача: `Task 1.md` (День 11).

## Запуск

```sh
cd "Week 2"
./kotlin run :desktop      # GUI
./kotlin build             # только сборка
```

Нужен `DEEPSEEK_API_KEY`: env-переменная или `.env` (`DEEPSEEK_API_KEY=...`)
рядом с проектом / в корне репо / в `~/.ai-advent-week2/.env`.
Модель фиксирована: `deepseek-chat` (`core/src/network/LlmClient.kt`).

Данные лежат в `~/.ai-advent-week2/`: `state.json` + `memory/*.md`.

## Структура

```
Week 2/
  project.yaml            # модули: core, desktop
  kotlin, kotlin.bat      # враппер Kotlin Toolchain 0.12.0 (как в Week 1)
  core/src/
    domain/Models.kt      # Project, Task, Chat, Scope, MemoryDoc, ChatMessage, AppState
    domain/Memory.kt      # DISTILL_SYSTEM, parseDistillJson, PromptBuilder
    data/Store.kt         # state.json + memory/*.md
    network/LlmClient.kt  # DeepSeek chat/completions, LlmResult
    network/ApiKeyProvider.kt
    ChatService.kt        # фасад: дерево + память + ask + distill/commit
  desktop/src/
    main.kt               # Window, дерево, чат, память, диалоги
    ui/AppViewModel.kt    # Selection, CreateKind, SaveDialog, UiState
  docs/
    MEMORY.md             # модель памяти, скоупы, сборка промпта, save-flow
    CORE.md               # API core-модуля
    UI.md                 # дерево, диалоги, сценарии демо
```

## Ключевые решения

- Память **shared по scope**, а не per-chat (подробности: `docs/MEMORY.md`).
- Без суммаризации: Sliding Window `N=12` + инжект фактов в system prompt.
- Сохранение в память **явное**: `⋯ → Сохранить в память` → LLM-дистилляция →
  редактируемый факт → выбор цели (scope + .md). Сырец не хранится.
- Хранилища разделены: сообщения чата — `state.json`, память — `.md` файлы.

## Текущие упрощения

1. Нет нативного ПКМ — только кнопка `⋯` у сообщения (fallback).
2. Сохраняется вся реплика, не выделение текста (`SelectionContainer` есть).
3. Нет удаления/переименования чатов, проектов, задач (только MemoryDoc).

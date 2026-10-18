# Week 6 — Dev Assistant (Day 31 / Task 1)

Десктоп-ассистент разработчика: RAG по документации проекта + живой git-контекст (MCP-тулы).

База — код Week 4 (RAG-движок, чанкеры, Ollama `bge-m3`, SQLite). Вместо wiki-статей — проекты.

## Запуск

```sh
cd "Week 6"
./kotlin run --module desktop
```

Нужны: запущенная Ollama (`ollama serve` + `ollama pull bge-m3` для эмбеддингов
и, например, `ollama pull qwen3:32b` для локальной LLM) и — только для облачного
режима — `DEEPSEEK_API_KEY` (env, корневой `.env` или `~/.ai-advent-week6/.env`).

Данные: `~/.ai-advent-week6/` — `rag.db`, `projects.json`, `chats.json`, `repos/` (клоны).

## Проекты

- `+` в панели «Проекты»: имя + git-URL (клонируется в `repos/<имя>`) или локальный путь (на месте).
- Клик — выбрать проект для вопросов. `⟳` — обновить (`git pull` + доиндексация), `×` — убрать.
- Индексируется только документация: `README*`, `docs/**` (целиком, включая примеры), `*.md`, схемы API (`openapi*.yaml/json`), `*.sql`/`graphql`. Код проекта (`.kt/.java/.py/.js/.ts` вне `docs/`) НЕ эмбеддится.
- Внизу левого сайдбара — «⚙ Настройки»: выбор LLM (локальная Ollama / DeepSeek-облако) и эмбеддинг-модели, как в мобильном Week 5. Пресеты для M3 Pro 64 ГБ: `qwen3:32b` (баланс), `qwen2.5-coder:32b` (код), `llama3.3:70b` (максимум), `qwen2.5:7b` (быстрая).

## Команды чата (Task 1)

- `/help [вопрос]` — без аргумента справка, с вопросом — ответ по проекту (RAG + ветка).
- `/branch` — текущая ветка/коммит (MCP-тул `GitTools`, без LLM).
- `/files [фильтр]` — список файлов проекта (`git ls-files`, без LLM).
- `/diff` — изменения рабочего дерева (`status` + `diff --stat`, без LLM).
- `/status` — проект, путь, ветка, файлы в индексе.
- `/projects` — список проектов.
- Обычный текст — RAG-ответ со ссылками `[N]` и раскрывашкой «источники».

## Как закрыт Task 1

- RAG: README + `docs` + схемы API в SQLite-векторах, чанки `fixed`/`structure`.
- MCP: `core/src/project/GitTools.kt` — `branch` (минимум), `listFiles` (`/files`), `diffStat` (`/diff`), `readFile`, `commit`; ветка/коммит подмешиваются в системный промпт.
- `/help` отвечает на вопросы о структуре проекта с цитатами файлов.

## Task 2 — AI-ревью PR (День 32)

- `cli review --base origin/main --head HEAD --format json --out review.json`:
  freshness RAG (`indexedCommit` vs HEAD + `staleSources`) -> PR-diff (`merge-base...head`) ->
  поиск правил по каждому файлу -> LLM JSON -> валидация строк.
- Ключ только из env `DEEPSEEK_API_KEY` (в Action — из `secrets.DEEPSEEK_API_KEY`), на диск/VPS не пишется.
- Вывод — `ReviewResult` (`core/src/rag/Review.kt`): `summary` (баги/архитектура/рекомендации) + `comments[{path,line,severity,body}]` — только `+`-строки diff, иначе GitHub вернёт 422.
- `--dry-run` — без LLM: freshness + статистика diff (дымовой тест CI без ключа).
- `--verify-review` — pure-проверка парсинга diff/JSON без сети и БД.
- Подключение **чужого** проекта: `integration/` (скопировать `review.yml` + `post_review.py`, задать 3 секрета + `REVIEW_PROJECT`) — см. `integration/README.md`. Движок один на VPS (`server` модуль, `POST /review`), в целевом проекте нет ни Kotlin, ни сборки.
- VPS перед каждым ревью best-effort подтягивает доки (`git pull` + доиндексация, `refresh`), неудача — строка в `warning`.
- Промпт: `buildReviewSystem/buildReviewUser` в `core/src/rag/Prompts.kt` — строгий JSON без фенсов.
- Деплой: `DEPLOY.md` + `Dockerfile.review-server`.

## Task 3 — Ассистент поддержки (День 33)

CRM = **GitHub Issues продукта** (лейбл `support` + `status:*`). Ночной workflow выгружает
тикеты на VPS, сервер разбирает front-matter, раскладывает их в CRM и кладёт в **тот же
RAG-индекс**, что и FAQ — поэтому «такая же ошибка была у других» находится по описанию,
а не только по номеру.

Продукт для демо — [CallowFellow](https://github.com/vladislav-chetrari/CallowFellow):
FAQ в `support/faq.md`, тикеты — Issues с профилем пользователя во front-matter.

### Поток одного сообщения

```
CLI/UI: support-ask --chat 1042 --message "модель падает на 80%"
   └─▶ VPS POST /support/ask
         ├─ MCP-клиент ─▶ POST /mcp  get_ticket(1042)          ← статус, ответственный, обходной путь
         ├─ RAG          ─▶ FAQ + docs проекта (top-K, цитаты [N])
         ├─ MCP-клиент ─▶ POST /mcp  search_tickets(query)      ← только если FAQ не ответил
         └─ DeepSeek     ─▶ ответ: признать проблему, опереться на тикет, дать шаг
```

Один диалог = один тикет (`chatId == ticketId`), история — в `support_chats.json`.

### Ключевые решения

- **Тикет авторитетнее FAQ.** Статус, ответственный, срок и обходной путь берутся только
  из CRM. Если срока в тикете нет — бот обязан сказать «ориентира по срокам пока нет».
- **Что говорить по статусу, считает код, а не модель** (`ticketGuidance`): мелкие LLM путают
  `resolved` и `in-progress`. Поведение воспроизводимо и не зависит от настроек модели.
- **Запрет выдумок** — правила 7–9 в `buildSupportSystem` + отрицательный пример («настройки →
  Система → Диагностика») и потолок `max_tokens = 500`: на длинных ответах модель
  расписывает несуществующие пути и срывается на другой язык.
- **Порядок блоков промпта и бюджет на каждый** (`buildSupportUser`): общий лимит раньше
  резал промпт с конца, из-за чего выпадал именно FAQ — модель отвечала, не видя цитат.
- **Деградация без эмбеддингов**: если Ollama недоступна, ассистент отвечает по тикету из CRM
  и честно пишет об этом в `warning`, вместо того чтобы молчать или упасть.
- **Ночная выгрузка не затирает ручные правки**: снимок GitHub — в `crm/<project>.json`,
  локальные изменения (статус, комментарий, эскалация) — в `crm/<project>.overrides.json`.

### Команды

```sh
support-ask   --chat 1042 --message "текст" [--project CallowFellow] [--server URL] [--topK 8]
support-ticket --list | --id 1042 [--status in-progress] [--assignee ilya] [--comment "..."] [--escalate "..."]
mcp-tools     [--server URL]                      # tools/list CRM-сервера по MCP
support-eval  --cases support/cases.json          # 8 кейсов: тикет/статус/фразы/запрещённое
verify-support                                   # оффлайн-проверка слоя, без сети и БД
```

`--server` (или `$VPS_URL`) — работа по сети на VPS; без него тот же код исполняется локально.
Ключ LLM — из `DEEPSEEK_API_KEY` в env (на VPS прилетает в `X-LLM-Key`).

### Эндпоинты

| Метод | Путь | Назначение |
|---|---|---|
| POST | `/support/ask` | одно сообщение пользователя → `SupportReply` |
| POST | `/mcp` | CRM как MCP-сервер (JSON-RPC 2.0: `initialize`, `tools/list`, `tools/call`) |
| POST | `/crm/import` | выгрузка тикетов из Issues + индексация их в RAG |
| POST | `/crm/tickets` | текущее состояние CRM |
| POST | `/crm/ticket` | правка тикета: статус, ответственный, комментарий, эскалация |

MCP-тулы: `get_ticket`, `list_open_tickets`, `search_tickets`, `get_user_tickets`.

### Подключение к продукту

Скопировать в репозиторий продукта `integration/support-index.yml` →
`.github/workflows/support-index.yml`, задать `VPS_URL`, `VPS_TOKEN` и
(необязательно) `TICKET_LABEL`. Демо-тикеты создаются скриптом
`scripts/seed_support_tickets.py`. Подробности — `integration/README.md`.

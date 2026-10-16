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

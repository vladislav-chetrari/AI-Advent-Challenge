# Task 2 — деплой ревью на VPS (GCP)

Схема: движок (RAG + LLM) живёт **один раз на VPS**, а каждый ревьюируемый
проект подключается **двумя файлами** (`Week 6/integration/`):

```
чужой проект, PR ──▶ Action (diff + secrets) ──▶ VPS /review ──▶ inline-комменты в PR
                         │                              │
              VPS_URL/VPS_TOKEN/DEEPSEEK_API_KEY    RAG-индекс доков проекта
              (Secrets целевого репозитория)        (git pull перед каждым ревью)
```

## 1. VM (один раз)

e2-medium+, Ubuntu 22.04. Caddy/Nginx с TLS (443/80) → прокси на `127.0.0.1:8080`.

## 2. Зависимости на VM (один раз)

- Java 21, Ollama (`ollama pull bge-m3` — только эмбеддинги; LLM — DeepSeek-cloud,
  поэтому 32B-модели на VPS не нужны),
- клон **этого** репозитория, `cd "Week 6"`.

## 3. Доки целевых проектов — авто-флоу (настройка не нужна)

Сервер сам заводит проект при первом обращении: клонирует `repoUrl` из запроса
в `~/.ai-advent-week6/repos/<имя>`, индексирует доки; при следующих PR —
`git pull` + доиндексация (`refresh: true`, best-effort). Токен приватного
репозитория (`repoToken`) идёт только в git-транспорт и не хранится.

Запасной путь — ручное `project-add` на VPS (доки вне git, клон недоступен):

```sh
cd ~/AI-Advent-Challenge/"Week 6"
./kotlin run --module cli -- project-add --name <имя> --input <git-URL|путь>
./kotlin run --module cli -- project-list
# данные: ~/.ai-advent-week6/: rag.db, projects.json, repos/
```

## 4. Сервер (один раз)

```sh
export PORT=8080 VPS_TOKEN=$(openssl rand -hex 32)
# DEEPSEEK_API_KEY НЕ экспортировать — придёт в X-LLM-Key из GH Secrets целевого проекта
./kotlin run --module server
# systemd: Environment=PORT=8080 Environment=VPS_TOKEN=...
```

Проверка: `curl http://127.0.0.1:8080/health` → `ok`.

## 5. Подключение целевого проекта (на каждый проект)

См. `integration/README.md`, коротко:

1. Скопируй `integration/review.yml` → `<проект>/.github/workflows/ai-review.yml`,
   `integration/post_review.py` → `<проект>/.github/scripts/post_review.py`.
2. В Settings целевого репозитория задай Secrets `VPS_URL`, `VPS_TOKEN`,
   `DEEPSEEK_API_KEY` и Variable `REVIEW_PROJECT` (= имя из шага 3).
3. Открой тестовый PR — бот оставит inline-комменты (`POST /pulls/{pr}/reviews`,
   только `+`-строки) + общий итог: баги / архитектура / рекомендации.

## 6. Dogfooding этого репозитория

`.github/workflows/week6-review.yml` — тот же принцип, но ревьюит PR этого
репозитория локально (Amper CLI в раннере, без VPS) либо через VPS, если задан
`VPS_URL`. Режим выбирается автоматически по наличию секрета.

## 7. Task 3 — поддержка пользователей на том же VPS

Ничего ставить не нужно: тот же `server` модуль и тот же процесс на `:8080`.
CRM — это GitHub Issues продукта, они приезжают ночным workflow и складываются в
`~/.ai-advent-week6/crm/<project>.json`.

Новые эндпоинты (auth — тот же `Authorization: Bearer $VPS_TOKEN`):

| Путь | Что делает |
|---|---|
| `POST /support/ask` | одно сообщение пользователя → `SupportReply` |
| `POST /mcp` | CRM как MCP-сервер (JSON-RPC 2.0) |
| `POST /crm/import` | выгрузка тикетов из Issues + индексация их в RAG |
| `POST /crm/tickets`, `POST /crm/ticket` | состояние CRM и правка тикета |

`DEEPSEEK_API_KEY` по-прежнему прилетает per-request в `X-LLM-Key` — на диск не пишется.
Ollama нужна как и раньше: тикеты кладутся в RAG-индекс (`bge-m3`).

Проверка после деплоя:

```sh
# CRM жива: список тикетов
curl -s -X POST http://127.0.0.1:8080/crm/tickets \
  -H "Authorization: Bearer $VPS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"project":"CallowFellow"}'

# CRM говорит по MCP
./kotlin run --module cli -- mcp-tools --server https://<vps>

# ответ ассистента (ключ придёт из env)
./kotlin run --module cli -- support-ask --chat 1042 \
  --message "модель падает на 80%" --server https://<vps>
```

Подключение продукта — `integration/support-index.yml` + секреты `VPS_URL`, `VPS_TOKEN`
(см. `integration/README.md`). FAQ продукта индексируется сам: это обычный `*.md`.

Что стоит знать при эксплуатации:

- `/crm/import` best-effort по индексации: если Ollama лежит, CRM и точный доступ к
  тикетам работают, пропадает только семантический поиск по обращениям;
- ночная выгрузка GitHub **не затирает** ручные правки (`crm/<project>.overrides.json`);
- при недоступных эмбеддингах `/support/ask` отвечает по тикету и пишет предупреждение
  в `warning`, а не падает.
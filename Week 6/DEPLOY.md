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

## 3. Доки целевых проектов — ночная индексация (настройка не нужна)

Проект заводится сам: workflow `index-docs` по cron (03:17 UTC) или вручную шлёт
`POST /index` с `repoUrl`, сервер клонирует репозиторий в `~/.ai-advent-week6/repos/<имя>`,
делает `git pull` и переиндексирует доки. Полный обход — ~30 минут на 2 vCPU
(эмбеддинги `bge-m3` на CPU), поэтому это отдельная задача, а не шаг внутри ревью.

Ревью индекс не трогает: только проверяет через `git ls-remote`, не разошёлся ли он
с GitHub, и пишет `⚠ RAG: индекс устарел`, если разошёлся. Токен приватного
репозитория (`repoToken` / `X-Repo-Token`) идёт только в git-транспорт и не хранится.

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

### Чат для пользователя (без токенов)

Конечный пользователь не имеет ни `VPS_TOKEN`, ни ключа модели. Поэтому:

- `GET /chat` — страница чата (один HTML, без зависимостей);
- `POST /support/chat` — публичный эндпоинт `{chatId, message}` → `SupportReply`.

Ключ модели берёт **сервер**: `DEEPSEEK_API_KEY` в env или `~/.ai-advent-week6/.env`
(парсер читает строку `DEEPSEEK_API_KEY=...`). Если ключа нет — работает локальная
модель Ollama, если она настроена (`WEEK6_LLM_PROVIDER=ollama`).

Публичность ограничена троттлингом: 12 сообщений в минуту с одного IP.

```sh
# ключ на VPS (нужен для облачной модели)
scp ~/IdeaProjects/AI-Advent-Challenge/.env <user>@<vps>:~/.ai-advent-week6/.env
# или через systemd: Environment=DEEPSEEK_API_KEY=sk-...
```

Проверка: открыть `https://<vps>/chat` и написать вопрос.

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
./kotlin run --module cli -- support-ask --chat 3 \
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

---

# 8. Обновления

Движок и «клиентские» файлы обновляются **раздельно**: код живёт на VPS, а в каждом
ревьюимом репозитории лежат свои копии workflow-скриптов.

## 8.1 Порядок: сначала VPS, потом целевые репозитории

Новый workflow может вызывать новые эндпоинты (`/index`, `/crm/import`, `/mcp`).
Если обновить наоборот, Action получит `404` и упадёт. Всегда: **движок → потом bundle**.

## 8.2 Обновление движка на VPS

```sh
cd ~/AI-Advent-Challenge
git pull --ff-only
cd "Week 6"
sudo systemctl restart review-server
systemctl is-active review-server        # ожидаем active
```

Юнит запускает `./kotlin run --module server`, поэтому при старте модули
пересобираются — первый старт после обновления занимает от десятка секунд
до нескольких минут. Новые зависимости из `module.yaml` Amper скачает сам.

Лучше делать ночью: пока идёт `git pull`+пересборка, текущие ревью и индексация
отвалятся, и Action получит таймаут.

Проверить, что поднялось и эндпоинты на месте:

```sh
curl -s localhost:8080/health                                   # ok
curl -s -o /dev/null -w "%{http_code}\n" -X POST localhost:8080/index \
  -H "Authorization: Bearer $VPS_TOKEN" -H 'Content-Type: application/json' -d '{}'
# 500 + «не указан проект» — эндпоинт есть; 404/405 — сервер старый
```

## 8.3 Обновление bundle в целевых репозиториях

Файлы из `Week 6/integration/` **не подтягиваются автоматически** — их нужно
скопировать и закоммитить в каждый подключённый проект.

| Файл из `integration/` | Куда в целевом проекте |
|---|---|
| `review.yml` | `.github/workflows/review.yml` |
| `index-docs.yml` | `.github/workflows/index-docs.yml` |
| `support-index.yml` | `.github/workflows/support-index.yml` (Task 3) |
| `post_review.py` | `.github/scripts/post_review.py` |

```sh
cd <целевой-проект>
git pull --ff-only
SRC="/путь/к/AI-Advent-Challenge/Week 6/integration"
cp "$SRC/review.yml"          .github/workflows/review.yml
cp "$SRC/index-docs.yml"      .github/workflows/index-docs.yml
cp "$SRC/post_review.py"      .github/scripts/post_review.py
git commit -am "chore: обновить bundle AI-ревью" && git push
```

Если в проекте уже был свой форк workflow — сначала покажи разницу:
`diff .github/workflows/review.yml "$SRC/review.yml"`.

## 8.4 Когда нужна переиндексация

| Причина | Что делать |
|---|---|
| Доки проекта обновились | Ничего: ночной `index-docs` сам сделает `pull` + доиндексацию |
| Смена модели эмбеддингов | Полная переиндексация: старые вектора несовместимы |
| Смена чанкера / стратегии | То же — вектора считаются для старого разбиения |

Модель эмбеддингов лежит в `~/.ai-advent-week6/embed.json` (по умолчанию `bge-m3`).
После смены запусти `index-docs` вручную (Actions → Run workflow) — старые вектора
отбросятся автоматически, но это полный прогон: **~30 минут на 2 vCPU**.
Делать ночью, ревью в это время будет честно писать `⚠ RAG: индекс устарел`.

## 8.5 Smoke-проверка после обновления

```sh
# 1. движок собран и поднят
systemctl is-active review-server && curl -s localhost:8080/health

# 2. логика парсинга diff/JSON цела (без сети и БД)
cd ~/AI-Advent-Challenge/"Week 6" && ./kotlin run --module cli -- verify-review

# 3. индексация жива — ручной запуск index-docs в целевом репо
gh workflow run index-docs --repo <owner>/<repo>

# 4. ревью живо — Re-run all jobs на тестовом PR
gh run list --repo <owner>/<repo> --limit 3
```

Ожидаемый вывод ревью в PR: общий комментарий (итог + баги/архитектура/рекомендации
+ строка `⚠ RAG`, если индекс устарел) и inline-комментарии на `+`-строках.

## 8.6 Откат

```sh
# движок
cd ~/AI-Advent-Challenge
git log --oneline -5                       # найти предыдущий рабочий коммит
git checkout <sha>
cd "Week 6" && sudo systemctl restart review-server

# целевой репозиторий: откатить только bundle
git revert <sha-коммита-с-bundle>
# или вернуть файлы из запасной ветки, если её сохранили при синхронизации
```

Данные (`rag.db`, `projects.json`, `crm/`, `repos/`) живут в `~/.ai-advent-week6/`
и при обновлении/откате кода не страдают. Бэкап перед рискованными операциями:

```sh
tar czf ~/ai-advent-week6-$(date +%F).tar.gz ~/.ai-advent-week6/
```

## 8.7 Логи и типовые сбои

```sh
journalctl -u review-server -n 50 --no-pager   # системный лог
tail -f ~/review-server.log                     # прогресс ревью и индексации
```

| Симптом | Причина | Что делать |
|---|---|---|
| Action: `unknown url type` | в `VPS_URL` нет `http(s)://` | перезаписать секрет |
| Action: `cannot reach VPS host …` | сервер упал или порт закрыт файрволом GCP | `systemctl status`, проверить правило firewall |
| Action: `VPS HTTP 401` | разошёлся `VPS_TOKEN` (VPS ↔ репозиторий) | синхронизировать оба |
| Action: `VPS HTTP 404` | на VPS старый движок без нужного эндпоинта | обновить движок (§8.2) |
| Action: таймаут 600 с | шла тяжёлая индексация — ревью ждал её | повторить запуск; индекс к тому моменту готов |
| В PR вместо inline один общий коммент | GitHub отверг строки (не `+`, или >40) | см. `integration/README.md` |
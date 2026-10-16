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

## 3. RAG-индекс целевого проекта (один раз на проект)

```sh
./kotlin run --module desktop   # кнопка + : имя + git-URL (клон в repos/<имя>) или локальный путь
# данные: ~/.ai-advent-week6/: rag.db, projects.json, repos/
```

Имя запомни — это `REVIEW_PROJECT`. Приватный репозиторий: клонируй на VPS
вручную (deploy key / PAT) и добавь локальным путём.

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

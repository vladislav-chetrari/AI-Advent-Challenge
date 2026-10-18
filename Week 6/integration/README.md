# Подключение AI-ревью к любому репозиторию

Движок ревью (RAG + LLM) живёт **один раз на твоём VPS**. В каждый проект,
который хочешь ревьюить, добавляются **только 2 файла** — никакого Kotlin,
Ollama и сборки в целевом проекте нет.

## Шаг 0. VPS уже поднят (один раз)

См. `../DEPLOY.md`: VM → Ollama (`bge-m3`) → `./kotlin run --module server`
с `VPS_TOKEN`. Проверка: `curl https://<vps>/health` → `ok`.

## Шаг 1. Доки проекта — автоматически (делать ничего не надо)

VPS сам индексирует доки вызывающего проекта при каждом обращении:

- **первый PR** — сервер клонирует репозиторий по `repoUrl` из запроса
  в `repos/<имя>`, индексирует доки (`README/docs/*.md`, схемы API) и ревьюит;
- **каждый следующий PR** — `git pull` + доиндексация только нового
  (вектора переиспользуются, эмбеддинги считаются лишь для новых чанков);
- проект без доков — не ошибка: ревью идёт по общим правилам,
  в итог добавляется `⚠ RAG: ...`.

Имя проекта берётся из `REVIEW_PROJECT` (по умолчанию = имя репозитория).
Приватный репозиторий: workflow сам прикладывает `GITHUB_TOKEN` как `repoToken`
(только для клона/pull доков, на VPS не хранится, живёт минуты).

Ручное `project-add` на VPS — только запасной путь
(доки вне git или клон недоступен с VPS).

## Шаг 2. Скопировать 2 файла в целевой репозиторий

| Этот файл | Куда в целевом проекте |
|---|---|
| `review.yml` | `.github/workflows/ai-review.yml` |
| `post_review.py` | `.github/scripts/post_review.py` |

Ничего править в них не нужно, кроме `BASE_REF` по умолчанию (`main`),
если у проекта другая главная ветка.

## Шаг 3. Секреты и переменная в целевом репозитории

Settings → Secrets and variables → Actions:

- Secrets: `VPS_URL` (`https://<твой-vps>`), `VPS_TOKEN` (тот же, что на VPS),
  `DEEPSEEK_API_KEY` (летит на VPS в заголовке `X-LLM-Key` per-request,
  на диске VPS **не хранится**).
- Variables: `REVIEW_PROJECT` = имя проекта из шага 1. Если не задана —
  используется имя репозитория (`github.event.repository.name`), так что проще
  назвать проект на VPS так же, как репозиторий.

`GITHUB_TOKEN` выдаёт сам GitHub, в workflow уже стоит `pull-requests: write`.

## Как это работает на каждый PR

1. `opened / synchronize / reopened` → Action снимает `git diff origin/main...HEAD`.
2. `POST <VPS_URL>/review` (auth `VPS_TOKEN` + ключ в `X-LLM-Key`).
3. VPS **не трогает индекс**: только дешёво проверяет через `git ls-remote`,
   не устарел ли он, и ищет по RAG правила для каждого файла diff →
   DeepSeek возвращает `{summary, comments[]}` → валидация строк.
   Индекс обновляет отдельный ночной `index-docs` (полная переиндексация ~30 мин).
4. `post_review.py` постит **inline-комменты** через `POST /pulls/{pr}/reviews`
   (только `+`-строки, `side: RIGHT`); если комментов нет — один общий `LGTM`.

## Обновление файлов этого bundle

Файлы из `Week 6/integration/` не обновляются автоматически — после обновления
движка их нужно скопировать в репозиторий заново и закоммитить
(порядок «движок → bundle» и команда `cp` — в `../DEPLOY.md`, раздел 8).

## Проверка

Открой тестовый PR с мелочью (например, `TODO` без Issue или дублированный код) —
бот должен оставить inline-коммент именно на этой строке + общий итог
(баги / архитектура / рекомендации + строка `⚠ RAG:` если доки протухли).

---

# Task 3 — ассистент поддержки

CRM ассистента — **Issues этого же репозитория** с лейблом `support`. Отдельная
подключаемая часть: один файл workflow, ключ LLM сюда не нужен (генерация идёт
в `/support/ask`, ключ прилетает per-request в `X-LLM-Key`).

## Подключение (один файл)

| Этот файл | Куда в целевом репозитории |
|---|---|
| `support-index.yml` | `.github/workflows/support-index.yml` |

Secrets те же: `VPS_URL`, `VPS_TOKEN`. Variables:

- `REVIEW_PROJECT` — имя проекта в RAG (то же, что для ревью);
- `TICKET_LABEL` — лейбл тикетов, по умолчанию `support`.

Workflow выгружает Issues ночью (03:47 UTC) и по кнопке: снимает список через GitHub API,
отбрасывает PR, отправляет `POST /crm/import`. Сервер разбирает front-matter тикета,
раскладывает его в CRM и индексирует в общий RAG-индекс проекта.

## Формат тикета

Лейблы задают состояние, YAML-фронтматтер в теле Issue — данные:

```yaml
---
ticket:
  user:
    id: u17
    name: Ирина
    plan: pro
    device: Pixel 8
    os: Android 14
    app: 0.1.0
  component: models
  assignee: ilya
  workaround: скачать .litertlm вручную в models/llm/
  fix: перезагрузка без Range-заголовка, рвётся на длинных соединениях
  eta: релиз 0.1.1
---

Текст обращения пользователя.
```

Лейблы: `support`, `type:bug|question|known-issue`, `severity:critical|major|minor`,
`status:open|in-progress|needs-info|resolved`, `component:<имя>`.

**Лейбл важнее фронтматтера**, если заданы оба. Закрытый Issue без лейбла = `closed`.
Если поля `eta` нет — ассистент обязан сказать, что ориентира по срокам нет, и никогда
не называет срок сам.

Профиль пользователя живёт в тикете: у CallowFellow нет аккаунтов, а `user` даёт
ассистенту контекст устройства и версии.

## FAQ

Обычный `*.md` в репозитории продукта (`support/faq.md`) — индексируется ночным
`index-docs` без дополнительных настроек, отдельный workflow не нужен.

## Демо-тикеты

```sh
python3 ../scripts/seed_support_tickets.py --dry-run
python3 ../scripts/seed_support_tickets.py
```

Скрипт идемпотентен (ищет Issue по заголовку), создаёт 6 тикетов, покрывающих все
статусы: в работе / ждём ответа / решён / вопрос.

## Проверка с терминала

```sh
export VPS_URL=https://<твой-vps> VPS_TOKEN=... DEEPSEEK_API_KEY=...
./kotlin run --module cli -- support-ticket --list
./kotlin run --module cli -- support-ticket --id 3 --status resolved --comment "починили, релиз 0.1.1"
./kotlin run --module cli -- support-ask --chat 3 --message "модель так и не скачалась"
```

Смена статуса меняет ответ бота в том же диалоге: это и есть «учёт контекста тикета».

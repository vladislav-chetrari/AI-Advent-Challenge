# Подключение AI-ревью к любому репозиторию

Движок ревью (RAG + LLM) живёт **один раз на твоём VPS**. В каждый проект,
который хочешь ревьюить, добавляются **только 2 файла** — никакого Kotlin,
Ollama и сборки в целевом проекте нет.

## Шаг 0. VPS уже поднят (один раз)

См. `../DEPLOY.md`: VM → Ollama (`bge-m3`) → `./kotlin run --module server`
с `VPS_TOKEN`. Проверка: `curl https://<vps>/health` → `ok`.

## Шаг 1. Проиндексировать целевой проект на VPS (один раз на проект)

На VPS добавить проект в RAG — его доки станут «правилами» для ревью
(на headless VM десктопа нет, поэтому через CLI):

```sh
cd ~/AI-Advent-Challenge/"Week 6"
./kotlin run --module cli -- project-add --name <имя> --input <git-URL|путь>
```

Имя проекта (например `my-service`) запомни — оно пойдёт в `REVIEW_PROJECT`.
Неизвестное имя на сервере даст честную ошибку, а не молчание.
Приватный репозиторий: клонируй на VPS вручную (deploy key / PAT) и добавь
локальным путём; дальше сервер сам делает `git pull` перед каждым ревью
(`refresh: true`, best-effort — неудача лишь попадёт в `warning`, ревью не упадёт).

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
3. VPS: `git pull` доков → RAG-поиск правил по каждому файлу diff →
   DeepSeek возвращает `{summary, comments[]}` → валидация строк.
4. `post_review.py` постит **inline-комменты** через `POST /pulls/{pr}/reviews`
   (только `+`-строки, `side: RIGHT`); если комментов нет — один общий `LGTM`.

## Проверка

Открой тестовый PR с мелочью (например, `TODO` без Issue или дублированный код) —
бот должен оставить inline-коммент именно на этой строке + общий итог
(баги / архитектура / рекомендации + строка `⚠ RAG:` если доки протухли).

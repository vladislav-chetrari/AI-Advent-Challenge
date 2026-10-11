# Week 5 Task 5 — приватный AI-сервис (Kotlin + Ollama + OpenAI API)

День 30: локальная LLM как сервис — HTTP API, чат по сети, rate limit / max context.
Без UI: вместо Compose-экранов — стандартный **OpenAI-совместимый API**.

Стек: **Kotlin Toolchain 0.12.0 (Amper), Ktor Server (CIO) + Ktor Client**,
рантайм инференса — **Ollama** (llama.cpp + CUDA), RAG-индекс — **SQLite**
(plain JDBC, без Room). Проект отдельный (`Week 5/Task 5/`), скопирован
по образцу `Week 5/Task 1/` (тот же `QwenClient` с OOM-фолбэком),
RAG-логика портирована из корневого `Week 5/shared` (Task 3/4).

## Модели (дефолт под RTX 3060 12GB)

| Роль | Модель | Размер | Примечание |
|---|---|---|---|
| LLM | `qwen2.5:7b` (Q4) | ~4.7 ГБ | влезает всегда с `num_ctx=4096` |
| Embed | `embeddinggemma:300m` | ~300 МБ | 768d, мультиязычная (план) |

Проверено на: `qwen2.5:7b` + `bge-m3:latest` (1024d, уже была в `ollama list`,
сервер берёт размерность зондом — любая embed-модель работает).
Хочешь по плану — скачай `embeddinggemma`:

```sh
ollama serve
ollama pull qwen2.5:7b
ollama pull embeddinggemma:300m   # или работай с bge-m3 через TASK5_EMBED
```

Память: `7B Q4 ~4.7GB + KV-cache(4096) ~1-1.5GB = ~6-7GB` из 12GB.
`num_ctx=8192+` на 12GB — лотерея (`cudaMalloc failed`, см. Task 1).
У embed-модели `keep_alive=5m`, чтобы не вытесняла LLM из VRAM.

## Запуск

```sh
cd "Week 5/Task 5"
./kotlin build
# дефолт: LLM qwen2.5:7b, embed embeddinggemma:300m, порт 8080, без auth
./kotlin run --module server
# свой embed / ключ / порт:
TASK5_EMBED=bge-m3:latest TASK5_API_KEY=secret123 TASK5_PORT=8080 ./kotlin run --module server
```

Переменные: `TASK5_PORT` (8080), `OLLAMA_URL` (http://localhost:11434),
`TASK5_API_KEY` (пусто = auth выключен, только для локалки),
`TASK5_LLM`, `TASK5_EMBED`, `TASK5_NUM_CTX` (4096), `TASK5_MAX_TOKENS` (1024),
`TASK5_MODELS_DIR` (models — там `rag.db`).

Проверка:

```sh
curl http://localhost:8080/health
curl http://localhost:8080/v1/models
curl http://localhost:8080/v1/chat/completions -H "Content-Type: application/json" \
  -d '{"model":"qwen2.5:7b","messages":[{"role":"user","content":"Привет!"}],"max_tokens":128}'
```

PowerShell-скрипты: `test-scripts/smoke.ps1` (весь цикл),
`test-scripts/loop.ps1 -N 10` (стабильность для видео).

## Desktop-клиент (`desktop/`)

CMP-десктоп к тому же localhost-серверу (UI по образцу `Week 4/desktop`:
слева сайдбар «Чаты + База знаний», справа чат с Markdown).
Вся RAG-логика остаётся на сервере — клиент только шлёт историю
в `POST /v1/chat/completions` и дергает `/v1/docs` + `/health`.

```sh
cd "Week 5/Task 5"
./kotlin run --module desktop
```

Сначала подними сервер (`./kotlin run --module server`, дефолт
`http://localhost:8080`), потом открывай десктоп. Подключение (base URL /
ключ / модель / temperature / max tokens / system) — через ⚙, хранится
в `~/.ai-advent-task5/settings.json`, чаты — в `~/.ai-advent-task5/chats.json`.
Env-оверрайды как в CLI: `TASK5_BASE_URL` / `TASK5_API_KEY`.

## CLI-клиент (`cli/`)

Маленький консольный клиент к своему же серверу (чат + документы).
HTTP-обёртка — `core/src/api/Task5Client.kt`, команды — `cli/src/Main.kt`:

```sh
cd "Week 5/Task 5"
./kotlin run --module cli -- health
./kotlin run --module cli -- ask "Кто такие вороны? Ответь одним предложением."
./kotlin run --module cli -- chat --model qwen2.5:7b
./kotlin run --module cli -- docs add --type wiki "Ворон"
./kotlin run --module cli -- docs add --type text --title "Заметка" --file ./note.txt
./kotlin run --module cli -- docs list
./kotlin run --module cli -- docs delete "user/zametka"
./kotlin run --module cli -- embed --model bge-m3:latest "Вороны каркают"
```

Флаги: `--base-url` (иначе `TASK5_BASE_URL`, иначе `http://localhost:8080`),
`--key` (иначе `TASK5_API_KEY`), `--model` (пусто = дефолт сервера),
`--temperature`, `--max-tokens`, `--system`. В `chat` команды:
`/clear /temp <n> /system <текст> /exit` (паттерн из Task 1).
Кириллица в Windows-консоли лечится так же, как в Task 1 (`chcp 65001` + UTF-8 потоки).

## API

Все ошибки — в OpenAI-обёртке `{"error":{"code","message"}}`.

```
GET  /health                        # открыто: {status, llm, embed, ollama_ok, rag_docs, rag_chunks}
GET  /v1/models                     # список LLM + embed
POST /v1/chat/completions           # {model, messages[], temperature, max_tokens, stream:false}
POST /v1/embeddings                 # {model, input: string|string[]} -> {data[].embedding}
POST /v1/docs  {type=text|wiki, title, input} -> 202 {id, title, chunks}
GET  /v1/docs                       # [{id, title, chunks, chars, active}]
DELETE /v1/docs/{id}                # {deleted: id} (id со слэшами, напр. wikipedia/ru/Ворон)
```

`POST /v1/chat/completions` внутри: small-talk (`QueryRouter`) — свободный ответ
без retrieval; иначе dense-поиск + гейты Task 4 (`MIN_SCORE=0.55`, `hasTokenOverlap`),
промпты `Prompts.ragSystem/chatUser`, ответ + `Источники: [1] ...`.
`stream:true` → `400 stream_not_supported` (стриминг не входит в Task 5).

Лимиты: auth `Authorization: Bearer <TASK5_API_KEY>` (кроме `/health`) → `401`;
30 req/min с IP (чат/эмбеддинги), 5/мин индексация → `429 + Retry-After`;
pre-flight контекста (`~len/4 + max_tokens > num_ctx`) → `400 context_too_large`;
запрос > 32000 символов → `400`; не больше 2 параллельных инференсов
(RTX 3060), занято → `429 overloaded`; таймаут инференса 180с.

## Структура

```
project.yaml gradle/ kotlin[.bat]   # Amper-каркас (как в Task 1/)
core/src/llm/   QwenModel.kt QwenClient.kt (порт Task 1) EmbedClient.kt (POST /api/embed)
core/src/rag/   Chunker.kt EmbedMath.kt QueryRouter.kt Prompts.kt WikiLoader.kt
                Retrieval.kt (гейты) RagStore.kt (SQLite) Models.kt
core/src/api/   OpenAiDto.kt        # DTO /v1/*
core/src/util/  TokenEst.kt         # оценка токенов для pre-flight
server/src/     Main.kt             # Ktor: routes + auth + rate limit + semaphore
models/         rag.db (gitignore)  # RAG-индекс
test-scripts/   smoke.ps1 loop.ps1
```

## Проверка для видео

1. `ollama list` + `GET /health` (с другого устройства в LAN — доступ по сети).
2. `POST /v1/docs` (wiki-статья) → `GET /v1/docs`.
3. Три чата: small-talk без источников / вопрос по базе с `Источниками` / out-of-domain (честный фолбэк).
4. `POST /v1/embeddings` → dim модели.
5. Лимиты: гигантский запрос → `400`, неверный ключ → `401`.
6. `loop.ps1 -N 10` — стабильность серией.

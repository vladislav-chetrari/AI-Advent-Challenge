# Week 5 Task 1 — локальная LLM (Ollama + Qwen, Kotlin Toolchain)

День 26: модель запускается локально, доступ через CLI, отвечает на запросы.

## Модель

Дефолт — `qwen2.5:7b` (~4.7 ГБ Q4).
Запасной — `qwen3:8b` (лучше русский, `think=false` уже зашит в клиент).

```sh
ollama serve
ollama pull qwen2.5:7b
```

## Запуск (та же команда на Mac и ПК)

```sh
cd "Week 5/Task 1"
./kotlin run --module cli -- status
./kotlin run --module cli -- ask "Ответь одним предложением: что такое Ollama?"
./kotlin run --module cli -- demo --model qwen2.5-7b
./kotlin run --module cli -- chat --model qwen2.5-7b
./kotlin build   # только сборка
```

`demo` — 3 запроса разной сложности одним прогоном (простой / средний / сложный),
удобно для видео. `status` — проверка сервера + каких моделей нет (`ollama pull` подскажет сам).

## Контекст и видеопамять (важно для RTX 3060 12GB)

Рабочий контекст по умолчанию — `num_ctx=4096` (флаг `--num-ctx` / `--ctx` или env `OLLAMA_NUM_CTX`).
Большой контекст на 12GB — лотерея: 8192/32768 иногда стартуют, иногда роняют
llama-server с `cudaMalloc failed: out of memory / failed to allocate buffer for kv cache`
(один только буфер под KV-cache просит ~1.9 ГБ, итог зависит от свободной VRAM
в момент загрузки). 32768 стабильно — только для Mac 64GB или карт с запасом VRAM.

Если OOM всё же случился (HTTP 500), клиент сам повторит запрос с ctx вдвое меньше
(…→4096→2048) и запомнит рабочий лимит до конца сессии, а если не влез и туда —
подскажет, что делать
(`--num-ctx 4096`, выгрузить лишние модели через `ollama ps`, взять `qwen2.5:1.5b`).

## Windows: кириллица и запуск

Клиент сам переключает консоль на UTF-8 (`chcp 65001`) и держит stdout/stderr/stdin
в UTF-8, поэтому `Чат:` / `Команды:` / `вы>` отображаются нормально, а русский ввод
в `chat` не бьётся. Если терминал старый (cmd без TrueType/Windows Terminal) —
открой Windows Terminal или выполни вручную перед запуском:

```powershell
chcp 65001
.\kotlin.bat run --module cli -- chat
```

SLF4J-ворнинг (`No SLF4J providers were found`) убран зависимостью `slf4j-nop` —
это был шум от ktor, на работу не влиял.

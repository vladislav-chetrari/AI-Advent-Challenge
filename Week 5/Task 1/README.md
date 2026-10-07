# Week 5 Task 1 — локальная LLM (Ollama + Qwen, Kotlin Toolchain)

День 26: модель запускается локально, доступ через CLI, отвечает на запросы.

## Модель

Дефолт — `qwen2.5:7b` (~4.7 ГБ Q4): один тег работает и на MacBook M4 Pro 64GB,
и на ПК Ryzen 3600X + RTX 3060 12GB (целиком в VRAM даже с `num_ctx 32768`).
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

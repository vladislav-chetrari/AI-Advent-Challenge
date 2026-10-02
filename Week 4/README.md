# Week 4 — чаты по своим документам (Desktop, Kotlin Toolchain + локальные эмбеддинги + DeepSeek)

День 21 (Task 1): индексация документов + чаты с RAG.

## Запуск

```sh
cd "Week 4"
./kotlin run --module desktop      # GUI: чаты + база знаний
./kotlin run --module cli -- index <файл-или-папка...>  # headless-индексация
./kotlin run --module cli -- wiki "Искусственный интеллект"  # статья Википедии -> корпус
./kotlin run --module cli -- compare <путь...>  # сравнение 2 стратегий чанкинга (Task 1)
./kotlin run --module cli -- ask "вопрос..."     # RAG-ответ
./kotlin build             # только сборка
```

Нужен `DEEPSEEK_API_KEY` только для ответов: env, `.env` (поиск вверх
6 уровней, находится корневой `.env`), `~/.ai-advent-week4/.env`.
Эмбеддинги считает локальная Ollama — см. раздел ниже.

Данные: `~/.ai-advent-week4/` — `rag.db` (чанки + вектора float32),
`corpus/` (статьи Wiki), `chats.json` (чаты и сообщения), `sources.json` (источники),
`embed.json` (подключение к эмбеддинг-модели).

## Эмбеддинг-модель (Ollama)

Нужна запущенная Ollama с эмбеддинговой моделью:

```sh
ollama serve            # сервер (или Ollama.app с автозапуском)
ollama pull bge-m3      # модель для русского корпуса (~1.2 ГБ)
```

Дефолт приложения: `http://localhost:11434` + `bge-m3`. Другое — кнопка ⚙
рядом с «Базой знаний» (модель + адрес с портом + кнопка «Проверить»),
либо env `WEEK4_OLLAMA_URL` / `WEEK4_EMBED_MODEL` (выше настроек из UI, удобно для CLI).

Без модели на порту приложение честно говорит об этом: включение RAG,
индексация и вопросы завершаются понятной ошибкой, а не тихим мусором.
После смены модели нужна переиндексация (размерность векторов другая).

## Как пользоваться

Слева дерево как в Week 2:

- **Чаты** — `+` создаёт чат, клик по 💬 включает/выключает RAG у чата,
  `×` удаляет. С выключенным RAG чат отвечает напрямую через DeepSeek,
  без базы знаний. Ответы RAG-чата идут со ссылками и раскрывающимся
  списком «источники».
- **База знаний** — `+` добавляет PDF-книги, тексты, папку или статью
  Википедии. Добавленное индексируется само (обе стратегии, в фоне).
  Клик по 📄 открывает текст, клик по иконке включает/выключает документ
  в RAG-контексте (как M-доки в Week 2), `×` убирает документ из базы.

Сравнение стратегий чанкинга (fixed vs structure, требование Task 1) —
команда `compare` в CLI: индексирует корпус обеими и гоняет пробные запросы.

## Как устроено

```
core/src/
  rag/RagModels.kt    # RawDoc, Chunk(id/source/title/section/ord/strategy/tokens), DocInfo, ScoredChunk
  rag/Chunkers.kt     # Chunker + FixedSizeChunker + StructureChunker (части секций [k/N])
  rag/DocumentLoader.kt # файлы/папки -> RawDoc (PDF через PDFBox, .md/.txt/код)
  rag/WikiLoader.kt   # статья Википедии (название/URL) -> RawDoc
  rag/Embeddings.kt   # EmbeddingProvider + HashingEmbedder (тесты) + CachedEmbedder
  rag/OllamaEmbeddings.kt # вектора через локальную Ollama (/api/embed, L2-норма)
  rag/EmbedSettings.kt  # модель + адрес:порт (embed.json, env выше)
  rag/VectorStore.kt  # SQLite (BLOB float32 LE) + точный top-K по косинусу + фильтр документов
  rag/RagService.kt   # reindex / search / compare / ask / askPlain / indexedDocs
  rag/ChatStore.kt    # чаты + сообщения (chats.json)
  rag/SourcesStore.kt # источники + выключенные доки (sources.json)
  llm/LlmClient.kt    # DeepSeek chat/completions (как в Week 2)
  llm/ApiKeyProvider.kt # ключ: override -> env -> .env вверх -> ~/.ai-advent-week4/.env
desktop/src/
  main.kt             # окно: дерево + чат/док + диалоги (как Week 2)
  ui/AppViewModel.kt  # состояние, вызовы RagService, автоиндексация
  ui/ChatMarkdown.kt  # компактная типографика markdown
cli/src/Main.kt       # index|compare|wiki|search|ask + скрытый selftest
```

## Ключевые решения

- **Эмбеддинги — только локальная модель**: Ollama + `bge-m3` (1024 dim,
  русский хороший), подключение настраивается (⚙ / env). Никаких молчаливых
  фолбэков: нет модели на порту — везде понятная ошибка. Смена модели требует
  переиндексации (размерность другая).
- **Хранилище — SQLite**: чанки + вектора (BLOB float32 LE, ~1.5 КБ/чанк),
  поиск — точный косинус с кучей top-K. FAISS не взят сознательно: нативный C++
  без Kotlin API, а при тысячах чанков brute-force даёт миллисекунды и тот же
  результат, что FlatIP.
- **RAG на чат, а не глобально**: флаг `ragEnabled` у чата + фильтр активных
  документов в `search(onlySources)` — выключенный документ физически не попадает
  в контекст.
- **Масштабирование**: новый чанкер = класс + имя; Ollama/OpenAI-эмбеддинги =
  ещё одна реализация `EmbeddingProvider`; HNSW/rerank/порог — внутрь
  `VectorStore.search` / между search и промптом в `ask()`, интерфейсы не меняются.

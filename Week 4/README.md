# Week 4 — чаты по своим документам (Desktop, Kotlin Toolchain + локальные эмбеддинги + DeepSeek)

День 21 (Task 1): индексация документов + чаты с RAG.

## Запуск

```sh
cd "Week 4"
./kotlin run --module desktop      # GUI: чаты + база знаний
./kotlin run --module cli -- wiki "Искусственный интеллект"  # статья Википедии -> корпус
./kotlin run --module cli -- ask "вопрос..." [--strategy both] [--topK 20] [--filter on|off] [--temperature 0.35] [--postK 5|off] [--rewrite on|off]  # RAG-ответ со 2 этапом (Task 3)
./kotlin run --module cli -- eval-modes [--probe "..." ...] [--strategy both] [--topK 20] [--temperature 0.35] [--postK 5|off]  # сравнение режимов без переиндексации (Task 3)
./kotlin build             # только сборка
```

Нужен `DEEPSEEK_API_KEY` только для ответов: env, `.env` (поиск вверх
6 уровней, находится корневой `.env`), `~/.ai-advent-week4/.env`.
Эмбеддинги считает локальная Ollama — см. раздел ниже.

Данные: `~/.ai-advent-week4/` — `rag.db` (чанки + вектора float32),
`corpus/` (копии статей Wiki), `chats.json` (чаты и сообщения), `sources.json` (выключенные доки),
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
- **База знаний** — `+` добавляет статью Википедии (название или URL).
  В диалоге выбирается стратегия чанкинга: фиксированный размер,
  по структуре или обе. Документ сразу появляется в списке как
  индексирующийся (некликабельная строка + прогрессбар), остальные документы
  при этом не переиндексируются. В строке видна подпись метода чанкинга
  («фиксированный» / «структурный» / «обе стратегии») — по факту индекса.
  Клик по 📄 открывает текст, клик по иконке включает/выключает документ
  в RAG-контексте (как M-доки в Week 2), `×` убирает документ из базы.

Сравнение стратегий чанкинга (fixed vs structure, требование Task 1) —
индексируй одну статью обеими стратегиями и сравни выдачу
в `search --strategy fixed|structure` (score + section),
а не только в `ask` — LLM сглаживает разницу. Wiki-статьи качаются с
`exsectionformat=wiki` (`== секции ==`), structural понимает `##` и `==`,
а plain-текст без разметки режет по эвристике заголовков/абзацам —
поэтому мелкий факт (сольдо Катерины) и вопрос «в каком разделе...»
дают разные top-K: у fixed `section` пустой, у structure — имя секции.

## Второй этап: фильтр + rewrite (Task 3, День 23)

Пайплайн ответа (задание 3): забрать широко — top-K до фильтрации
(`--topK`, деф. 20) по оригиналу и rewrite-вариантам → слить max-score →
фильтр вкл/выкл (`--filter on|off`, деф. on): отсечение по температуре
(`--temperature 0–1`, деф. 0.35) → опциональный top-K после фильтрации
(`--postK 1–topK`, деф. 5; `off` = без ограничения, идут все прошедшие порог).
`--rewrite on` просит DeepSeek переписать вопрос в 1–2 поисковых запроса
(нужен `DEEPSEEK_API_KEY`); любая ошибка rewrite — тихий fallback на оригинал.
Если после фильтра пусто — честный отказ вместо галлюцинации
(«ничего релевантного не нашлось, температура …»).

Замер на корпусе «Чёрный дрозд» (`eval-modes`, topK=20, postK=5, температура 0.35):

| Режим | kept (сред.) | dropped (сред.) | top-1 score |
|---|---|---|---|
| без фильтра | 5.0 | 15.0 | ~0.36 |
| с фильтром | 3.3 | 16.8 | ~0.36 |
| фильтр + rewrite | 5.0 | 23.8 | 0.42–0.71 |

Выводы: порог подрезает хвост (при off-topic «рецепт борща» выдача всё равно
непустая — косинус bge-m3 плохо отделяет «чужое», это нормально);
главный выигрыш — rewrite: «Сколько яиц в кладке?» без него весь top-20
ниже температуры 0.35 (вопрос отсекается целиком), с rewrite top-1 = 0.676,
нужная секция «Размножение» находится сразу. Дефолт 0.35 подобран так,
чтобы держать релевантное (0.35–0.39) и резать хвост.

В GUI: кнопка «RAG ⚙» в шапке чата — top-K до, rewrite, фильтр вкл/выкл,
а при включённом фильтре температура (слайдер 0–1) и опциональный top-K после
(1–topK, пусто = все прошедшие); применяются сразу. Под каждым RAG-ответом
строка диагностики («поиск: 20 → в контекст: 4 (отсечено: 16) · фильтр: темп. 0.35 …»).

## Как устроено

```
core/src/
  rag/RagModels.kt    # RawDoc, Chunk(id/source/title/section/ord/strategy/tokens), DocInfo(+стратегия/статус), DocumentEntry, ScoredChunk, RagAnswer(+RetrievalDebug), ModeReport
  rag/Rerank.kt       # Задание 3: RerankConfig(mode off/threshold, retrieveK=top-K до, finalK=top-K после?, minScore=температура) + rerank() + mergeRetrievals
  rag/QueryRewrite.kt # Task 3: rewriteQueries через DeepSeek (fallback — оригинал)
  rag/Chunkers.kt     # Chunker + FixedSizeChunker + StructureChunker (части секций [k/N])
  rag/WikiLoader.kt   # статья Википедии (название/URL) -> RawDoc (единственный источник документов)
  rag/Embeddings.kt   # EmbeddingProvider + HashingEmbedder (тесты) + CachedEmbedder
  rag/OllamaEmbeddings.kt # вектора через локальную Ollama (/api/embed, L2-норма)
  rag/EmbedSettings.kt  # модель + адрес:порт (embed.json, env выше)
  rag/DbMigrations.kt # Flyway-миграции SQLite (старые БД до Flyway сносятся — там только чанки)
  rag/VectorStore.kt  # SQLite через Flyway (chunk PK(id,strategy) + document) + точный top-K по косинусу + фильтр документов
  rag/RagService.kt   # fetchWikiDoc / indexDocs / indexNewDocs (точечно, с прогрессом) / search(both) / searchWithRerank / compareModes / ask (retrieve->rewrite->rerank->LLM) / indexedDocs
  rag/ChatStore.kt    # чаты + сообщения (chats.json, у ответа — info с диагностикой поиска)
  rag/SourcesStore.kt # выключенные из RAG доки (sources.json)
  llm/LlmClient.kt    # DeepSeek chat/completions (как в Week 2)
  llm/ApiKeyProvider.kt # ключ: override -> env -> .env вверх -> ~/.ai-advent-week4/.env
core/resources/
  db/migration/V1__init.sql # chunk + document (Flyway)
  desktop/src/
  main.kt             # окно: дерево + чат/док + диалоги (как Week 2) + RagSettingsDialog (Task 3)
  ui/AppViewModel.kt  # состояние, вызовы RagService, точечная индексация с плейсхолдерами, RAG-настройки Task 3
  ui/ChatMarkdown.kt  # компактная типографика markdown
cli/src/Main.kt       # eval-modes|wiki|search|ask + скрытый selftest
```

## Ключевые решения

- **Эмбеддинги — только локальная модель**: Ollama + `bge-m3` (1024 dim,
  русский хороший), подключение настраивается (⚙ / env). Никаких молчаливых
  фолбэков: нет модели на порту — везде понятная ошибка. Смена модели требует
  переиндексации (размерность другая).
- **Хранилище — SQLite через Flyway**: `V1__init.sql` создаёт `chunk`
  (PK — пара `(id, strategy)`, иначе fixed/structure с одинаковыми
  `source#ord` затирали бы друг друга) и `document` (выбранная стратегия +
  статус индексации). Вектора — BLOB float32 LE (~1.5 КБ/чанк), поиск —
  точный косинус с кучей top-K; `strategy="both"` ищет объединением обеих
  стратегий, поэтому документы с разным чанкингом находятся одним запросом.
  FAISS не взят сознательно: нативный C++ без Kotlin API, а при тысячах
  чанков brute-force даёт миллисекунды и тот же результат, что FlatIP.
- **RAG на чат, а не глобально**: флаг `ragEnabled` у чата + фильтр активных
  документов в `search(onlySources)` — выключенный документ физически не попадает
  в контекст.
- **Масштабирование**: новый чанкер = класс + имя; Ollama/OpenAI-эмбеддинги =
  ещё одна реализация `EmbeddingProvider`; HNSW — внутрь
  `VectorStore.search`, порог/реранк уже живут в `Rerank.kt` между search и промптом, интерфейсы не меняются.

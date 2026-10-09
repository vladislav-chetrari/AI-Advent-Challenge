# Week 5 — on-device LLM + RAG (LiteRT-LM)

День 27: приложение отправляет запросы во **встроенную** LLM, получает и отображает ответы, работает **без облака**.

Стек: **Compose Multiplatform (Kotlin) + Kotlin Toolchain 0.12.0 (Amper)**,
Clean Architecture + MVVM + SSOT + SOLID/KISS. Рантайм — **LiteRT-LM
(Google AI Edge, Maven: `litertlm-android` / `litertlm-jvm`)**, генерация —
**Qwen2.5-1.5B-Instruct-Q8 (~1.5 ГБ)**, эмбеддинги для RAG —
**EmbeddingGemma-270M (~165 МБ)**. Облачный путь — **DeepSeek через Koog**.

## Структура (корень проекта — `Week 5/`)

```
project.yaml            # shared, androidApp, desktopApp
shared/                 # общий код: domain / data / llama / presentation / ui
  src/                  # common: ChatRepository(SSOT), PromptBuilder, ModelCatalog, VM, ChatScreen
  src@android/          # actual: LiteRT-LM Engine/EmbeddingEngine, Downloader на HttpURLConnection
  src@jvm/              # actual: LiteRT-LM (генерация), Fake-эмбеддинги (десктоп-превью RAG)
androidApp/             # product android/app, MainActivity
desktopApp/             # product jvm/app, превью на десктопе (настоящая генерация!)
```
Каталог моделей зашит (`ModelCatalog`): дефолт `qwen25-1.5b-q8`, лёгкая
`qwen3-0.6b-int4`. Эмбеддинги — `embedgemma-270m`. Всё тянется
из `litert-community` на HuggingFace при первом запуске
в `filesDir/models/` (или `./models/` на десктопе), дальше всё офлайн.
`INTERNET` нужен только на докачку.

## Запуск

```sh
cd "Week 5"
./kotlin build              # проверка всех модулей
./kotlin run -m desktopApp  # десктоп-превью того же чата (Fake-движок)
./kotlin run -m androidApp   # установка на девайс/эмулятор
```

Модель докачивается при первом запуске в `filesDir/models/` (дефолт ~1.5 ГБ + эмбеддинги ~165 МБ, прогресс в %),
дальше всё офлайн. `INTERNET` нужен только на докачку.

## Нативный рантайм

Ничего собирать не надо: LiteRT-LM едет Maven-зависимостями
(`litertlm-android` / `litertlm-jvm`), нативные библиотеки внутри AAR/jar.

## Проверка для видео

1. Включить airplane-mode.
2. Открыть приложение, выбрать `Qwen2.5 1.5B Q8`.
3. Три запроса как в Task 1: простой / средний / сложный.
4. Показать бейдж `Offline • LiteRT • qwen25-1.5b-q8`.

## Архитектура

*   `presentation/ChatViewModel` — MVVM, только `UiState + Intent`.
*   `data/ChatRepositoryImpl` — SSOT (`StateFlow<List<ChatMessage>>`).
*   `llama/LlamaBridge` — DIP-интерфейс, `expect/actual` (JNI vs Fake).
*   `di/AppContainer` — ручной DI без Hilt (KISS, один экран).

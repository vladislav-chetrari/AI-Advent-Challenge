# Week 5 Task 2 — on-device LLM (llama.cpp + Qwen3-1.7B-Q4)

День 27: приложение отправляет запросы во **встроенную** LLM, получает и отображает ответы, работает **без облака**.

Стек: **Compose Multiplatform (Kotlin) + Kotlin Toolchain 0.12.0 (Amper)**,
Clean Architecture + MVVM + SSOT + SOLID/KISS. Движок — **llama.cpp через JNI**
(`libllama.so` в `androidApp/jniLibs/`), модель — **Qwen3-1.7B-Instruct-Q4_K_M (~1.3 ГБ)**.

## Структура (корень проекта — `Week 5/`)

```
project.yaml            # shared, androidApp, desktopApp
shared/                 # общий код: domain / data / llama / presentation / ui
  src/                  # common: ChatRepository(SSOT), PromptBuilder, ModelCatalog, VM, ChatScreen
  src@android/          # actual: JniLlamaBridge (libllama.so), Downloader на HttpURLConnection
  src@jvm/              # actual: FakeLlamaBridge (десктоп-превью без натива)
androidApp/             # product android/app, MainActivity, jniLibs/arm64-v8a/libllama.so
  cpp/bridge.cpp        # JNI-мост (V1 stub + TODO под настоящий llama.cpp)
desktopApp/             # product jvm/app, превью того же UI на десктопе
tools/build-llama.sh    # сборка libllama.so через NDK 21.4 + CMake 3.22.1
```

Каталог моделей зашит (`ModelCatalog`): дефолт `qwen3-1.7b-q4`, запасные
`qwen3-0.6b-q4`, `smollm3-3b-q4`. Произвольных URL нет осознанно (KISS + OOM на телефоне).

## Запуск

```sh
cd "Week 5"
./kotlin build              # проверка всех модулей
./kotlin run -m desktopApp  # десктоп-превью того же чата (Fake-движок)
./kotlin run -m androidApp   # установка на девайс/эмулятор
```

Модель докачивается при первом запуске в `filesDir/models/` (~1.3 ГБ дефолт, прогресс в %),
дальше всё офлайн. `INTERNET` нужен только на докачку.

## Нативная сборка llama.cpp

```sh
git submodule add https://github.com/ggerganov/llama.cpp.git androidApp/cpp/llama.cpp
sh tools/build-llama.sh arm64-v8a   # -> androidApp/jniLibs/arm64-v8a/libllama.so
```

Без `.so` приложение собирается и показывает понятную ошибку
«libllama.so не найден…», а не падает. С заглушкой `bridge.cpp`
`nativeInit` возвращает 0 — тоже честная ошибка «не открылось».

## Проверка для видео

1. Включить airplane-mode.
2. Открыть приложение, выбрать `Qwen3 1.7B Q4`.
3. Три запроса как в Task 1: простой / средний / сложный.
4. Показать бейдж `Offline • llama.cpp • qwen3-1.7b-q4`.

## Архитектура

*   `presentation/ChatViewModel` — MVVM, только `UiState + Intent`.
*   `data/ChatRepositoryImpl` — SSOT (`StateFlow<List<ChatMessage>>`).
*   `llama/LlamaBridge` — DIP-интерфейс, `expect/actual` (JNI vs Fake).
*   `di/AppContainer` — ручной DI без Hilt (KISS, один экран).

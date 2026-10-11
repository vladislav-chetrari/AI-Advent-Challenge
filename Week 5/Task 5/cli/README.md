# CLI-клиент Task 5 (`cli/`)

Маленький консольный клиент к Task 5 серверу: чат + загрузка документов.
HTTP-обёртка — `core/src/api/Task5Client.kt`, команды — `cli/src/Main.kt`.

## Команды

```sh
task5 health [--base-url ...] [--key ...]
task5 models [--base-url ...] [--key ...]
task5 ask <текст...> [--model qwen2.5:7b] [--temperature 0.7] [--max-tokens 512] [--system "..."]
task5 chat [--model ...] [--temperature 0.7] [--max-tokens 512] [--system "..."]
task5 docs add --type text|wiki [--title "..."] [--file path | <текст...>] [--input ...]
task5 docs list
task5 docs delete <id>
task5 embed <текст...> [--model embeddinggemma:300m]
```

## Дефолты

- `--base-url` — из `TASK5_BASE_URL`, иначе `http://localhost:8080`.
- `--key` — из `TASK5_API_KEY` (пусто = сервер без auth).
- `--model` — пусто = дефолт сервера (`TASK5_LLM` / `TASK5_EMBED`).

## Примеры

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

## REPL-чат (`task5 chat`)

История диалога хранится в памяти и шлётся целиком каждый раз.
Команды: `/clear` (очистить историю), `/temp <n>` (сменить temperature),
`/system <текст>` (сменить system-промпт, история очищается), `/exit`.

Кириллица в Windows-консоли — как в Task 1: `chcp 65001` + UTF-8 потоки
(делает сам клиент при старте).

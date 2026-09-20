# UI (`desktop/`)

Окно 1200×800, две зоны (`desktop/src/main.kt:98`, `Root`):

- **Слева (340px, тёмная):** дерево + кнопка `+` + профиль внизу.
  - `general` (корень) → общие чаты (`☰`-строки) + `GENERAL` `.md` (`M↓`/`M✕` `title.md` + клик по иконке `M` — `toggleDocActive`) (`desktop/src/main.kt:116`).
  - `▾ 📁 <проект>` → `PROJECT` `.md` + чаты проекта → `▾ 📋 <задача>` → `TASK` `.md` + чаты задачи (`desktop/src/main.kt:125`).
  - Клик по чату → справа чат; клик по `.md` → справа viewer (`Selection.ChatSel` / `Selection.DocSel`, `desktop/src/ui/AppViewModel.kt:18`).
  - Чекбокс-иконка включает/выключает инжект дока (`toggleDocActive`, `desktop/src/main.kt:228`).
  - Низ древа: круглая кнопка профиля (`desktop/src/main.kt:168`). `?` = Аноним (без персонализации), иначе первая буква имени. Справа подпись `Аноним / без персонализации` или `<имя> / профиль инжектится`. Клик → `ProfileDialogView`.
  - Свернутость: `expandedProjects` / `expandedTasks` (`desktop/src/ui/AppViewModel.kt:75`), авто-раскрытие предков при `select` (`desktop/src/ui/AppViewModel.kt:107`), toggle по клику на header (`desktop/src/main.kt:213`).
- **Справа:** чат **или** viewer памяти.

## Чат (`ChatPane`, `desktop/src/main.kt:277`)

- Шапка: хлебные крошки по уровню — `GENERAL`: `имя`, `PROJECT`: `проект / имя`, `TASK`: `проект / задача / имя` (`desktop/src/main.kt:282`) + `▼/▲ system prompt` (collapse/extend собранного system prompt, `AppViewModel.systemPrompt`, обновляется при каждом `ask` и смене выбора/`profile`/`toggleDoc`) + `🗑 очистить` (только историю, `AppViewModel.clearChat`).
  Под промптом: `~N tok (оценка chars/4) · last prompt_tokens=M` (`desktop/src/main.kt:314`). Развёрнутый промпт — `BoxWithConstraints` до пол-окна с вертикальным скроллом (`desktop/src/main.kt:298`).
- Лента: `user` (голубой `0xFFE3F2FD`) / `assistant` (серый `0xFFF1F1F1`), текст в `SelectionContainer` (`desktop/src/main.kt:379`). Сообщения читаются прямо из `UiState.messages` (без `remember`), поэтому лента перерисовывается каждым `refresh`; автоскролл к последнему сообщению (`LaunchedEffect` на `messages.size`, `desktop/src/main.kt:321`).
  Пока `busy` — в конце ленты пузырь `Печатает…`, поле ввода заблокировано (`desktop/src/main.kt:328`).
  ПКМ по реплике → контекстное меню строго в позиции курсора: `Копировать` / `Сохранить в память` (`startSave` с `chat.scope/parentId`, `desktop/src/main.kt:363`). ПКМ перехватывается в `PointerEventPass.Initial` и `consume()`, чтобы не всплывало дефолтное меню `Copy` `SelectionContainer` (`desktop/src/main.kt:387`).
  Меню — `Popup` с кастомным `PopupPositionProvider`, возвращающим window-координаты клика (`desktop/src/main.kt:415`).
  `user`-реплики — `Text`, `assistant` — `Markdown` с `chatMarkdownTypography()` (`desktop/src/main.kt:405`, `desktop/src/ui/ChatMarkdown.kt:16`).
- Ввод: поле + `➤`, `Enter`/`NumpadEnter` — отправка, `Shift+Enter` — новая строка, `busy`-блок, ошибки — красной строкой под панелью (`desktop/src/main.kt:339`, `desktop/src/main.kt:203`). `AppViewModel.send` делает optimistic echo: `appendUserMessage` sync + `refresh` до `completeAsk` (`desktop/src/ui/AppViewModel.kt:154`).

## Viewer памяти (`MemoryPane`, `desktop/src/main.kt:440`)

Title, `scope parentId · active/выключен`, кнопки `включить/выключить` (`toggleDoc`), `удалить` (`deleteDoc`), текст `.md` как есть (`Markdown` той же компактной типографикой, `desktop/src/ui/ChatMarkdown.kt:16`, фон `0xFFF9F9F9`).

## Диалог создания (`CreateDialog`, кнопка `+`, `desktop/src/main.kt:462`)

Тип (`CreateKind`, `desktop/src/ui/AppViewModel.kt:23`): `Общий чат` (имя) / `Проект` (имя; только папка) / `Чат в проекте` (имя + проект) / `Задача` (имя + проект; только папка) / `Чат в задаче` (имя + задача).
Чипы `FilterChip` — клик сразу переключает `setCreateKind` (сбрасывает `parent`), родителя — второй ряд чипов (`desktop/src/main.kt:477`).
`Enter` — создать, если `canConfirm` (`name.isNotBlank() && parent != null` если нужен). После создания — автовыбор нового чата и авто-раскрытие предков (`desktop/src/ui/AppViewModel.kt:195`).

## Диалог сохранения (`SaveDialogView`, `desktop/src/main.kt:536`)

Сырец (первые 300 симв) → варианты дистилляции (клик подставляет в поле, `pickCandidate`) → редактируемое поле факта → scope (`GENERAL/PROJECT/TASK`, дефолт = scope чата) → родитель (проект/задача, если не `GENERAL`) → существующий `.md` **или** ☑ новый документ + title. `Сохранить` активна только при заполненных факте, родителе и доке (`enabled` check, `desktop/src/main.kt:619`). `busy` — `Дистиллирую...`.

## Диалог профиля (`ProfileDialogView`, `desktop/src/main.kt:631`)

Круглая кнопка внизу дерева → `AppViewModel.openProfile` (`desktop/src/ui/AppViewModel.kt:325`).

- Верх: выбор пользователя чипами — `Аноним` + все `profiles` (`desktop/src/main.kt:640`). Клик сразу вызывает `setProfileSelected(id)` (`desktop/src/ui/AppViewModel.kt:343`): `service.setActiveProfile(id)` + подставляет поля выбранного профиля + `refresh()` → следующий system prompt уже с новым профилем (или без него для Анонима). Под чипами подсказка: `Аноним: в system prompt ничего не добавляется` (`desktop/src/main.kt:656`).
- Поля: `Имя` (singleLine, ≤60), `Стиль`, `Формат`, `Ограничения` (≤500, `desktop/src/main.kt:661`).
- Кнопки: `Создать` (enabled `name.isNotBlank()`, `createProfile`, `desktop/src/ui/AppViewModel.kt:365`), `Сохранить` (enabled `selectedId != null`, `updateProfile`), `Удалить` (enabled `selectedId != null`, `deleteProfile` + сброс диалога в пустой `ProfileDialog`, `desktop/src/ui/AppViewModel.kt:380`).
- `Готово` — `closeProfile` (`desktop/src/ui/AppViewModel.kt:341`).

Типографика markdown (чат и viewer): `chatMarkdownTypography()` (`desktop/src/ui/ChatMarkdown.kt:16`) — база `14sp` (дефолт библиотеки `~57sp` для `h1`), шкала `h1 20 → h2 18 → h3 16 → h4 15 → h5 14 → h6 13` Bold, `code 13sp monospace`.

## Сценарии демо

### День 11 — модель памяти (`Task 1 demo.mp4`)

1. Создать проект + задачу + чат; спросить без памяти (доки пустые) — ответ без контекста.
2. Ответить что-то про стек/цель → `ПКМ → Сохранить в память` → проверить текст в viewer (`.md`).
3. Открыть `▼ system prompt` — факт виден в `[Память: ...]`; задать вопрос повторно — ответ учитывает память. Выключить док чекбоксом `M↓/M✕` — ответ «забывает» (следующий промпт без блока).
4. Показать: история режется окном `N=12` (длинный диалог), факты переживают обрезку.

### День 12 — персонализация (`Task 2 demo.mp4`)

1. Низ слева — кружок `?` (Аноним). Задать вопрос (напр. «объясни...») — базовый стиль (`BASE`).
2. Открыть профиль → создать `Кратко` (`style=кратко, по делу`) и `Подробно` (`format=списки, примеры кода`) → переключение чипами `Аноним → Кратко → Подробно` без смены чата/памяти.
3. Один и тот же вопрос при разных профилях — видимая разница ответов. Показать `▼ system prompt`: блок `[Профиль: ...]` появляется/меняется при переключении.
4. Выключить профиль (Аноним) — блок исчезает, ответы возвращаются к базовым. Сохранение/удаление профиля через диалог.

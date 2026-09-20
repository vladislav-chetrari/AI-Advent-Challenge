# UI (`desktop/`)

Окно 1200×800, две зоны (`Root`):

- **Слева (340px, тёмная):** дерево + кнопка `+` + профиль внизу.
  - `general` (корень) → общие чаты (`☰`-строки) + `GENERAL` `.md` (`M↓`/`M✕` `title.md` + клик по иконке `M` — `toggleDocActive`).
  - `▾ 📁 <проект>` → `PROJECT` `.md` + `🛡`-инварианты проекта + чаты проекта → `▾ 📋 <задача>` → бейдж этапа (`PLN`/`EXE`/`VAL`/`DONE`, цвет по stage, `⏸` при паузе, `desktop/src/main.kt:171`) → `TASK` `.md` + `🛡`-инварианты задачи + чаты задачи.
  - Клик по чату → справа чат; клик по `.md` → справа viewer памяти (`MemoryPane`); клик по `🛡` → редактор инвариантов (`InvariantPane`) (`Selection.ChatSel` / `Selection.DocSel` / `Selection.InvariantSel`, `desktop/src/ui/AppViewModel.kt:27`).
  - Чекбокс-иконка `M` включает/выключает инжект дока (`toggleDoc`), иконка `🛡`/`🛡✕` — инжект инварианта (`toggleInvariant`, `InvariantRow`, `desktop/src/main.kt:300`).
  - Низ древа: круглая кнопка профиля. `?` = Аноним (без персонализации), иначе первая буква имени. Справа подпись `Аноним / без персонализации` или `<имя> / профиль инжектится`. Клик → `ProfileDialogView`.
  - Свернутость: `expandedProjects` / `expandedTasks` (`desktop/src/ui/AppViewModel.kt:93`), авто-раскрытие предков при `select` (включая `InvariantSel`, `desktop/src/ui/AppViewModel.kt:130`), toggle по клику на header.
- **Справа:** чат **или** viewer памяти **или** редактор инвариантов. Над TASK-чатом и TASK-viewer'ами — `TaskStateBar`.

## TaskStateBar (`desktop/src/main.kt:657`)

Шапка состояния задачи (`taskId` из чата/дока, `titleOverride` — полный путь `проект / задача / чат` для чата, короткий `▸ имя` для viewer'ов):

- Строка 1: заголовок + кнопки управления (слева от чипов этапов) + 4 некликабельных чипа `planning/execution/validation/done` (текущий подсвечен цветом stage, при паузе — оранжевым) + `⏸` при `PAUSED`.
  - `PLANNING`: `→ Execution` (финализация плана + старт авто) / `↺ Replan`.
  - `EXECUTION`: `▶ Авто` / `⏸ Пауза` (`toggleAuto`, `desktop/src/ui/AppViewModel.kt:748`) / `↺ Replan`.
  - `VALIDATION`: `✓ Done` (подтверждение юзера) / `↻ Retry` / `↺ Replan` (`desktop/src/main.kt:706`).
  - `DONE`: кнопок нет, чипы только индикатор; новые чаты/инварианты запрещены (`AppViewModel.commitCreate`, `desktop/src/ui/AppViewModel.kt:262`).
- Строка 2: `Шаг i/N: title` (+ `✓` у выполненного), ниже `Ожидается: nextAction`.
- Подсказки режима: в EXECUTION-ACTIVE — `🤖 EXECUTION автоматический: код выдаётся шаг за шагом через API`; в EXECUTION-PAUSED — `⏸ Остановлено на невыполненном шаге i/N: title` (`desktop/src/main.kt:755`).
- В VALIDATION — результат автопроверки инвариантов (`ts.lastValidation`): `✅ Инварианты соблюдены — ждём твоего подтверждения` + `↻ Проверить`; либо `❌ Нарушены инварианты (N)` с карточками `• rule / ↳ "evidence" / ✎ fix` + `↻ Retry EXECUTION` / `Проверить снова` (`desktop/src/main.kt:764`).
- `actions`-слот первой строки: кнопки шапки чата (system prompt / invariants / clear) рисуются внутри бара для TASK-чатов.
- День 15: отказы гардов (`Denied(reason)`) показываются красной строкой статуса (`lastDeniedReason`); `VALIDATION → DONE` идёт через `completeTask` (при `FAILURE` — отказ, нужен Retry); под баром — строка `Переходы: A→B → ...`; в DONE — `✅ Задача завершена`, отправка сообщений блокируется.

## Чат (`ChatPane`, `desktop/src/main.kt:403`)

- Шапка: для TASK-чатов — `TaskStateBar` с полным заголовком + кнопками `▼/▲ system prompt`, `🛡 invariants` (collapse/extend `InvariantsPanel`, `invariantsExpanded`, `desktop/src/ui/AppViewModel.kt:180`), `🗑 очистить` (только историю, `AppViewModel.clearChat`). Для GENERAL/PROJECT — обычные хлебные крошки (`имя` / `проект / имя`) + те же кнопки без бара.
  Под промптом: `~N tok (оценка chars/4) · last prompt_tokens=M`. Развёрнутый промпт — `BoxWithConstraints` до пол-окна с вертикальным скроллом.
- `InvariantsPanel` (`desktop/src/main.kt:348`, collapse по кнопке `🛡`): действующие в этом чате инварианты по той же логике наследования что и в промпте (`PROJECT → проект`, `TASK → проект + задача`); GENERAL — заглушка `Инвариантов для этого чата нет`. Строка: `🛡/🛡✕` (toggle), `🛡 title.md` (переход в `InvariantSel`), `active/выкл`, первые 300 симв текста.
- Лента: `user` (голубой `0xFFE3F2FD`) / `assistant` (серый `0xFFF1F1F1`), текст в `SelectionContainer`. Сообщения читаются прямо из `UiState.messages` (без `remember`), поэтому лента перерисовывается каждым `refresh`; автоскролл к последнему сообщению (`LaunchedEffect` на `messages.size`).
  Пока `busy` — в конце ленты пузырь `Печатает…`, поле ввода заблокировано.
  ПКМ по реплике → контекстное меню строго в позиции курсора: `Копировать` / `Сохранить в память` (`startSave` с `chat.scope/parentId`). ПКМ перехватывается в `PointerEventPass.Initial` и `consume()`, чтобы не всплывало дефолтное меню `Copy` `SelectionContainer`.
  Меню — `Popup` с кастомным `PopupPositionProvider`, возвращающим window-координаты клика.
  `user`-реплики — `Text`, `assistant` — `Markdown` с `chatMarkdownTypography()`.
- Ввод: поле + `➤`, `Enter`/`NumpadEnter` — отправка, `Shift+Enter` — новая строка, `busy`-блок, ошибки — красной строкой под панелью. `AppViewModel.send` делает optimistic echo: `appendUserMessage` sync + `refresh` до `completeAsk` (`desktop/src/ui/AppViewModel.kt:190`).

## Viewer памяти (`MemoryPane`, `desktop/src/main.kt:629`)

Title, `scope parentId · active/выключен`, кнопки `включить/выключить` (`toggleDoc`), `удалить` (`deleteDoc`), текст `.md` как есть (`Markdown` той же компактной типографикой, `desktop/src/ui/ChatMarkdown.kt:16`, фон `0xFFF9F9F9`). Для TASK-доков сверху — `TaskStateBar` (короткий заголовок).

## Редактор инвариантов (`InvariantPane`, `desktop/src/main.kt:597`)

Заголовок `🛡 title.md` + `scope parentId · active/выкл`, кнопки `включить/выключить` (`toggleInvariant`), `удалить` (`deleteInvariant`). Текст — редактируемое многострочное поле (`TextField`, моноширинный, `minLines=12`) с автосейвом: мгновенно в `UiState.invariantContents`, на диск (`invariants/<id>.md`) с debounce 600мс (`onInvariantEdit`, `desktop/src/ui/AppViewModel.kt:330`). Дистилляции нет. Под полем — счётчик `N/8000 симв`. Для TASK-инвариантов сверху — `TaskStateBar`. Пустой док показывает плейсхолдер `Опиши правила...` (архитектура, стек, запреты).

## Диалог создания (`CreateDialog`, кнопка `+`, `desktop/src/main.kt:819`)

Тип (`CreateKind`, `desktop/src/ui/AppViewModel.kt:34`): `Общий чат` (имя) / `Проект` (имя; только папка) / `Чат в проекте` (имя + проект) / `Задача` (имя + проект; только папка + сразу `TaskState(PLANNING)`) / `Чат в задаче` (имя + задача) / `Инварианты` (имя + проект/задача, scope выводится по типу родителя).
Чипы `FilterChip` — клик сразу переключает `setCreateKind` (сбрасывает `parent`), родителя — второй ряд чипов.
`Enter` — создать, если `canConfirm` (`name.isNotBlank() && parent != null` если нужен). После создания — автовыбор нового чата/дока и авто-раскрытие предков (`desktop/src/ui/AppViewModel.kt:231`). В DONE-задаче новые чаты/инварианты запрещены со статусом `Задача завершена (DONE) — ...`.

## Диалог сохранения (`SaveDialogView`, `desktop/src/main.kt:896`)

Сырец (первые 300 симв) → варианты дистилляции (клик подставляет в поле, `pickCandidate`) → редактируемое поле факта → scope (`GENERAL/PROJECT/TASK`, дефолт = scope чата) → родитель (проект/задача, если не `GENERAL`) → существующий `.md` **или** ☑ новый документ + title. `Сохранить` активна только при заполненных факте, родителе и доке. `busy` — `Дистиллирую...`.

## Диалог профиля (`ProfileDialogView`, `desktop/src/main.kt:992`)

Круглая кнопка внизу дерева → `AppViewModel.openProfile` (`desktop/src/ui/AppViewModel.kt:414`).

- Верх: выбор пользователя чипами — `Аноним` + все `profiles`. Клик сразу вызывает `setProfileSelected(id)` (`desktop/src/ui/AppViewModel.kt:432`): `service.setActiveProfile(id)` + подставляет поля выбранного профиля + `refresh()` → следующий system prompt уже с новым профилем (или без него для Анонима). Под чипами подсказка: `Аноним: в system prompt ничего не добавляется`.
- Поля: `Имя` (singleLine, ≤60), `Стиль`, `Формат`, `Ограничения` (≤500).
- Кнопки: `Создать` (enabled `name.isNotBlank()`, `createProfile`, `desktop/src/ui/AppViewModel.kt:454`), `Сохранить` (enabled `selectedId != null`, `updateProfile`), `Удалить` (enabled `selectedId != null`, `deleteProfile` + сброс диалога в пустой `ProfileDialog`, `desktop/src/ui/AppViewModel.kt:469`).
- `Готово` — `closeProfile`.

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

### День 13 — состояние задачи (`Task 3 demo.mp4`)

1. Создать проект + задачу (сразу `PLANNING`, бейдж `PLN`) + TASK-чат; обсудить цель в чате — сверху `TaskStateBar` с шагом и `Ожидается`.
2. Нажать `→ Execution`: весь разговор дистиллируется в чистый план (док `план`, scope=TASK), этап → `EXECUTION` (бейдж `EXE`), стартует авто-цикл — `[авто]`-запросы шаг за шагом, код в ленте.
3. Нажать `⏸ Пауза` mid-EXECUTION: цикл встаёт на невыполненном шаге (`⏸ Остановлено на ...`), `▶ Продолжить`/`▶ Авто` продолжает с того же шага. Показать `▼ system prompt`: блок `[Задача]` с текущим шагом/`nextAction`/историей.
4. После последнего шага — автопереход в `VALIDATION` (бейдж `VAL`), `✓ Done` → `DONE` (бейдж `DONE`, новые чаты запрещены). `↺ Replan` в любом этапе — чистка реплик + возврат в `PLANNING`.

### День 14 — инварианты (демо Task 4)

1. `+ → Инварианты` (проект или задача) → `🛡`-док в дереве → открыть `InvariantPane`, вписать правила (напр. `Стек: только X`, `Запрещено: ...`) — автосейв, без дистилляции.
2. В TASK-чате раскрыть `🛡 invariants` — инварианты проекта+задачи видны; показать `▼ system prompt` — блок `[Инварианты — НЕ НАРУШАТЬ]`. Тумблер `🛡/🛡✕` выключает инжект без удаления текста.
3. Прогнать задачу до `VALIDATION` (авто-цикл EXECUTION) — автопроверка `validateAgainstInvariants` показывает `✅ соблюдены` (ждём `✓ Done`) либо `❌ Нарушены (N)` с карточками `rule/evidence/fix`.
4. При failure: `↻ Retry EXECUTION` — чистка только `[авто]`-реплик (планировочный диалог жив), откат в EXECUTION, рестарт авто. Конфликтный запрос (попросить нарушить стек) — ассистент отказывается со ссылкой на пункт инварианта.

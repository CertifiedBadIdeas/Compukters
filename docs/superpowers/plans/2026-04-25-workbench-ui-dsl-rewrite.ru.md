# Переписывание Workbench IDE UI на внутренний DSL (план реализации)

> **Статус (2026-04-26):** все задачи выполнены, кроме ручного клиентского теста (Задача 2.4). DSL-расширения (Эпик 1) и переписывание `WorkbenchEditorScreen` на DSL (Эпик 2) закоммичены, чистка (Эпик 3) пройдена. Ожидается прогон `./gradlew :v1_21_1-neoforge:runClient` пользователем.
>
> **Для агентов:** суб-навык `superpowers:subagent-driven-development` или `superpowers:executing-plans`. Задачи отмечаются чекбоксами.

**Предпосылка:** В [core/ui/foundation/**](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/foundation) и [core/ui/program/**](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/program) уже живёт Layer-2 DSL (UiElement/Modifier/ValueExpression, ScreenProgramCompiler, ScreenRuntimeExecutor, GuiGraphicsRenderBackend). В production он пока используется только для `TerminalItemScreen` через [DslContainerScreen.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/DslContainerScreen.kt). Весь [WorkbenchEditorScreen.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt) по-прежнему рисуется пиксельно через `graphics.fill()`/`drawString()`.

**Решения пользователя (2026-04-25):**

- DSL: **Layer 2** (UiElement/ScreenProgram).
- Стратегия: **big-bang**, старый `WorkbenchEditorScreen` выкидываем.
- Scope: **весь экран целиком** — тулбар, файловый сайдбар, редактор, popup’ы автокомплита и импортов, статус-бар, встроенная терминал-панель.
- Примитивы: **добавляем в core DSL как первоклассные UiElement** (`TextField` / `CodeEditor`, `ScrollArea`, `Focusable`, тикающие `Value`).
- UX: свободно редизайним.

---

## Цели

1. В `core` появляется набор примитивов, достаточных для полноценного IDE: скролл-контейнер, фокус-менеджер с Tab-навигацией, первоклассный редактор кода, тикающее значение для анимаций, drag/release события.
2. Новый `WorkbenchEditorScreen` целиком декларативный: его тело — одна функция `buildWorkbenchUi(store: WorkbenchStore): UiElement`. Всё рисование, layout и routing событий — через DSL-рантайм; `GuiGraphics` напрямую не трогаем.
3. Семантика поведения сохраняется: все клавиатурные и мышиные действия из раздела A.2 доступны и работают.
4. Старый экран (~600 строк пиксельной логики) удалён; все вспомогательные helper’ы-рендереры (`renderToolbar`, `renderWorkspaceList`, `renderEditor`, `renderHighlightedLine`, popup-рендереры, позиционирование кнопок) снесены.

**Не входит в этот план** (отдельные плашки):

- Перенос модели редактора из `WorkbenchStore` во что-то реактивное — стор остаётся императивным и мутабельным, DSL только читает/диспатчит.
- Миграция Workbench на байтовый поток (задача 3.4 из эпиков 2-3-4) — независима.
- Clipboard API (Ctrl+X/C/V) — отложено, упомянем в TODO.

---

## Стек и конвенции

- Файлы DSL: Kotlin в `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/**`, тесты в `modules/core/src/test/kotlin/**/ui/**`.
- Интеграция: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/ui/program/GuiGraphicsRenderBackend.kt` расширяется симметрично новым `RenderOp`.
- Не ломаем существующие UiElement’ы — только добавляем новые подклассы `sealed interface UiElement` и расширения `Modifier`.
- Каждая задача = отдельный коммит с тестами. Где тесты возможны без Minecraft (ScreenProgramCompiler, UiLayoutResolver, executor) — покрываем в `:core`; экранная интеграция проверяется в `:v1_21_1-neoforge:runClient` вручную.

---

## Эпик 1 — Расширение DSL

Добавляем в core всё, чего не хватает по разделу C отчёта исследования, в порядке от инфраструктуры к компонентам.

### Задача 1.1 — Мульти-фокус + Tab/Shift+Tab

Сейчас `ScreenProgram.focusRegion` — одиночный и хранит только одного обработчика. Для IDE нужен минимум **три независимых фокуса**: редактор, встроенный терминал, поле поиска импорта (и потенциально сайдбар).

- [x] Переименовать `FocusRegion`/`FocusHandler` → `FocusNode { id: String, bounds, tabOrder: Int, handler }`.
- [x] `ScreenProgram.focusRegion: FocusRegion?` → `focusNodes: List<FocusNode>` + `var focusedNodeId: String?` в executor.
- [x] Executor: `mouseClicked` ищет `FocusNode` по z-top hit — тот и становится `focusedNodeId`; `keyPressed`/`charTyped` уходят в хендлер активного узла.
- [x] Поддержать Tab/Shift+Tab: если активный узел не потребил Tab, executor переводит фокус на следующий/предыдущий узел по `tabOrder`.
- [x] Добавить `Modifier.focusable(id: String, tabOrder: Int = 0, handler: FocusHandler)` — заменяет все текущие `onKey`/`onCharTyped` на `TerminalSurface`.
- [x] `TerminalSurface` переводим с inline-хендлеров на этот модификатор.
- [x] Юнит-тесты: два фокус-узла, клик по первому → `keyPressed` идёт в него; Tab переводит фокус.

### Задача 1.2 — Скролл/клип контейнер

- [x] Новый `UiElement.ScrollArea(modifier, scrollX: Value<Int>, scrollY: Value<Int>, children)` — задаёт прямоугольник, внутри которого дети смещаются на `(-scrollX, -scrollY)` и клипятся к границам контейнера.
- [x] В `ScreenProgram` ввести `RenderOp.PushClip(x, y, w, h) / PopClip`, компилятор расставляет пары вокруг детей ScrollArea.
- [x] Backend: `GuiGraphicsRenderBackend` использует `graphics.enableScissor/disableScissor` (с учётом вложенности через стек).
- [x] `mouseScrolled` в executor: если мышь над ScrollArea, вызывается хендлер `onScroll(deltaY)` (опциональный параметр конструктора).
- [x] Hit-regions внутри ScrollArea корректно клипятся (клик вне видимой части не срабатывает).
- [x] Юнит-тесты компилятора: дочерние `FillRect` смещаются и клипятся.

### Задача 1.3 — Тикающее значение

- [x] В `ScreenRuntimeExecutor.render()` пробрасывать `tickCount: Int` (простой монотонный счётчик, инкремент перед каждым `render`).
- [x] Новый конструктор `Value.tick { tick -> T }` — выражение, которое получает текущий tick. Для blink это `Value.tick { it / 6 % 2 == 0 }`.
- [x] Тесты: между двумя `render` тик инкрементируется, `Value.tick` вычисляется с новым значением.

### Задача 1.4 — Drag & release события

- [x] `HitRegion` получает опциональные `onDragStart(x, y)`, `onDrag(x, y)`, `onRelease(x, y)`.
- [x] Executor API: `mouseDragged(x, y, button)`, `mouseReleased(x, y, button)`. Хостовой экран форвардит.
- [x] Модификатор: `Modifier.draggable(onStart, onDrag, onRelease)` + сохраним `clickable(onClick)` как sugar поверх drag.
- [x] Тесты executor’а на drag-sequence.

### Задача 1.5 — `CodeEditor` UiElement

Главный новый элемент. Он **декларативный**: получает снаружи `EditorViewModel` (интерфейс, реализуемый адаптером над `WorkbenchStore`), и сам занимается:

- рендером текста с подсветкой,
- прорисовкой курсора (через `Value.tick`),
- прорисовкой выделения (пока без клавиатурной логики — модельно доступно, Shift+Arrow в задаче 2.x),
- gutter’ом с номерами строк и маркерами диагностики,
- скроллом (внутри CodeEditor — своя ScrollArea),
- приёмом клавиатурного ввода (Arrow/Backspace/Delete/Enter/Tab/PageUp/PageDown/printable chars),
- приёмом клика — конвертацией (pixelX, pixelY) → (line, column) → `vm.moveCursorTo`.

Интерфейс:

```kotlin
interface EditorViewModel {
    val text: String
    val cursorLine: Int
    val cursorColumn: Int
    val scrollLine: Int
    val highlights: List<HighlightToken>
    val diagnostics: List<Diagnostic>
    val selection: SelectionRange?   // пока null

    fun onKeyPressed(key: Int, modifiers: Int, visibleLines: Int): Boolean
    fun onCharTyped(ch: Char, visibleLines: Int): Boolean
    fun onMouseClickAt(line: Int, column: Int)
    fun onScroll(deltaLines: Int)
}
```

- [x] Новый файл [core/ui/editor/CodeEditor.kt](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/editor/CodeEditor.kt) — `UiElement.CodeEditor(modifier, viewModel: Value<EditorViewModel>, fontWidth, fontHeight)`.
- [x] Compiler эмитит один `RenderOp.DrawCodeEditor(...)`; RenderBackend реализует его поверх `GuiGraphics` (токены + курсор + gutter); `CanvasScope` не хватит, делаем новый backend-метод.
- [x] Элемент регистрирует `FocusNode` и `HitRegion` автоматически.
- [x] Юнит-тесты: VM возвращает текст/хайлайты — компилятор строит ScreenProgram без падений; ключ-события роутятся в VM.
- [x] `FixedWidthFontRenderer` переиспользуем для моноширинного глифа.

### Задача 1.6 — Базовые списки

Для сайдбара/сompletion/import-picker достаточно `Column + for + Modifier.clickable + Modifier.background`, отдельный `ListView` сейчас не нужен. Если по ходу окажется неудобно — добавим задним числом.

- [x] Добавить sugar `Modifier.selectable(selected: Value<Boolean>, selectedBackground: Color)` — опционально, если рутина повторяется.

---

## Эпик 2 — Новый Workbench UI

### Задача 2.1 — Адаптер стора в `EditorViewModel`

- [x] Файл [workbench/screen/WorkbenchEditorViewModel.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorViewModel.kt) — тонкий адаптер, оборачивающий `WorkbenchStore`. Все методы делегируются в `store.keyPressed/charTyped/moveCursorTo/scrollEditor`.
- [x] Юнит-тесты: прокидывание ключей.

### Задача 2.2 — Сборка UI-дерева

- [x] Файл [workbench/screen/WorkbenchUiBuilder.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchUiBuilder.kt) — функция `buildWorkbenchUi(store: WorkbenchStore, viewportSize: () -> IntSize): UiElement`. Структура:

```
Column
  Row (height = toolbar)                      // Эпик 2 — тулбар
    Button(label="IDE / Console", …)
    Button("Save"),  Button("Pull"), Button("Push"), Button("Run"), Button("Imports")
    Spacer (weight = 1)
    Button("Terminal ⟷"), Button("Reboot")
  Row (weight = 1)                            // основная область
    Column (width = sidebar)                  // файловый браузер
      Text("browserPath")
      ScrollArea
        Column { entries.map { Row { icon + name + clickable } } }
    CodeEditor (weight = 1, viewModel = adapter)
    Column (if terminalVisible)               // правая панель терминала
      TerminalSurface(snapshot = menu.screenSnapshot)
  Row (height = statusBar)                    // статус
    Text("line N, col M")
    Spacer
    Text(hoverInfo ?: target.displayName)
Overlay (visible = completion.visible, anchor = computed) // completion popup
  Column { … }
Overlay (visible = importPicker.visible, anchor = center) // import picker
  Column { … }
```

- [x] Юнит-тест компилятора: `buildWorkbenchUi(fakeStore)` компилируется без падений и набирает ожидаемое число FocusNode/HitRegion.

### Задача 2.3 — Новый `WorkbenchEditorScreen`

- [x] Переписать [WorkbenchEditorScreen.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/screen/WorkbenchEditorScreen.kt) как тонкого наследника `DslContainerScreen`:

```kotlin
class WorkbenchEditorScreen(menu, inv, title) : DslContainerScreen(menu, inv, title) {
    private val store by lazy { WorkbenchStore(...) }
    override fun buildUi() = buildWorkbenchUi(store, ::sizeProvider)
    override fun init() { super.init(); store.bind(coroutineScope, menu.updateSource) }
    override fun onClose() { store.dispose(); super.onClose() }
}
```

- [x] Удалить все `renderToolbar/renderWorkspaceList/renderEditor/renderHighlightedLine/renderCompletionPopup/renderImportPicker/renderStatusBar/renderCursor` и связанные layout-helper’ы, если они больше нигде не используются.
- [x] Удалить `ToolbarButtonLayout` и весь [WorkbenchLayoutModel.kt](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/ui/workbench/WorkbenchLayoutModel.kt) (логика ушла в DSL-layout). Если `mouseToCursor` всё ещё нужен — оставляем как чистую функцию.
- [x] Проверить `WorkbenchTerminalRenderer` — встроенная терминал-панель теперь рисуется через `TerminalSurface` в DSL; если рендерер не используется с других мест — снести.

### Задача 2.4 — Ручной тест

- [ ] `./gradlew :v1_21_1-neoforge:runClient`.
  - [ ] Открыть Workbench, убедиться что сайдбар показывает файлы.
  - [ ] Открыть файл, редактировать — стрелки, Backspace, Enter, Tab.
  - [ ] Ctrl+Space → completion popup.
  - [ ] Ctrl+A → import picker.
  - [ ] Ctrl+S → save.
  - [ ] F4 — показать/скрыть терминал, в терминале печать читаема.
  - [ ] Клик по тулбар-кнопкам: Pull/Push/Run/Reboot.
  - [ ] Скролл колёсиком по редактору и по сайдбару.
  - [ ] Окно меньше стандартного → ScrollArea действительно клипит контент.

---

## Эпик 3 — Чистка

- [x] Удалить мёртвые импорты `GuiGraphics` из `common/workbench/screen/**`.
- [x] Удалить `textures/gui/workbench*.png`, если они больше не рисуются (и рефы в коде отсутствуют).
- [x] `docs/TODOs.md` — добавить задачи «Clipboard API для CodeEditor» и «Shift+Arrow/выделение» в отложенное.
- [x] `./gradlew build` — всё зелёное.

---

## Чекпоинты

- **CP-1** после Эпика 1: `:core:test` зелёный, новые primitives покрыты юнит-тестами.
- **CP-2** после Задачи 2.2: `buildWorkbenchUi` компилируется в ScreenProgram без ошибок; показать пользователю.
- **CP-3** после Задачи 2.3: `./gradlew build` зелёный; экран открывается и базовые взаимодействия работают.
- **CP-4** финал: весь пользовательский сценарий из 2.4 пройден, старый код удалён.

---

## Риски

- **Размер.** Эпик 1 — серьёзная прокачка DSL. Если становится видно, что `CodeEditor` как UiElement разрастается сверх разумного — падаем в запасной план: держим CodeEditor как `Canvas` внутри DSL-дерева, а поведение (ввод, клик) проксируем через `Modifier.focusable + draggable`. Это проще, но теряем «первоклассный UiElement» для редактора.
- **Производительность ScrollArea.** Компилятор должен уметь НЕ эмитить offscreen элементы. На первом заходе можно рисовать всё в clip-области — если будет тормозить, оптимизация отдельной задачей.
- **Фокус между Minecraft и DSL.** При Escape и кликах по инвентарным слотам Minecraft может выкинуть фокус — executor должен обрабатывать `restoreFocus`.

---

## Путь по задачам

Последовательность коммитов: `1.1 → 1.2 → 1.3 → 1.4 → 1.5 → (1.6 при необходимости) → 2.1 → 2.2 → 2.3 → 2.4 → 3`. Каждая задача — отдельный коммит с тестами.

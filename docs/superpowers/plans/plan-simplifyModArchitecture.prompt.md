# Plan: Simplify Mod Architecture — VM Self-Sufficiency, Declarative UI, Clean Data Flow

Переработка архитектуры мода по трём направлениям: (1) сделать VM полностью автономной — терминал становится просто потоковым вводом/выводом внутри VM, убирается отдельный `Terminal`-класс на стороне сервера и дублирование сущностей клиент/сервер; (2) построить декларативный UI-слой, отделив бизнес-логику от Minecraft-рендеринга; (3) упростить граф компонентов и задокументировать каждый модуль.

> **Примечание:** предыдущий план (`plan-simplifyVmArchitecture`) уже завершён — lambda spaghetti устранён, `VmContext` введён, `MenuSide` sealed interface добавлен, `ComputerManager` заменил `ComputerRegistry`. Этот план — следующий этап.

---

## Step 1. Встроить терминальный I/O в VM как `ScreenBuffer` вместо отдельного `Terminal`

### Что сейчас

`ServerComputer` владеет `NetworkedTerminal` — это 273-строчный мутабельный объект с `ArrayList<TextBuffer>`, палитрой, курсором, и `synchronized`-блоками. VM пишет в терминал через **трёхшаговый roundtrip**: VM-корутина → `HostCall.TerminalWrite` → очередь → `ServerComputer.serverTick()` → `HostCallDispatcher` → `TerminalHostWriter` мутирует `NetworkedTerminal` → `HostResult` летит обратно в корутину. Каждый `terminal.write("hello")` — это полный цикл suspend + resume через две очереди.

Кроме того, позиция курсора дублируется: `VmTerminalApi` хранит `cursorX/cursorY` для `TerminalLineReader`, а `NetworkedTerminal` хранит свой `cursorX/cursorY`.

### Что предлагается

VM владеет `ScreenBuffer` — плоский `CharArray` + `ByteArray` (цвета) + cursor position + dirty flag. Это **данные, а не объект с поведением**. VM-корутина пишет в буфер напрямую (без HostCall roundtrip). `ServerComputer.serverTick()` проверяет dirty flag, снимает snapshot, шлёт клиенту.

Удалить: `Terminal.kt`, `NetworkedTerminal.kt`, `TerminalHostWriter.kt`, `VmTerminalApi`, `HostCall.TerminalWrite/Clear/SetCursor`, соответствующие ветки в `HostCallDispatcher`.

### Обоснование: правильно ли это?

**Да, это правильное решение.** Причины:

1. **HostCall roundtrip для записи текста — overkill.** HostCall нужен когда VM обращается к ресурсу, принадлежащему серверному потоку (файловая система, redstone). Но текстовый буфер экрана — это не серверный ресурс, это данные самой VM. Нет причин маршрутизировать `write("hello")` через две concurrent-очереди.

2. **Thread safety проще, а не сложнее.** Сейчас `NetworkedTerminal` защищён `synchronized`, и к нему лезут из двух потоков (VM через HostCallDispatcher на main thread, и serverTick на main thread). С `ScreenBuffer` внутри VM — запись однопоточная (VM-корутина), чтение (snapshot) — atomic swap или copy-on-dirty, что проще.

3. **Задержка вывода.** Сейчас `write()` → HostCall → ждём следующий serverTick → dispatch → результат. Это минимум 1 tick (50ms) latency на каждый вызов terminal.write. С прямой записью — мгновенно, а sync с клиентом всё равно раз в тик.

**Риск в контексте Minecraft-моддинга:** Никакого. Серверный поток не мутирует терминальные данные — он только читает snapshot и шлёт пакет. Это стандартный паттерн producer (VM thread) → consumer (server tick thread).

**Важная деталь реализации:** `ScreenBuffer` должен жить в `BackgroundComputerVm` (или быть его полем), а не в `ServerComputer`. `ServerComputer.serverTick()` вызывает `vmHandle.readScreenSnapshot()` — получает иммутабельный snapshot или `null` если буфер не изменился.

---

## Step 2. Удалить мёртвый `ComputerRegistry`

### Что сейчас

`ComputerRegistry` (`context/ComputerRegistry.kt`) всё ещё существует как файл и объявлен в `ServerContext`, но **уже не используется ни одним `.kt` файлом** — все обращения переведены на `ComputerManager.get/add/remove` в рамках предыдущего плана.

### Что предлагается

Удалить файл `ComputerRegistry.kt`. Удалить `val registry = ComputerRegistry()` и `val registry get() = context().registry` из `ServerContext.kt`.

### Обоснование

Это просто уборка — код уже мёртв. Единственная вещь из `ComputerRegistry`, которая не имеет аналога в `ComputerManager` — это `sessionId`. Если `sessionId` нужен (для идентификации серверной сессии), перенести его в `ServerContext` или `ComputerManager`.

---

## Step 3. Декларативный UI-слой (`compukterkraft.mod.ui.dsl`)

### Что сейчас

`ComputerWorkbenchScreen` — 448 строк, в которых **намертво переплетены** три задачи:
- Layout-расчёты (`layout()`, `terminalLayout()`, `WorkbenchLayoutModel`)
- Input handling (`keyPressed`, `mouseClicked`, `charTyped` — 100+ строк `when`-блоков)
- Рендеринг (`renderBg`, `renderEditor`, `renderHighlightedLine`, `renderCursor`, `renderCompletionPopup`, `renderStatusBar`, `renderWorkspaceList` — ~150 строк прямых `graphics.fill`/`graphics.drawString`)

При добавлении нового UI-элемента нужно трогать 3–4 метода в одном файле.

### Что предлагается

Минимальный stateless UI-слой:

```kotlin
// Чистые data-классы — описание "что рисовать"
sealed interface UiNode
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int, val color: Int) : UiNode
data class Text(val x: Int, val y: Int, val text: String, val color: Int, val shadow: Boolean = false) : UiNode
data class CenteredText(val x: Int, val y: Int, val w: Int, val text: String, val color: Int) : UiNode
data class TerminalView(val x: Int, val y: Int, val screenBuffer: ScreenBufferSnapshot) : UiNode
data class Group(val children: List<UiNode>) : UiNode

// Рендерер — единственное место, знающее про GuiGraphics
object UiRenderer {
    fun render(graphics: GuiGraphics, font: Font, nodes: List<UiNode>) { ... }
}

// Экран превращает state → список нод
fun WorkbenchState.buildTerminalUi(layout: WorkbenchTerminalLayout): List<UiNode> = buildList {
    add(Rect(layout.panelBounds, PANEL_BACKGROUND))
    add(Text(layout.panelBounds.x + 12, layout.panelBounds.y + 8, "Terminal", TITLE_COLOR))
    add(TerminalView(layout.terminalBounds.x, layout.terminalBounds.y, terminalSnapshot))
    // ...
}
```

`ComputerWorkbenchScreen` сводится к:
```kotlin
override fun renderBg(graphics, ...) {
    val nodes = store.state.buildUi(layout())
    UiRenderer.render(graphics, font, nodes)
}
```

### Обоснование: правильно ли это?

**В целом правильно, но с оговорками:**

✅ **За:**
- Разделение «что рисовать» и «как рисовать» — правильный паттерн при любом UI
- Тестируемость: `buildTerminalUi()` — чистая функция, можно проверить без Minecraft
- При 448 строках Screen это уже реально улучшит читаемость
- Подход stateless (state → nodes) намного проще чем делать полноценный Compose

⚠️ **Не стоит делать:**
- Полноценный Compose-like фреймворк с `State<T>`, реактивностью, diff-алгоритмом — это месяцы работы ради двух экранов
- Column/Row/Padding layout engine — слишком много абстракции. Координаты считает `WorkbenchLayoutModel`, ноды просто принимают готовые координаты
- Собственную систему виджетов взамен стандартного `Screen.addWidget()` — для кнопок и слотов лучше использовать Minecraft'овские виджеты, а DSL только для кастомного рендеринга (терминал, редактор)

**Вывод:** Делать, но минимально. `UiNode` — sealed interface, `UiRenderer` — один объект, builder-функции на `WorkbenchState`. Без layout engine, без реактивности, без дерева виджетов.

---

## Step 4. Изолировать рендеринг символов в `TerminalRenderer`

### Что сейчас

`FixedWidthFontRenderer` — 436 строк low-level OpenGL/Blaze3D-кода (vertex buffers, UV-координаты, `PoseStack`, `Matrix4f`). `WorkbenchTerminalRenderer` — 124 строки, рисует фон панелей + вызывает `FixedWidthFontRenderer`. Оба знают про `Terminal`-класс.

### Что предлагается

Объединить в `compukterkraft.mod.ui.render.TerminalRenderer`, который принимает `ScreenBufferSnapshot` (из Step 1) вместо `Terminal`-класса. Выделить в отдельный пакет `compukterkraft.mod.ui.render`, чтобы весь Blaze3D-код был в одном месте.

### Обоснование: правильно ли это?

**Да, это стандартная практика.** В Minecraft-моддинге рендеринг-код — самый хрупкий (ломается между версиями MC, зависит от OpenGL-state, тесно связан с конкретными RenderType). Изоляция его в отдельный пакет с чётким API (`render(graphics, x, y, snapshot)`) — это правильно.

**Одна тонкость:** `FixedWidthFontRenderer` используется не только для терминала — в комментарии написано «This class is used for printouts too». Если планируется рендеринг напечатанных страниц (printouts), нужно оставить общий `drawString`-метод, но параметризованный на `ScreenBufferSnapshot`, а не на `Terminal`.

---

## Step 5. Упростить `MenuSide` — убрать runtime exceptions

### Что сейчас

`AbstractComputerMenu` содержит `sealed interface MenuSide` (это уже хорошо — было сделано в предыдущем плане). Но интерфейс `ComputerMenu` всё ещё объявляет методы, которые бросают `UnsupportedOperationException`:

```kotlin
fun getComputerPublic(): ServerComputer  // throws на клиенте
fun getInputPublic(): ServerInputHandler  // throws на клиенте
fun updateTerminal(state: TerminalState)  // throws на сервере
```

### Что предлагается

**НЕ** делать отдельные `ServerComputerMenu` / `ClientComputerMenu` классы.

**Причина:** Minecraft **требует** один класс для `MenuType<T>`. Фреймворк создаёт экземпляр menu по `MenuType` при открытии GUI — на сервере с контекстом, на клиенте из `FriendlyByteBuf`. Разделение на два класса **сломает** регистрацию `MenuType` и сетевую синхронизацию.

**Вместо этого:** Убрать из `ComputerMenu` server-only и client-only методы. Вынести их в `MenuSide`:

```kotlin
// Вместо ComputerMenu.getComputerPublic() / getInputPublic():
val serverSide: MenuSide.Server get() = side as MenuSide.Server  // используется только серверным кодом

// Вместо ComputerMenu.updateTerminal():
val clientSide: MenuSide.Client get() = side as MenuSide.Client  // используется только клиентским кодом
```

Серверные сетевые обработчики (`ComputerActionServerMessage` и т.д.) обращаются к `menu.serverSide.computer`. Клиентские (`ComputerTerminalClientMessage`) — к `menu.clientSide.terminal`. Cast'ы остаются, но они сосредоточены в одном месте, а не разбросаны по коду.

**Интерфейс `ComputerMenu` содержит только общие методы:**
```kotlin
interface ComputerMenu {
    val side: MenuSide
    val family: ComputerFamily
    fun updateWorkspaceEntries(entries: List<ComputerWorkspaceEntry>)
    fun updateWorkspaceDocument(document: ComputerWorkspaceDocument?)
}
```

### Обоснование

В «обычном» программировании sealed-подтипы с `as`-кастами выглядят неправильно. Но в Minecraft-моддинге `AbstractContainerMenu` — это фреймворковый класс с жёсткими ограничениями. Один `MenuType` = один класс. Попытка обойти это (фабрика, которая возвращает разные типы) создаст больше проблем, чем решит. `MenuSide` sealed interface — **правильный компромисс** для Minecraft.

---

## Step 6. Задокументировать архитектуру (KDoc + `ARCHITECTURE.md`)

### Что предлагается

1. Создать `docs/ARCHITECTURE.md` с:
   - Диаграммой потоков данных: `VM корутина → ScreenBuffer → serverTick snapshot → network packet → client ScreenBufferSnapshot → UiRenderer`
   - Описанием зон ответственности каждого пакета
   - Описанием жизненного цикла компьютера: `BlockEntity.use() → ServerComputer.turnOn() → BackgroundComputerVm.start() → … → serverTick() → sync`

2. KDoc для ключевых классов:
   - `BackgroundComputerVm` — что за класс, какие потоки, кто владеет lifecycle
   - `ComputerManager` — единый реестр компьютеров и VM
   - `ServerComputer` — серверная модель одного компьютера
   - `WorkbenchStore` — client-side state management
   - `ScreenBuffer` (новый) — формат данных, thread-safety контракт
   - `UiRenderer` (новый) — как конвертирует ноды в draw-calls

### Обоснование

Проект уже достаточно большой (compiler + mod + runtime API), чтобы через месяц забыть кто кого вызывает. Документация — не роскошь, а необходимость.

---

## Порядок выполнения

```
Step 2 (удалить мёртвый ComputerRegistry) — 5 минут, можно сделать сразу
    ↓
Step 1 (ScreenBuffer вместо Terminal) — самый большой шаг, ядро рефакторинга
    ↓
Step 4 (изолировать рендеринг) — естественное продолжение Step 1
    ↓
Step 3 (декларативный UI) — после Step 4, т.к. зависит от нового TerminalRenderer
    ↓
Step 5 (упростить MenuSide) — независим, можно параллельно со Step 3
    ↓
Step 6 (документация) — в конце, когда архитектура стабилизировалась
```

Steps 1→4→3 — это одна цепочка (переделка терминала + рендеринга + UI). Steps 2, 5, 6 — независимы.

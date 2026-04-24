# Эпики 2 + 3 + 4 — Терминал определяет размер, Terminal-предмет, чистка (план реализации)

> **Статус (2026-04-24):** Эпик 2 сдан (2.1–2.9). Эпик 3 сдан (3.1, 3.2, 3.3, 3.5; 3.4 фактически ноуп, пока жив снапшот-путь). Эпик 4 частично: задача 4.3 (DECTCEM через VT) сделана; задачи 4.1 (убрать 77×27 из `Config` + переделать `ComputerProfile`), 4.2 (удалить `ComputerTerminalClientMessage`, `ComputerMenuWithoutInventory`, дубликат-синки), а также перенесённые сюда 2.5 (убрать `screenBuffer` из `ComputerTerminalApi`) и 2.10 (снести легаси-клиент-сообщение) **всё ещё не сделаны** — это единый инвазивный cutover, потому что удаление серверного `ScreenBuffer` ломает снапшот-путь Workbench’а, что заставляет и Workbench (3.4) мигрировать на `ClientTerminalBuffer`. Сделать их вместе в отдельной ветке.

> **Для агентов:** ОБЯЗАТЕЛЬНЫЙ суб-навык: `superpowers:subagent-driven-development` (рекомендуется) или `superpowers:executing-plans`. Шаги отмечаются чекбоксами `- [ ]`.

**Предусловие:** Эпик 1 (`docs/superpowers/plans/2026-04-24-terminal-stream-io-epic-1.ru.md`) завершён. VM уже пишет поток VT-100 в `ComputerStdioApi`; серверный `VmStdioApi` пока разбирает его обратно в `ScreenBuffer`. Именно этот мост Эпик 2 и разбирает.

---

## Цели

- **Эпик 2 — Разделение сети.** `stdio.writeString(...)` на VM **рассылается как сырые байты** всем подключённым клиентам. Каждый клиент ведёт свой [VtParser](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/vt/VtParser.kt) в своём `ScreenBuffer`, размер которого берётся из клиентского UI. Несколько клиентов делят одну сессию (семантика `tmux attach -x`). Кольцевой буфер скроллбэка накапливает вывод, пока никто не подключён, и реплеит его при позднем подключении.
- **Эпик 3 — Терминал как периферийный предмет.** Новый `TerminalItem` (переносимый, без NBT). Shift+ПКМ по `ComputerBlock` с терминалом в руке связывает предмет с компьютером на время сессии. `ComputerBlock` теряет встроенное "открыть терминал" — обычный компьютер становится **headless** CPU. `WorkbenchBlock` использует тот же клиентский рендерер.
- **Эпик 4 — Чистка.** Убрать публичный доступ к `ComputerTerminalApi.screenBuffer`; снести хардкод 77×27 в `Config`; удалить более не нужный серверный `ScreenBuffer`; выкинуть костыли моргания курсора из `VmTerminalApi`.

Исходное пожелание пользователя "размер с клиента, мультиюзер" закрывается в конце Эпика 2. Эпики 3 и 4 — шлифовка: 3 приводит UX предмета к спеке, 4 сносит переходные швы, оставленные Эпиком 2.

---

## Стек и конвенции

- Kotlin/JVM, Gradle, `kotlin.test` для юнитов; NeoForge 1.21.1.
- Затронутые модули: `core` (рантайм, broadcaster, скроллбэк), `v1_21_1-common` (пакеты, меню, экраны), `v1_21_1-neoforge` (регистрация блоков/предметов, lifecycle BlockEntity).
- Пакеты регистрируются по образцу [ComputerTerminalClientMessage.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/ComputerTerminalClientMessage.kt) — id в [NetworkMessages.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt).
- DataComponent по образцу [ComputerItem.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/item/ComputerItem.kt) — обёртка `DataComponents.CUSTOM_DATA`. Свой `DataComponentType` в Эпике 3 не нужен: привязка эфемерная.

---

## Эпик 2 — Потоковый I/O по сети

### Целевая архитектура

```
VM-поток                        Серверный тик                    Клиент
────────                        ─────────────                    ──────
stdio.writeString(bytes)
  → ComputerStdioBroadcaster
       ├── append в ScrollbackRing (кольцо N байт)
       └── пометить на флаш
                                ServerComputer.serverTick()
                                  → слить накопленные байты
                                  → на каждую активную сессию:
                                      StdoutBytesClientMessage(sessionId, bytes)
                                                                 → netHandler
                                                                 → session.rxBytes(bytes)
                                                                 → VtParser.feed(bytes)
                                                                 → ClientScreenBuffer меняется
                                                                 → рендер перерисовывает
```

Ключевые структурные изменения:

- Серверный `VmStdioApi` (VtParser в ScreenBuffer) заменяется на `ComputerStdioBroadcaster` (байтовый fan-out с кольцом).
- Один `ScreenBuffer` на компьютер — больше нет. Каждая сессия держит свой буфер **на клиенте**. **Сервер не ведёт канонической сетки** — только поток байт и трекер курсора для `TerminalLineReader`.
- Появляется сущность "подключённая сессия" (`TerminalSession`), ключ отдельный от `containerId`. Сессия создаётся, когда клиент с терминал-предметом открывает UI; несёт `(cols, rows)` от клиента; живёт поверх смены контейнеров.

---

### Структура файлов

**Создать:**

- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScrollbackRing.kt` — кольцевой буфер фиксированной ёмкости (`append(chunk)`, `snapshotBytes(): ByteArray`).
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/CursorTracker.kt` — дешёвый `VtSink`, отслеживающий курсор `(x, y)` на неограниченной сетке; для `TerminalLineReader` вместо `screenBuffer.cursorX/Y`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ComputerStdioBroadcaster.kt` — новая реализация `ComputerStdioApi` со скроллбэком, трекером курсора и списком consumer-ов (`addConsumer` / `removeConsumer` / `drainPendingTo`).
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/TerminalSession.kt` — серверная запись о сессии: `sessionId`, `cols`, `rows`, `playerUuid`, `pendingBytes`.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ScrollbackRingTest.kt`.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/CursorTrackerTest.kt`.
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/ComputerStdioBroadcasterTest.kt`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/StdoutBytesClientMessage.kt` — сервер → клиент.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/AttachTerminalServerMessage.kt` — клиент → сервер: "открой сессию (cols, rows) на компе X". Несёт `sessionId: UUID` (рандом на клиенте), `instanceId`, размеры.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DetachTerminalServerMessage.kt` — закрытие.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/ResizeTerminalServerMessage.kt` — изменение окна.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientTerminalBuffer.kt` — обёртка над `ScreenBuffer` + `VtParser` для клиента; отдаёт снапшот экрану для рендера.
- `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/StdoutBytesClientMessageTest.kt` — round-trip сериализации.

**Изменить:**

- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt` — создавать `ComputerStdioBroadcaster` вместо `VmStdioApi`; убрать собственный `ScreenBuffer`; публично отдавать `attachSession(session)` / `detachSession(sessionId)` / `drainStdoutTo(session)`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/api/VmTerminalApi.kt` — убрать зависимость от `screenBuffer`; `readLine` спрашивает курсор у `CursorTracker`; серверное моргание курсора выкинуть.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt` — **удалить** `screenBuffer: ScreenBuffer` из `ComputerTerminalApi` (ломающее изменение, необходимо для headless-режима).
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerComputer.kt` — заменить `syncScreen` на `flushStdout`: на каждом тике забирать pending-байты из broadcaster-а по сессиям и слать `StdoutBytesClientMessage`. Удалить поле `screenSnapshot`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt` — вместо `clientSide.screenSnapshot` держать `terminalBuffer: ClientTerminalBuffer?`; хендлер `StdoutBytesClientMessage` кормит его.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/screen/ComputerTerminalScreen.kt` — рендерить из `ClientTerminalBuffer.snapshot()`; вычислять `(cols, rows)` по `imageWidth/imageHeight` на open и слать `AttachTerminalServerMessage`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt` — зарегистрировать три server-bound и один client-bound; `ComputerTerminalClientMessage` снять с регистрации (удалим в Эпике 4).

---

### Задача 2.1 — `ScrollbackRing`

- [ ] **Сначала тест:** `ScrollbackRingTest` — FIFO до ёмкости, старое вытесняется при переполнении, `snapshotBytes()` возвращает данные в правильном временном порядке, `append` потокобезопасен под `synchronized`.
- [ ] **Реализация:** `ByteArray` фиксированного размера, `writePos`, `size`, синхронизация на `this`. Дефолт ёмкости `64 * 1024`.
- [ ] **Проверка:** `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.computer.vm.api.ScrollbackRingTest`
- [ ] **Коммит:** `feat(stdio): scrollback ring buffer`

### Задача 2.2 — `CursorTracker`

- [ ] **Сначала тест:** прогнать через `VtParser(CursorTracker)`: `"Hi"` → `(2, 0)`; `"Hi\n"` → `(0, 1)`; `"\u001B[5;3H"` → `(2, 4)` (1-based → 0-based); backspace уменьшает X; CR обнуляет X.
- [ ] **Реализация:** `class CursorTracker : VtSink` с `var cursorX`, `var cursorY`, парой save/restore. Без клампа — сетка бесконечна. Colors / erase / SGR — no-op.
- [ ] **Проверка:** `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.computer.vm.api.CursorTrackerTest`
- [ ] **Коммит:** `feat(stdio): CursorTracker VtSink for server-side line reader`

### Задача 2.3 — `ComputerStdioBroadcaster` (сервер)

- [ ] **Сначала тест:** `ComputerStdioBroadcasterTest`
  - `writeString` добавляет в scrollback.
  - `addConsumer` сперва отдаёт снапшот scrollback (replay), потом получает новые писания.
  - `removeConsumer` прекращает доставку.
  - Два consumer-а получают одинаковый поток (shared session).
  - `writeString` обновляет внутренний `CursorTracker`, доступный через `cursor(): Pair<Int, Int>`.
- [ ] **Реализация:**
  ```kotlin
  class ComputerStdioBroadcaster(
      scrollbackBytes: Int = 64 * 1024,
  ) : ComputerStdioApi {
      private val ring = ScrollbackRing(scrollbackBytes)
      private val cursorTracker = CursorTracker()
      private val cursorParser = VtParser(cursorTracker)
      private val consumers = CopyOnWriteArrayList<Consumer>()
      override fun writeString(text: String) {
          val bytes = text.toByteArray(Charsets.UTF_8)
          ring.append(bytes)
          cursorParser.feed(text)
          for (c in consumers) c.enqueue(bytes)
      }
      fun cursor(): Pair<Int, Int> = cursorTracker.cursorX to cursorTracker.cursorY
      fun addConsumer(c: Consumer) { c.enqueue(ring.snapshotBytes()); consumers += c }
      fun removeConsumer(c: Consumer) { consumers -= c }
  }
  ```
- [ ] **Проверка:** набор тестов broadcaster-а зелёный.
- [ ] **Коммит:** `feat(stdio): broadcaster with shared-session fan-out`

### Задача 2.4 — Подключить broadcaster к VM, убрать серверный `ScreenBuffer`

- [ ] **Прочитать:** [BackgroundComputerVm.kt#L100-L300](modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/computer/vm/BackgroundComputerVm.kt).
- [ ] **Изменить:** заменить `private val screenBuffer = ScreenBuffer(...)` на `private val stdioBroadcaster = ComputerStdioBroadcaster(...)`. Вместо `VmStdioApi` передавать в `VmRuntime` сам broadcaster. У `VmTerminalApi` убрать параметр `screenBuffer`; `readLine` просит курсор через `stdioBroadcaster.cursor()` (вводим интерфейс `CursorSource`).
- [ ] **Изменить:** `readScreenSnapshot()` / `forceScreenSnapshot()` на `ComputerVmHandle` **удаляются** (их единственный потребитель `ServerComputer.syncScreen` переписывается в Задаче 2.7).
- [ ] **Компил-фикс:** удалить `VmStdioApi.kt` и `ScreenBufferVtSink.kt` из Эпика 1 вместе с их тестами — они были только мостом.
- [ ] **Проверка:** `./gradlew :core:compileKotlin` собирается.

### Задача 2.5 — Убрать `screenBuffer` из `ComputerTerminalApi`

- [ ] **Прочитать:** [ComputerRuntime.kt](modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/ComputerRuntime.kt).
- [ ] **Изменить:** убрать `val screenBuffer: ScreenBuffer` из `ComputerTerminalApi`. Снять `override` с `VmTerminalApi`. Обновить `RecordingRuntime` в `LanguageRuntimeTest.kt`.
- [ ] **Проверка:** `./gradlew :core:test :compiler:test` зелёный.
- [ ] **Коммит:** `refactor(runtime): ComputerTerminalApi no longer exposes ScreenBuffer`

### Задача 2.6 — Новые сетевые пакеты

- [ ] **Сначала тест:** `StdoutBytesClientMessageTest` — сериализация payload `(sessionId: UUID, bytes: ByteArray)` → десериализация → равенство. Аналогично `AttachTerminalServerMessage` (`sessionId`, `computerInstanceId: Int`, `cols: Int`, `rows: Int`), `DetachTerminalServerMessage` (`sessionId`), `ResizeTerminalServerMessage` (`sessionId`, `cols`, `rows`).
- [ ] **Реализация:** по образцу [KeyEventServerMessage.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/KeyEventServerMessage.kt). Id — в [NetworkMessages.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt).
- [ ] **Проверка:** новые сериализационные тесты зелёные.
- [ ] **Коммит:** `feat(net): stdout byte stream + attach/detach/resize messages`

### Задача 2.7 — Таблица сессий в `ServerComputer`

- [ ] **Прочитать:** [ServerComputer.kt#L140-L200](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerComputer.kt).
- [ ] **Изменить:**
  - Удалить поле `screenSnapshot: MutableStateFlow<ScreenBufferSnapshot?>` и метод `syncScreen()`.
  - Добавить `private val sessions = ConcurrentHashMap<UUID, TerminalSession>()`.
  - Публичные методы: `attachSession(session)`, `detachSession(sessionId)`, `resizeSession(sessionId, cols, rows)`. Каждая регистрирует / снимает consumer на broadcaster-е.
  - В `serverTick()`: обойти сессии, забрать накопленные байты, отправить `StdoutBytesClientMessage(sessionId, bytes)`. Пустые очереди пропустить.
- [ ] **Создание сессии:** `AttachTerminalServerMessage.handle` зовёт `ComputerManager.findByInstanceId(instanceId)?.attachSession(session)`. Permission-чек: игрок должен держать терминал-предмет в руке ИЛИ быть владельцем блока (это уже Эпик 3 — пока заглушка "всегда разрешаем", в Задаче 3.5 ужесточим).
- [ ] **Проверка:** компил-тайм; тесты ждут Задачу 2.8.
- [ ] **Коммит (вместе с 2.8):** объединённый.

### Задача 2.8 — Клиентский буфер и рендер

- [ ] **Реализовать `ClientTerminalBuffer`:** держит `ScreenBuffer(cols, rows)` + `VtParser` над `ScreenBufferVtSink(screenBuffer)` (файл **остаётся** — переезжает с серверной стороны на клиентскую; пакет меняем по желанию, но оставляем в `core`, т.к. переиспользуется).
  - Метод `applyStdoutBytes(bytes: ByteArray)` декодирует UTF-8 и кормит парсер.
  - Метод `snapshot(): ScreenBufferSnapshot` для рендера.
- [ ] **Изменить `ComputerMenu.ClientSide`:** вместо `screenSnapshot: ScreenBufferSnapshot?` держать `terminalBuffer: ClientTerminalBuffer?`. Точка входа: `handleStdoutBytes(containerId, sessionId, bytes)` маршрутизируется в буфер открытого меню.
- [ ] **Изменить `ComputerTerminalScreen`:**
  - В `init` вычисляем `(cols, rows)` из `imageWidth / 8` и `imageHeight / 10` (через существующий `WorkbenchTerminalMetrics`); создаём `ClientTerminalBuffer(cols, rows)`; шлём `AttachTerminalServerMessage` с `UUID.randomUUID()` как `sessionId`.
  - В `onClose` / `removed` шлём `DetachTerminalServerMessage(sessionId)`.
  - Рендер читает из `terminalBuffer.snapshot()`.
- [ ] **Хендлер:** `StdoutBytesClientMessage.handle` находит открытое меню (по `sessionId` в его `ComputerTerminalScreen`) и вызывает `applyStdoutBytes(bytes)`.
- [ ] **Проверка:** `./gradlew :v1_21_1-neoforge:runClient` — вручную поставить компьютер, открыть терминал, убедиться, что BIOS загружается и вывод шеллa идёт. Два клиента в одном LAN-мире открывают один компьютер одновременно — оба видят одинаковый вывод (shared session).
- [ ] **Коммит:** `feat(terminal): client-driven size + shared session streaming`

### Задача 2.9 — Ресайз окна клиента

- [ ] **Изменить `ComputerTerminalScreen`:** при повторном вызове `init()` (например, при изменении окна) с другими `(cols, rows)` пересоздать `ClientTerminalBuffer` **и** послать `ResizeTerminalServerMessage(sessionId, cols, rows)`. Сервер обновляет `TerminalSession`; клиент при следующем такте сам перерисует по новому scrollback (повторный `addConsumer` **не** вызываем — сервер просто шлёт дальнейшие байты; клиент в своём новом буфере "догонит" их, плюс имеем scrollback на сервере для сценария "новый клиент"). Если для ресайза нужен полный replay — добавить в `ResizeTerminalServerMessage.handle` явный вызов `broadcaster.drainPendingTo(session)` на replay-снапшоте. Проверить эмпирически.
- [ ] **Коммит:** `feat(terminal): client resize updates server session dims`

### Задача 2.10 — Снести `ComputerTerminalClientMessage`

- [ ] **Изменить `ServerComputer`:** убедиться, что ссылок на старое сообщение нет (после 2.7 их уже быть не должно).
- [ ] **Изменить `NetworkMessages.kt`:** снять id с регистрации; файл класса снести одним коммитом позже (минимизируем риск по сетевой совместимости существующих тестовых сохранений).
- [ ] **Проверка:** `./gradlew :v1_21_1-neoforge:runClient` работает.
- [ ] **Коммит:** `refactor(net): remove legacy ComputerTerminalClientMessage`

### Критерии выхода из Эпика 2

- `./gradlew :core:test :compiler:test :v1_21_1-neoforge:test` — всё зелёное.
- Ручной тест: два клиента одновременно видят одинаковый вывод; ресайз GUI меняет рендер, не меняя скорость потока байт; повторное открытие UI показывает scrollback (до N байт).
- На сервере `ScreenBufferSnapshot` больше не встречается вне VT-sink-а (клиент использует — сервер нет).

---

## Эпик 3 — Терминал как периферийный предмет

### Целевой UX

- Игрок крафтит **Terminal**. Без NBT. Shift+ПКМ по `ComputerBlock` — UI терминала открывается, привязанный к `instanceID` этого компьютера.
- Если игрок кликает терминалом **не** по блоку (ПКМ в воздух), используется "последний привязанный" — хранится **только в сессионной памяти сервера** (`TransientPairing`, чистится на `PlayerLoggedOutEvent`). Shift+ПКМ в воздух переоткрывает последнее; ПКМ в воздух без привязки — no-op.
- ПКМ по `ComputerBlock` без терминал-предмета в руке теперь не делает **ничего** (в будущем — LED / peripheral poke). Встроенное меню больше не открывается.
- `WorkbenchBlock` продолжает открывать своё меню, но переиспользует рендерер Эпика 2.

### Оговорки по scope

- Персистентная привязка — не цель (спека: эфемерная). Терминал-предмет не несёт NBT-компонентов.
- Межмерные / дальние подключения: **radius-чек** на `AttachTerminalServerMessage`; конфигурируется новой `Config.TERMINAL_CONNECT_RADIUS_BLOCKS` (дефолт 32). Вне радиуса — attach отклоняется с сообщением игроку.

### Структура файлов

**Создать:**

- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/item/TerminalItem.kt` — наследник `Item`. `use()` — ПКМ в воздух; `useOn()` — по блоку.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/session/TransientPairing.kt` — серверный `ConcurrentHashMap<UUID playerUuid, TerminalBinding>`. `TerminalBinding(computerInstanceId: Int, dimensionId: ResourceKey<Level>, pairedAt: Long)`. Чистится на `PlayerLoggedOutEvent`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/menu/TerminalItemMenu.kt` — меню без инвентарного слота; держит клиентский `ClientTerminalBuffer`, переиспользует общий рендер.
- `modules/v1_21_1/v1_21_1-neoforge/src/main/kotlin/ru/lazyhat/compukterkraft/impl/terminal/TerminalItemRegistration.kt` — регистрация предмета и типа меню в NeoForge.
- `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/terminal/session/TransientPairingTest.kt`.

**Изменить:**

- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlock.kt` — убрать "открыть меню на use". `use()` / `useWithoutItem()` возвращают `InteractionResult.PASS`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/ServerComputer.kt` — `attachSession()` теперь принимает игрока и измерение; проверка радиуса и совпадения измерения.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/Config.kt` — добавить `TERMINAL_CONNECT_RADIUS_BLOCKS: Int = 32`.

### Задача 3.1 — `TerminalItem` + регистрация

- [ ] `TerminalItem.useOn(context)`: если не присядно — `PASS`; если присядно и блок — `ComputerBlock`, достать BlockEntity, взять `instanceId`, вызвать `openTerminalForPlayer(player, instanceId, computerPos, dimension)`. Прописать `TransientPairing`.
- [ ] Зарегистрировать предмет через `DeferredRegister<Item>`, добавить в creative-таб.
- [ ] **Ручной дым-тест:** `/give` терминал, Shift+ПКМ по компьютеру → UI открывается, BIOS стримится.
- [ ] **Коммит:** `feat(terminal-item): portable terminal opens UI on shift+RMB`

### Задача 3.2 — `TransientPairing`

- [ ] **Сначала тест:** `TransientPairingTest` — set, get, очистка при симуляции logout.
- [ ] **Реализация:** маленький класс с `set(uuid, binding)`, `get(uuid)`, `clear(uuid)`. Повесить `PlayerLoggedOutEvent` через шину NeoForge.
- [ ] **Интеграция:** `TerminalItem.use()` (ПКМ в воздух) читает pairing; есть — открывает меню; нет — no-op.
- [ ] **Коммит:** `feat(terminal-item): session-memory pairing`

### Задача 3.3 — Headless `ComputerBlock`

- [ ] Убрать из `ComputerBlock.use*()` логику открытия меню. Placement / break — без изменений.
- [ ] Удалить `ComputerMenuWithoutInventory` и регистрацию его `MenuType` — **после того** как `TerminalItemMenu` закроет все потребности (финальное удаление в Эпике 4, Задача 4.2).
- [ ] **Ручной тест:** ПКМ по компьютеру — ничего. Поставить терминал, Shift+ПКМ — работает.
- [ ] **Коммит:** `refactor(computer-block): headless — no built-in terminal UI`

### Задача 3.4 — Workbench переиспользует общий рендерер

- [ ] Разобрать [WorkbenchTerminalClientMessage.kt](modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/client/WorkbenchTerminalClientMessage.kt) и удалить. Меню Workbench открывает аналог `TerminalItemMenu`, но привязанный к "отсоединённой цели" workbench-а; рендер идентичен Задаче 2.8.
- [ ] Тесты: `WorkbenchTerminalViewStateTest` должен остаться зелёным после миграции на stream-буфер.
- [ ] **Коммит:** `refactor(workbench): reuse stream-I/O terminal renderer`

### Задача 3.5 — Permission + radius-чек

- [ ] **Сначала тест:** серверный `attachSession` с игроком дальше `TERMINAL_CONNECT_RADIUS_BLOCKS` от позиции компьютера → отклонено (вернуть в чат "out of range"; сессия не создаётся).
- [ ] **Реализация:** в `ServerComputer.attachSession` сравнить `player.position.distanceToSqr(computerPos)` с `radius^2`. Другое измерение → отклонить.
- [ ] **Коммит:** `feat(terminal-item): enforce connect radius`

### Критерии выхода из Эпика 3

- Стоит `ComputerBlock`, ПКМ — ничего не открывается.
- Shift+ПКМ терминалом по блоку: UI открыт, компьютер стримит.
- ПКМ в воздух терминалом с привязкой: UI переоткрывается; без привязки: no-op.
- Два игрока, оба привязались к одному компьютеру, — видят одну сессию.
- Отойти 32+ блоков и попытаться переоткрыть: отказ.

---

## Эпик 4 — Чистка

### Задача 4.1 — Снести хардкод 77×27

- [ ] `Config.DEFAULT_COMPUTER_TERM_WIDTH` и `DEFAULT_COMPUTER_TERM_HEIGHT` — **удалить**. Грепом проверить остатки.
- [ ] `ComputerProfileRegistry` больше не строит `ScreenBuffer`; поля `terminalWidth/terminalHeight` у `ComputerProfile` заменить на одно `initialScrollbackBytes` (дефолт 64 КиБ).
- [ ] `WorkbenchTerminalMetrics` — ширина/высота берутся из размеров открытого экрана, не из константы.
- [ ] **Коммит:** `cleanup: remove hardcoded terminal dimensions`

### Задача 4.2 — Удалить мёртвый код переходных швов

- [ ] Удалить `VmStdioApi.kt` и его тест (мост — broadcaster его заменил).
- [ ] Удалить `ScreenBufferVtSink.kt` из `core`, если клиентский пакет забрал владение; если файл общий — оставить в `core`, удалить дубль.
- [ ] Удалить `ComputerTerminalClientMessage.kt` и его тест.
- [ ] Удалить `ComputerMenuWithoutInventory.kt`, если на него больше нет ссылок.
- [ ] **Коммит:** `cleanup: remove Epic 2 compat seams`

### Задача 4.3 — `VmTerminalApi` честно по форме

- [ ] `VmTerminalApi` больше не принимает `screenBuffer`. `readLine` получает курсор через `stdio.cursorSource`. Моргание курсора было фичей `ScreenBuffer` — теперь реализуется VT-последовательностями: в начале `readLine` шлём `CSI ? 25 h` (показать курсор), в конце — `CSI ? 25 l` (скрыть). Клиентский парсер узнаёт DECTCEM и дёргает `setCursorVisible(Boolean)` на sink-е.
- [ ] **Сначала тест:** расширить `VtParserTest` DECTCEM-последовательностями → sink получает `setCursorVisible(true/false)`. Добавить `setCursorVisible(Boolean)` в `VtSink` (default no-op). Серверный `CursorTracker` игнорирует; клиентский `ScreenBufferVtSink` мапит на `screenBuffer.setCursorBlink(...)`.
- [ ] **Коммит:** `refactor(readline): cursor blink via VT DECTCEM`

### Задача 4.4 — Финальный проход

- [ ] `grep -rn "screenBuffer" modules/` — убедиться: только клиентский код и переименованный sink.
- [ ] `./gradlew :core:test :compiler:test :v1_21_1-neoforge:test` зелёные.
- [ ] Ручная проверка в игре: регрессий нет.
- [ ] **Коммит:** `docs: mark Epics 2+3+4 as complete`

### Критерии выхода из Эпика 4

- В `Config` нет хардкода размеров терминала.
- На сервере нет ни одного `ScreenBuffer`.
- `ComputerTerminalApi` — чистая поверхность потокового I/O.
- По грепу `ScreenBuffer` импортируется только клиентским кодом.

---

## Риски и митигация

| Риск | Митигация |
|------|-----------|
| Ежетиковая рассылка байт забивает сеть при спамных программах | Scrollback кольцо тянет запись; `pendingBytes` сессии слив раз в тик с жёстким кэпом (скажем, 8 КБ/тик/сессия); overflow вызывает разовый `CLEAR`-ресинк, не тихий drop |
| Ресайз клиента гонка с летящими байтами | Resize-сообщение идемпотентно; если чанк уже в очереди сессии — клиент применит в новом буфере, `ScreenBufferVtSink` уже клампит "курсор вне границ" |
| `TransientPairing` течёт через релоады | Чистка на `ServerStoppingEvent` и `PlayerLoggedOutEvent`; в NBT не писать |
| Два игрока на разных концах мира делят сессию → гриф | `TERMINAL_CONNECT_RADIUS_BLOCKS` проверяется на attach, не непрерывно; выход за радиус не отключает, но новый attach отказывает (для Эпика 3 достаточно) |
| На сервере потерялось моргание курсора в `readLine` | Задача 4.3 переносит через DECTCEM в VT-парсер; клиентская проблема |
| Save/load скроллбэка | Не персистируем. Документируем как осознанный выбор — на релоаде кольцо пустое. Долгоиграющие программы с баннером на boot (например `bios.ck`) повторят его при следующем attach |

---

## Не-цели (явно вне scope)

- Двунаправленный stdin (клиент → сервер как байтовый поток). Клавиши по-прежнему летят структурными `ComputerEvents`. Если потом понадобится `term.readByte()`, сделаем в Эпике 5.
- Персистентная привязка / имена терминалов / ярлыки.
- Перекалибровка цветов (монохромный терминал на цветной сессии). Все терминалы пока цветные.
- VM-мобильность через сеть (переезд компьютера между измерениями / серверами).

---

## Заметки по исполнению

- План на три Эпика. Лучше **Subagent-Driven** — один агент на задачу с ревью между; inline на 20+ задачах копит контекст сверх разумного.
- Задачи внутри Эпика строго упорядочены; между Эпиками — тоже (2 → 3 → 4). Параллелить Эпики нельзя.
- После каждого коммита: `./gradlew :core:test :compiler:test` как страховка. Полный `:v1_21_1-neoforge:test` — на границах Эпиков и перед их закрытием.

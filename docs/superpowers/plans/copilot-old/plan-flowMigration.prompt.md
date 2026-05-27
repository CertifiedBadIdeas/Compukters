# Plan: Полный переход на Kotlin Flows

**TL;DR:** Кодовая база уже на 70% мигрирована на `StateFlow` — все ключевые `MutableStateFlow` объявлены, но потребляются через `.value` polling. План завершает переход: добавляет `CoroutineScope` на клиент и сервер, заменяет `ComputerVmCallbacks` и `InputHandler` на sealed-class + `SharedFlow`, и переводит все polling-точки на реактивный `collect`.

## Phase 1: Инфраструктура CoroutineScope

**1.1** Создать `MinecraftMainDispatcher` — utility-класс для client-side корутин:
- Новый файл `mod/src/main/kotlin/ck/mod/infrastructure/coroutines/MinecraftMainDispatcher.kt`
- Реализует `CoroutineDispatcher`, делегирует `Minecraft.getInstance().tell(Runnable)` — гарантирует выполнение на render thread
- Экспонирует `val Dispatchers.minecraft: CoroutineDispatcher` extension property

**1.2** Создать client-side `CoroutineScope` в `ComputerWorkbenchScreen`:
- В `ComputerWorkbenchScreen.kt` добавить поле `private var screenScope: CoroutineScope? = null`
- В `init()` (L63-L67): создавать `CoroutineScope(SupervisorJob() + Dispatchers.minecraft)`
- В `removed()` (L69-L72): вызывать `screenScope?.cancel()`
- Передавать scope в `WorkbenchStore` при `bind()`

**1.3** Добавить server-side `CoroutineScope` в `ServerComputer`:
- В `ServerComputer.kt` рядом с полями (L58-L75) создать `private val serverScope = CoroutineScope(SupervisorJob() + serverDispatcher)` — dispatcher можно создать из `ServerContext.server` или reuse `Dispatchers.Default`
- Отменять scope в `dispose()` / при удалении компьютера из `ComputerManager`

## Phase 2: Реактивный WorkbenchStore

**2.1** Изменить `WorkbenchStore.bind()` (L53-L58):
- Принимать `CoroutineScope` как параметр: `fun bind(scope: CoroutineScope, source: WorkbenchUpdateSource)`
- Запускать `scope.launch { source.stateFlow.collect { mergeRemoteState(it) } }` и сохранять `Job`

**2.2** Удалить `tick()` (L70-L75) — polling больше не нужен

**2.3** Удалить вызов `store.tick()` из `ComputerWorkbenchScreen.containerTick()` (L74-L78)

**2.4** В `dispose()` (L60-L62) — отменять collect Job

## Phase 3: Замена ComputerVmCallbacks на SharedFlow

**3.1** Создать sealed interface `VmLifecycleEvent` в `VmStateManager.kt` или рядом:
- `data class Stopped(val reason: VmStopReason) : VmLifecycleEvent`
- `data object RebootRequested : VmLifecycleEvent`

**3.2** В `BackgroundComputerVm` (L74-L82):
- Заменить `callbacks: ComputerVmCallbacks` на `private val _lifecycleEvents = MutableSharedFlow<VmLifecycleEvent>(extraBufferCapacity = 4)`
- Экспонировать `val lifecycleEvents: SharedFlow<VmLifecycleEvent>`
- `currentLabel()` вынести в конструктор как `labelProvider: () -> String?`

**3.3** В `stopInternal()` (L162-L176):
- Заменить `callbacks.onVmRebootRequested()` → `_lifecycleEvents.emit(VmLifecycleEvent.RebootRequested)`
- Заменить `callbacks.onVmStop(reason)` → `_lifecycleEvents.emit(VmLifecycleEvent.Stopped(reason))`

**3.4** В `ServerComputer` (L177-L183):
- Удалить реализацию `ComputerVmCallbacks`
- В `serverScope` подписаться: `vmHandle.lifecycleEvents.collect { event -> when(event) { is RebootRequested -> _rebootRequested.value = true; is Stopped -> { /* handle */ } } }`

**3.5** Удалить интерфейс `ComputerVmCallbacks` из `BackgroundComputerVm.kt` (L51-L57)

## Phase 4: Реактивный ServerComputer (замена serverTick polling)

**4.1** Создать в `ServerComputer` реактивную подписку на `VmLifecycleState.stateFlow`:
- В `serverScope.launch` подписаться на `vmHandle.stateManager.lifecycle.stateFlow` через `collectLatest`
- Реагировать на переход в `STOPPED`/`CRASHED` → удалять VM, обрабатывать reboot

**4.2** Реактивная подписка на `_rebootRequested`:
- `serverScope.launch { _rebootRequested.filter { it }.collect { handleReboot() } }`

**4.3** Упростить `serverTick()` (L146-L173):
- Оставить только: `handle.requestSlice()`, drain host calls, `syncScreen()`
- Убрать проверки state == STOPPED/CRASHED и reboot — они теперь реактивные

**4.4** Вывести `isOn` из `VmLifecycleState.stateFlow`:
- Заменить геттер `isOn` (L86-L92) на `val isOnFlow: StateFlow<Boolean>` = `stateFlow.map { it !in setOf(COLD, STOPPED, CRASHED) }.stateIn(serverScope)`
- Оставить `val isOn: Boolean get() = isOnFlow.value` для обратной совместимости

## Phase 5: Sealed InputEvent + SharedFlow

**5.1** Создать sealed interface `InputEvent` (новый файл `mod/src/main/kotlin/ck/mod/application/input/InputEvent.kt`):
- `data class KeyDown(val key: Int, val repeat: Boolean) : InputEvent`
- `data class KeyUp(val key: Int) : InputEvent`
- `data class CharTyped(val char: Byte) : InputEvent`
- `data class Paste(val data: ByteArray) : InputEvent`
- `data class MouseClick(val button: Int, val x: Int, val y: Int) : InputEvent`
- `data class MouseUp(val button: Int, val x: Int, val y: Int) : InputEvent`
- `data class MouseDrag(val button: Int, val x: Int, val y: Int) : InputEvent`
- `data class MouseScroll(val direction: Int, val x: Int, val y: Int) : InputEvent`
- `sealed interface ControlAction : InputEvent` — `Terminate`, `Shutdown`, `TurnOn`, `Reboot`

**5.2** Удалить `InputHandler` (`InputHandler.kt`) полностью:
- Заменить на `fun interface InputEventSink { fun accept(event: InputEvent) }`
- Все 12 методов интерфейса заменяются одним `accept()` — маппинг в конкретные действия происходит через `when(event)` на стороне потребителя
- Обновить все точки использования (`ClientInputHandler`, `ServerInputState`, `WorkbenchTerminalInputController`) за один проход

**5.3** Рефакторить `ClientInputHandler` → `ClientInputEventSink` (`ClientInputHandler.kt`):
- Реализует `InputEventSink`
- Один `accept(event: InputEvent)` с `when(event)` маппит в правильный `gateway.send(event)` вызов
- Удалить все 12 отдельных method-реализаций

**5.4** Рефакторить `ComputerInputGateway` (`ComputerInputGateway.kt`):
- Упростить до `fun send(event: InputEvent)` — один метод вместо четырёх

**5.5** Рефакторить `NetworkComputerInputGateway` (`NetworkComputerInputGateway.kt`):
- Один `when(event)` dispatch в `send()` вместо четырёх методов

**5.6** Рефакторить `ServerInputState` (`ServerInputState.kt`):
- Принимает `InputEvent`, диспатчит в `ComputerEvents.*()` через один `when(event)`
- Трекинг `keysDown`, `lastMouse*` остаётся внутри

**5.7** Обновить `WorkbenchTerminalInputController` (`WorkbenchTerminalInputController.kt`):
- Вместо вызова `computer.keyDown(key, repeat)` → `computer.accept(InputEvent.KeyDown(key, repeat))`

**5.8** Рефакторить `ComputerEvents` (`ComputerEvents.kt`):
- Заменить все отдельные `keyDown()`/`keyUp()`/`charTyped()`/... на единый `fun dispatch(receiver: Receiver, event: InputEvent)`
- Внутри — `when(event)` маппинг `InputEvent` → `queueEvent(name, args)`
- Удалить старые методы — никаких промежуточных обёрток

## Phase 6: Screen snapshot — опциональная реактивность

**6.1** Terminal snapshot уже `StateFlow`. На клиенте `.value` reads в `renderBg()` — это **нормально** для immediate-mode рендеринга. Менять не нужно.

**6.2** На сервере `syncScreen()` (L187-L198) оставить в `serverTick()` — рассылка пакетов всем наблюдателям привязана к тику. Не трогать.

---

## Verification

1. Существующие тесты должны проходить после каждой фазы — запуск `./gradlew :mod:test :compiler:test`
2. Ручная проверка: открыть GUI компьютера, включить, написать программу в редакторе, переключиться на терминал — всё должно работать без задержек
3. Phase 3+4: включить/выключить/перезагрузить компьютер — убедиться что lifecycle transitions корректны
4. Phase 5: проверить что все key/mouse events доходят от клиента до VM (терминальный ввод)
5. Проверить отсутствие memory leaks: `screenScope?.cancel()` в `removed()`, `serverScope.cancel()` при dispose

## Decisions

### Принятые

- **Dispatcher на клиенте:** Создаём `MinecraftMainDispatcher` — собственный `CoroutineDispatcher`, делегирующий `Minecraft.getInstance().tell(Runnable)`. Это единственный корректный способ гарантировать выполнение корутин на render thread Minecraft. Экспонируется как `Dispatchers.minecraft` extension property. Один utility-файл, используется во всех client-side `CoroutineScope`.

- **`InputHandler` → полная замена на `InputEventSink`:** Интерфейс `InputHandler` с 12 методами удаляется целиком. Заменяется на `fun interface InputEventSink { fun accept(event: InputEvent) }`. Sealed interface `InputEvent` покрывает все типы ввода. Все реализации (`ClientInputHandler`, `ServerInputState`, `WorkbenchTerminalInputController`, `ComputerEvents`) мигрируются за один проход — никаких compatibility wrappers.

### Обоснования сохранения текущих паттернов

- **`syncScreen()` остаётся в `serverTick()`:** Рассылка network-пакетов (`ComputerTerminalClientMessage`) всем наблюдающим игрокам должна происходить из server tick thread. Вызов `sendToPlayer()` из `StateFlow.collect` в произвольном coroutine-контексте создаёт race condition с Netty pipeline Minecraft. Tick-based polling `ScreenBuffer.dirty` → snapshot → send — естественная и безопасная модель для Minecraft-сервера. Кроме того, экран обновляется максимум 20 раз в секунду (1 раз за тик), что совпадает с частотой server tick — реактивность не даёт выигрыша.

- **`ScreenBuffer.@Volatile dirty` остаётся как есть:** `ScreenBuffer` — low-level VM-примитив в `compiler` модуле (без Minecraft-зависимостей). Каждый `write()`/`setCursor()`/`scroll()` вызов помечает `dirty = true`. Замена на `StateFlow` добавила бы overhead на каждую запись символа (десятки тысяч раз в секунду при быстром выводе). Текущая схема: `@Volatile dirty` + `synchronized` snapshot копия — оптимальна для producer (VM coroutine) / consumer (server tick) модели с единственным читателем.

- **Client-side `.value` reads в `renderBg()` для screen snapshot:** Minecraft использует immediate-mode рендеринг — `Screen.render()` вызывается каждый кадр (~60fps). Подписка через `collect` на рендеринговые данные не имеет смысла: snapshot нужно читать синхронно в момент отрисовки, а не реагировать на изменения асинхронно. `menu.clientSide.screenSnapshot` (backed by `StateFlow.value`) — идиоматичный паттерн для этого случая.

- **`SingleContainerData` / Minecraft DataSlots:** `ContainerData` — часть Minecraft API для автоматической синхронизации integer-данных между сервером и клиентом через `AbstractContainerMenu`. Заменять на Flow невозможно — это фреймворковый контракт. Используется для синхронизации `isOn` (1 int).

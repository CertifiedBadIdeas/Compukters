# MACHINE

> **Superseded.** This document describes the legacy CKL / Image-VM
> machine model (CKIM, host-call boundary, `HostCallManager`,
> `BackgroundComputerVm`, device daemon, runtime kernel). After issue
> #26 the CKL stack was removed, and after issue #44 the Image-VM and
> device daemon were retired in favour of Kraft16 guest execution through
> `K16Computer` (flat RAM + MMIO). See [ARCHITECTURE.md](ARCHITECTURE.md)
> for the current product architecture and
> [k16-vm-code-flow.md](k16-vm-code-flow.md) for the current Rust VM code
> flow. The text below is kept only for historical context and should not
> be used as guidance for new VM work.

Подробное описание того, как устроена VM в моде Compukter-Kraft по фактической реализации.

Основная точка входа VM: `mod/src/main/kotlin/ck/mod/computer/vm/BackgroundComputerVm.kt`.

## 1. Общая схема

VM здесь состоит из четырех слоев:

1. `ServerComputer`
   Серверное представление одного компьютера в мире. Оно отвечает за включение, выключение, тики, доставку событий, обработку host calls и синхронизацию display sessions игрокам.

2. `BackgroundDeviceVm`
   Хост одной VM. Запускает программу на фоновой корутине, держит состояние VM, очередь событий, очередь host calls, display registry, IPC registry и runtime-объекты.

3. `VmRuntime`
   Реализация runtime API, которую видит исполняемая программа. Через нее язык получает доступ к `display`, `system`, `events`, `process`, `ipc`, `strings`, а также к операциям `sleep` и `yield`.

4. `CkVmImageComputerProgram` / `NativeImageVmRunner`
   Исполнитель Rust image VM. Kotlin frontend временно компилирует исходник в `CkVmImage`, а runtime запускает его через JNI и `native/ckl-vm`.

В результате архитектура выглядит так:

```text
Server tick thread
  -> ServerComputer.serverTick()
     -> BackgroundComputerVm.requestSlice()
     -> BackgroundComputerVm.drainHostCalls()
     -> HostCallDispatcher.dispatch(...)
     -> BackgroundComputerVm.deliverHostResults()
   -> RuntimeDeviceImpl.flushDisplaySessions()

Background coroutine
  -> BackgroundComputerVm.boot()
     -> ComputerProgramCompiler.compile(...)
   -> CkVmImageComputerProgram.run(runtime)
   -> NativeImageVmRunner.run(image, runtime)
     -> RuntimeHostBridge / VmRuntime / APIs
```

## 2. Кто создает и владеет VM

### `ComputerVmSupervisor`


Подтверждает, что:

Файл: `mod/src/main/kotlin/ck/mod/computer/vm/ComputerVmSupervisor.kt`

Этот класс создает общую инфраструктуру для всех VM:

- поднимает fixed thread pool из 2 потоков;
- превращает его в coroutine dispatcher;
- хранит таблицу активных `ComputerVmHandle` по `computerId`;
При `getOrCreate(...)` supervisor создает `BackgroundComputerVm` и передает в нее:

- `computerId`;
- `ComputerProfile`;
- coroutine dispatcher;
- `labelProvider`;
- logger.

### `ComputerManager`

Файл: `mod/src/main/kotlin/ck/mod/context/ComputerManager.kt`

Это реестр более высокого уровня. Он держит и `ServerComputer`, и сами VM-хэндлы, а доступ к IDE проксирует через supervisor.

### `ServerComputer`

Файл: `mod/src/main/kotlin/ck/mod/computer/ServerComputer.kt`

Это главный серверный оркестратор жизненного цикла компьютера:

- выбирает профиль через `ComputerProfileRegistry.forFamily(...)`;
- удаляет старую VM, если она осталась;
- получает или создает новую `BackgroundComputerVm`;
- вызывает `boot()`;
- слушает terminal lifecycle states и реагирует на stop/crash/reboot;
- каждый тик двигает VM вперед и flush-ит display sessions.

## 3. Что находится внутри `BackgroundComputerVm`

Файл: `mod/src/main/kotlin/ck/mod/computer/vm/BackgroundComputerVm.kt`

Одна VM хранит у себя следующие ключевые объекты:

- `scope = CoroutineScope(SupervisorJob() + dispatcher)`
- `slicePermits = Channel<Unit>(capacity = 1)`
- `stateManager = VmStateManager()`
- `eventManager = EventManager(profile.maxEventQueueSize)`
- `hostCallManager = HostCallManager()`
- `displayRegistry = DisplayRegistry()` — источник runtime UI frames для клиентского Computer screen;
- `ipcRegistry = IpcChannelRegistry()` — VM-local текстовые каналы без встроенной семантики stdin/stdout/stderr;
- `runtime = createRuntime("", "")`

Смысл каждого компонента:

- `scope` держит фоновую корутину VM.
- `slicePermits` выдает VM разрешения на выполнение по одному slice за раз.
- `stateManager` хранит жизненный цикл и данные планировщика.
- `eventManager` хранит очередь входящих событий.
- `hostCallManager` хранит запросы VM к хосту и ожидаемые ответы.
- `displayRegistry` хранит display/pixel-buffer state, из которого `RuntimeDeviceImpl` flush-ит `DisplayFrameDelta` в клиентские display sessions.
- `ipcRegistry` хранит локальные каналы; stdio для ROM/process code задается только соглашением поверх `ipc`.
- для Rust image runtime целевой архитектурой теперь считается отдельный shared `DeviceRuntimeKernel`, который должен забрать device-local ownership у `eventManager` и `ipcRegistry`, оставив Kotlin-хосту только world-facing integration;
- `runtime` это API-объект, который получает выполняемая программа.

## 4. Полный жизненный цикл VM

### 4.1. Включение компьютера

Когда сервер вызывает `ServerComputer.turnOn()`:

1. Проверяется, что компьютер еще не включен.
2. Старая VM удаляется через `computerManager.removeVm(instanceID, VmStopReason.CLOSED)`.
3. Создается новая `BackgroundComputerVm`.
4. Вызывается `handle.boot()`.
6. Запускается наблюдение за terminal states через `observeLifecycle(handle)`.

### 4.2. Что делает `boot()`

`BackgroundComputerVm.boot()`:

1. Проверяет, что runner уже не активен.
2. Переводит состояние в `VmState.Booting`.
3. Запускает фоновую корутину `runner = scope.launch { ... }`.
4. Внутри корутины:
   - компилирует исходник через `ComputerProgramCompiler.compile(...)`;
   - если компиляция не удалась, останавливает VM с ошибкой;
   - ждет разрешения на выполнение через `awaitSlicePermit()`;
   - вызывает `program.run(runtime)`;
   - после штатного завершения вызывает `stopInternal(VmStopReason.REQUESTED)`.
5. Сразу после запуска корутины добавляет событие `VmEvent("boot")` в очередь событий.

Именно это событие потом может подобрать код внутри программы через `pullEvent()`.

### 4.3. Выключение и перезапуск

Остановка идет через `BackgroundComputerVm.stop(...)`, который внутри `scope.launch` вызывает `stopInternal(reason)`.

`stopInternal(...)`:

- проверяет, что VM еще не в terminal state;
- переводит состояние в `VmState.Stopped(reason)` или `VmState.Crashed(errorMessage)`;
- отменяет `runner`;
- зануляет `runner`.

На стороне сервера `ServerComputer.observeLifecycle(...)` подписывается на `BackgroundComputerVm.terminalStates`, и когда состояние становится terminal:

- VM удаляется из supervisor;
- ссылка `vmHandle` сбрасывается;
- если причина остановки это `VmStopReason.REBOOT`, компьютер автоматически поднимается снова через `turnOn()`.

## 5. Потоки и модель выполнения

### 5.1. Два контекста исполнения

VM спроектирована как кооперативная система с двумя основными контекстами:

1. Фоновая coroutine VM
   Именно здесь исполняется байткод программы и вызываются API `runtime.display`, `runtime.process`, `runtime.system`, `runtime.ipc`.

2. Server tick thread
   Именно отсюда сервер вызывает:
   - `requestSlice(serverTick)`
   - `drainHostCalls()`
   - `deliverHostResults(...)`
   - `flushDisplaySessions()`
   - `snapshot()`

### 5.2. Почему это кооперативная VM

VM не прерывает программу в произвольной точке. Вместо этого программа должна регулярно доходить до scheduling point.

Scheduling point возникает в двух местах:

1. На уровне байткодной VM после каждых 64 инструкций возвращается `VmSignal.Yield`.
2. На уровне `VmRuntime.sleep(...)`, `VmRuntime.pullEvent(...)` и `VmRuntime.yield()` управление тоже возвращается наружу.

После этого `BackgroundComputerVm.applySchedulingPoint()` решает, продолжать ли выполнять код прямо сейчас или ждать следующего server tick slice.

## 6. Как работает планировщик и ограничение CPU

### 6.1. Slice-based scheduling

Основной механизм это `slicePermits: Channel<Unit>(capacity = 1)`.

Каждый серверный тик делает:

1. `handle.requestSlice(level.gameTime)`
2. `BackgroundComputerVm` обновляет `currentTick`
3. Проверяет `sleepUntilTick`
4. Если VM еще спит, разрешение не выдается
5. Иначе делается `slicePermits.trySend(Unit)`

Со стороны VM корутина вызывает `awaitSlicePermit()` и блокируется, пока сервер не даст следующий permit.

### 6.2. Step budget на slice

У каждого профиля есть `maxStepsPerSlice`:

- Normal Computer: `10_000` VM steps
- Advanced Computer: `100_000` VM steps
- Command Computer: `4_000_000` VM steps

Когда K16 VM получает permit, host runtime передает этот лимит как `maxSteps`:

```kotlin
K16ComputerRuntimeFactory.createFromBiosFlash(maxSteps = profile.resources.cpu.maxStepsPerSlice)
```

Это не wall-time окно: K16 останавливает выполнение после указанного числа VM steps или раньше, если сама дошла до сигнала ожидания/вывода.

### 6.3. Ограничение по инструкциям

В модуле `compiler` байткодная VM считает инструкции через `instructionsSinceYield`.

После каждых `64` инструкций срабатывает:

```kotlin
if (instructionsSinceYield >= YIELD_INTERVAL) {
    instructionsSinceYield = 0
    return VmSignal.Yield
}
```

То есть модель планирования двухуровневая:

- грубое ограничение по wall-clock time на slice;
- регулярные yield-поинты каждые 64 инструкции, чтобы программа не держала управление слишком долго без проверки дедлайна.

### 6.4. Что это означает practically

- VM не исполняет бесконечный цикл бесконтрольно без проверок; он будет доходить до `Yield` каждые 64 инструкции.
- Но это не preemptive multitasking в классическом смысле. Если в будущем появится тяжелая операция внутри одной инструкции или большого хост-вызова, она сама по себе не разрежется на подкуски этим механизмом.
- Ограничение CPU относится к длительности slice между scheduling points, а не к суммарному времени жизни программы.

## 7. Состояния VM

Состояния описаны в `compiler/src/main/kotlin/ck/lang/runtime/ComputerVmModels.kt`:

- `Cold`
- `Booting`
- `Running`
- `WaitingEvent`
- `Sleeping`
- `Stopped(reason)`
- `Crashed(errorMessage)`

`VmStateManager` делит хранение на две части:

1. `VmLifecycleState`
   Управляет `VmState` и terminal transitions.

2. `VmSchedulingState`
   Хранит:
   - `currentTick`
   - `sleepUntilTick`
   - `sliceDeadlineNanos`

Terminal transitions защищены через `Mutex` внутри `VmLifecycleState.stopVm(...)`, чтобы не было гонок при остановке.

## 8. Очередь событий и доставка событий

### 8.1. `EventManager`

Файл: `mod/src/main/kotlin/ck/mod/computer/vm/EventManager.kt`

События живут в:

- `Channel<VmEvent>(capacity = maxQueueSize, onBufferOverflow = BufferOverflow.DROP_OLDEST)`
- `deferredEvents = ArrayDeque<VmEvent>()`

### 8.2. Ограничение очереди событий

Размер очереди зависит от профиля:

- normal: `64`
- advanced: `128`
- command: `256`

Если очередь переполнена, старые события выбрасываются через `DROP_OLDEST`.

Это важно: VM не гарантирует сохранность всех событий при перегрузке. Она предпочитает оставаться живой, а не бесконечно расти по памяти.

### 8.3. `deferEvent(...)`

Во время чтения строки в терминале `TerminalLineReader` может встретить событие, которое не относится к вводу текста. Такие события не теряются, а складываются в `deferredEvents`, чтобы потом их мог получить обычный `pullEvent()`.

### 8.4. Как программа получает события

`VmRuntime.pullEvent(filter)` делает:

1. переводит состояние VM в `WaitingEvent`;
2. берет событие из `ctx.receiveEvent()`;
3. если фильтра нет или имя совпадает, возвращает событие;
4. переводит состояние обратно в `Running`;
5. вызывает `ctx.schedulingPoint()` перед возвратом.

На уровне языка это приходит через builtin `events.pull(...)`, который байткодная VM конвертирует в `VmSignal.WaitEvent(filter)`.

## 9. Host calls: как VM просит хост сделать работу

### 9.1. `HostCallManager`

Файл: `mod/src/main/kotlin/ck/mod/computer/vm/HostCallManager.kt`

Host calls нужны для операций, которые выполняются на стороне сервера, а не внутри VM-корутины.

Структура:

- `hostCalls = ConcurrentLinkedQueue<HostCall>()`
- `hostResponses = ConcurrentHashMap<Long, CompletableDeferred<HostResult>>()`
- `nextHostCallId = AtomicLong()`

Алгоритм:

1. VM вызывает `awaitHostCall { id -> HostCall... }`.
2. `HostCallManager` выдает новый `callId`.
3. Создает `CompletableDeferred<HostResult>`.
4. Кладет `HostCall` в очередь `hostCalls`.
5. VM корутина ждет `deferred.await()`.
6. Server thread в `ServerComputer.serverTick()` забирает все calls через `drainHostCalls()`.
7. Для каждого call делает `HostCallDispatcher.dispatch(call)`.
8. Полученные `HostResult` возвращаются в VM через `deliverHostResults(results)`.
9. Соответствующий `CompletableDeferred` резолвится, и VM продолжает выполнение.

### 9.2. Ошибки host calls

`HostCallDispatcher` оборачивает dispatch в `try/catch`.

Если при операции была ошибка, он возвращает `HostResult.Failure(call.id, message)`.

Внутри `HostCallManager.awaitHostCall(...)` такой результат превращается в `error(result.message)`, то есть в исключение внутри VM-кода.

Такой результат всплывает обратно в VM как ошибка выполнения. Если программа его не обработает, VM может завершиться с `Crashed(...)`.

## 10. Как работает runtime output

### 10.1. Runtime UI идет через display frames

Это принципиальный момент.

Видимый runtime UI должен рисоваться через `display::*`: ROM/user code обновляет pixel buffer в `DisplayRegistry`, серверный tick flush-ит dirty frames через display sessions, а клиентский Computer screen рендерит `ClientDisplayBuffer`.

Это значит:

- server-to-client output не идет через stdout byte stream;
- VM не предоставляет `terminal`/`stdout` APIs;
- runtime diagnostics не имеют отдельного renderer-а: если программа хочет показать что-то игроку, она должна сама рисовать это через `display::*`.

Сервер для обычного Computer GUI читает display deltas через `flushDisplaySessions()`. VM больше не публикует terminal screen snapshots.

### 10.2. ROM stdio поверх IPC

IPC остается низкоуровневым VM-local transport-ом. Семантика stdin/stdout/stderr появляется только как ROM/process convention:

```text
stdio-v1 <stdin-channel-id> <stdout-channel-id> <stderr-channel-id> <argument>
```

`terminal.ck` открывает каналы, запускает `shell.ck` с таким descriptor-ом, читает keyboard/paste events, пишет line input в stdin channel и рендерит stdout/stderr chunks через `display::*`.

`process::run(path, argument)` и `process::spawn(path, argument)` не знают о stdio на уровне типа. Если `argument` является valid `stdio-v1` descriptor-ом, launch/compile/runtime errors дочернего процесса записываются в descriptor stderr channel. Если descriptor отсутствует или malformed, ошибка остается только в server log и exit code.

### 10.3. Display state

Runtime Computer UI физически представлен display/pixel-buffer state, который затем кодируется в `DisplayFrameDelta`. Workbench live terminal attach сейчас не читает VM terminal snapshot; future live viewer должен подключаться к display sessions, а не reintroduce stdout transport.

## 11. Как запускаются программы

### 11.1. Компиляция

`ComputerProgramCompiler.compile(path, source)`:

1. вызывает `LanguageFrontend(...).compileImage(path, source)`;
2. берет `artifact.image`;
3. собирает все diagnostics уровня `ERROR`;
4. если image не получился или есть ошибки, возвращает `CompiledComputerProgram(program = null, errorMessage = ...)`;
5. проверяет encoded CKIM size против ROM limit профиля;
6. иначе возвращает `CkVmImageComputerProgram(image)`.

### 11.2. Выполнение

`CkVmImageComputerProgram.run(runtime)`:

1. загружает native library из `ckl.vm.native.library`;
2. кодирует `CkVmImage` через `CkVmImageAbi`;
3. создает Rust image VM handle через JNI;
4. циклически вызывает image VM до следующего native signal;
5. на основе сигнала делает следующее:
   - `Halt` -> завершает программу;
   - `Yield` -> вызывает `runtime.yield()` и продолжает, когда opcode support появится в image VM;
   - `Sleep(ticks)` -> вызывает `runtime.sleep(ticks)` и возвращает `Unit` в VM, когда opcode support появится;
   - `WaitEvent(filter)` -> вызывает `runtime.pullEvent(filter)` и возвращает событие в VM, когда event value codec появится;
   - `HostCall(module, function, args)` -> вызывает `RuntimeHostBridge.invoke(...)` и возвращает результат в Rust image VM.

## 12. Memory Model VM

Runtime memory now belongs to the Rust image VM. Kotlin still defines `VmValue` for host-call boundary values and uses `BytecodeModule` as temporary compiler scaffolding, but Kotlin/JVM no longer owns an interpreter heap, frame stack, or instruction pointer.

Ключевые файлы:

- `compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageBackend.kt`
- `compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/image/CkVmImageComputerProgram.kt`
- `compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmRunner.kt`
- `native/ckl-vm/src/image_runner.rs`

Практический вывод: новые runtime features должны добавлять поддержку в `CkVmImage` lowering, CKIM ABI и Rust image runner, а не восстанавливать Kotlin/JVM interpreter.

### 12.1. Какие значения умеет хранить VM

Значения представлены sealed-типом `VmValue`:

- `UnitValue`
- `NullValue`
- `BoolValue`
- `IntValue`
- `LongValue`
- `StringValue`
- `RecordValue(typeName, fields)`

Следовательно, локальная переменная в памяти VM это просто один элемент `VmValue` внутри `locals[slot]`.

### 12.2. Как компилятор привязывает переменную к памяти

При компиляции функции `LanguageFrontend.FunctionCompiler` строит:

- `instructions: MutableList<Instruction>`
- `locals: MutableList<BytecodeLocal>`
- `scopes: ArrayDeque<MutableMap<String, Int>>`

Когда встречается объявление переменной `VariableDeclarationStatement`, компилятор делает следующее:

1. компилирует initializer;
2. берет новый `slot = locals.size`;
3. добавляет в список локалов `BytecodeLocal(statement.name, typeName)`;
4. связывает имя с этим slot через `scopes.last()[statement.name] = slot`;
5. добавляет инструкцию `Instruction.StoreLocal(slot)`.

Именно так имя переменной превращается в индекс ячейки локальной памяти.

### 12.3. Что происходит при `val x = 42`

Допустим, есть такой код:

```ck
pub fun main() {
   val x = 42;
}
```

На уровне компилятора это превращается в последовательность, эквивалентную:

```text
PushInt(42)
StoreLocal(0)
Return
```

Номер `0` здесь примерный: это первый свободный slot в `locals` данной функции.

#### Шаг 1. Создается frame для `main`

При входе в функцию VM вызывает `createFrame(functionIndex, arguments)` и создает:

- `instructionPointer = 0`
- `locals = MutableList(...){ UnitValue }`
- `stack = mutableListOf()`

Состояние примерно такое:

```text
frames = [
  FrameState(
   functionIndex = main,
   instructionPointer = 0,
   locals = [UnitValue],
   stack = []
  )
]
```

#### Шаг 2. Выполняется `PushInt(42)`

VM заходит в `runUntilSignal()` и доходит до ветки:

```kotlin
is Instruction.PushInt -> {
   frame.stack += VmValue.IntValue(instruction.value)
}
```

После этого:

```text
locals = [UnitValue]
stack = [IntValue(42)]
```

#### Шаг 3. Выполняется `StoreLocal(0)`

VM доходит до:

```kotlin
is Instruction.StoreLocal -> {
   ensureLocal(frame, instruction.slot)
   frame.locals[instruction.slot] = frame.pop()
}
```

Что это значит буквально:

1. VM убеждается, что `locals` достаточно длинный;
2. берет верхушку стека через `frame.pop()`;
3. записывает это значение в `locals[0]`.

После этого:

```text
locals = [IntValue(42)]
stack = []
```

Это и есть момент, где переменная реально оказывается в памяти VM.

### 12.4. Что происходит, когда переменная читается

Если дальше есть:

```ck
system::log(x)
```

то использование имени `x` компилируется в `Instruction.LoadLocal(slot)`.

Выполнение буквально такое:

```kotlin
is Instruction.LoadLocal -> {
   frame.stack += frame.locals[instruction.slot]
}
```

То есть значение не копируется в новую «ячейку памяти», а просто берется из `locals[slot]` и кладется на стек вычислений.

### 12.5. Что происходит при `if`

Допустим, есть код:

```ck
if (flag) {
   system::log("yes")
} else {
   system::log("no")
}
```

Компилятор строит примерно такую схему инструкций:

```text
LoadLocal(flagSlot)
JumpIfFalse(elseStart)

PushString("yes")
CallBuiltin("system", "log", 1)
Pop
Jump(end)

elseStart:
PushString("no")
CallBuiltin("system", "log", 1)
Pop

end:
...
```

Что происходит на уровне VM:

1. `LoadLocal(flagSlot)` кладет `BoolValue(true/false)` в `stack`.
2. `JumpIfFalse(target)` делает `frame.pop().asBoolean()`.
3. Если значение `false`, VM просто меняет `instructionPointer = target`.
4. Если значение `true`, исполнение продолжается линейно.

То есть `if` не создает новую память сам по себе. Он:

- читает значение из `locals`;
- временно кладет его в `stack`;
- меняет `instructionPointer`.

### 12.6. Что происходит при `while`

Допустим:

```ck
while (i < 3) {
   system::log("tick")
}
```

Компилятор делает такую схему:

```text
loopStart:
LoadLocal(iSlot)
PushInt(3)
Binary(LESS)
JumpIfFalse(loopEnd)

PushString("tick")
CallBuiltin("system", "log", 1)
Pop
Jump(loopStart)

loopEnd:
...
```

На уровне VM это означает:

1. взять текущее значение `i` из `locals[iSlot]`;
2. положить `IntValue(3)` в стек;
3. `Binary(LESS)` снимет два верхних значения со стека и положит `BoolValue(...)` обратно;
4. `JumpIfFalse(loopEnd)` либо выйдет из цикла, либо оставит поток в теле цикла;
5. `Jump(loopStart)` перекинет `instructionPointer` обратно на начало проверки.

Опять же, цикл здесь это не отдельный runtime-object. Это просто:

- повторное чтение/запись `locals`;
- вычисления через `stack`;
- перепрыгивание `instructionPointer`.

### 12.7. Что происходит при вызове функции

Допустим, есть:

```ck
fun add(a: Int, b: Int): Int {
   return a + b;
}

pub fun main() {
   val result = add(2, 3);
}
```

На уровне bytecode VM вызов функции это не «прыжок внутрь того же контекста», а создание нового `FrameState`.

Когда VM доходит до `Instruction.CallFunction(index, argumentCount)`, она делает:

1. снимает аргументы со стека через `frame.popMany(argumentCount)`;
2. вызывает `createFrame(functionIndex, args)`;
3. пушит новый кадр в `frames`.

Новый кадр создается так:

1. берется описание функции `module.functions[functionIndex]`;
2. создается новый список `locals` нужной длины;
3. аргументы кладутся в первые слоты `locals[index] = value`;
4. `instructionPointer` ставится в `0`;
5. создается новый пустой `stack`.

После этого память выглядит примерно так:

```text
frames = [
  callerFrame(locals = [...], stack = [...]),
  calleeFrame(locals = [IntValue(2), IntValue(3)], stack = [])
]
```

То есть у каждой функции свои собственные `locals` и свой собственный `stack`.

### 12.8. Что происходит при `return`

Когда callee делает `Instruction.Return`, VM вызывает `handleReturn(result)`:

1. текущий frame удаляется из `frames`;
2. если frames пуст, VM завершается;
3. если caller еще есть, результат кладется в `caller.stack += result`;
4. выполнение продолжается уже в caller frame.

То есть память вызываемой функции уничтожается просто удалением ее `FrameState` из стека кадров.

### 12.9. Что происходит со struct/record

Когда программа строит record, VM не выделяет отдельный heap-object своей собственной VM-модели. Она просто создает:

```kotlin
VmValue.RecordValue(typeName, fields)
```

Это значение затем лежит либо:

- в `stack`, если оно только что сконструировано;
- в `locals[slot]`, если его сохранили в переменную;
- в `caller.stack`, если его вернули из функции.

### 12.10. Что важно про `var` и присваивание

В AST у `VariableDeclarationStatement` есть поле `mutable`, то есть язык различает `val` и `var`.

Но в разобранном исполняющем коде важен следующий факт:

- объявление переменной точно приводит к `StoreLocal(slot)`;
- отдельного пользовательского instruction-типа вроде `AssignLocal` в показанном коде нет;
- основная запись в память VM, которую видно напрямую, это именно `StoreLocal`.

То есть при ответе на вопрос «что происходит, когда я инициализировал переменную» фактический низкоуровневый ответ именно такой:

1. initializer вычисляется в `stack`;
2. компилятор уже заранее назначил переменной `slot`;
3. `StoreLocal(slot)` перекладывает верхушку стека в `locals[slot]`.

## 13. Какой API доступен программам внутри VM

API маппится через `RuntimeHostBridge` в `compiler/src/main/kotlin/ck/lang/runtime/RuntimeHostBridge.kt`.

### 13.1. Модуль `system`

Доступные функции:

- `system.computerId(): Int`
- `system.currentTick(): Long`
- `system.label(): String`
- `system.profileName(): String`
- `system.log(text): Unit`
- `system.shutdown(): Unit`
- `system.reboot(): Unit`

`VmSystemApi` дополнительно умеет `queueEvent(name, arguments)`, но в `RuntimeHostBridge` эта операция наружу как функция модуля `system` сейчас не экспортируется. То есть для программ важно различать:

- что умеет объект `ComputerSystemApi` внутри runtime;
- что реально проброшено в язык через `RuntimeHostBridge`.

### 13.2. Модуль `terminal`

Доступные функции:

- `terminal.write(text): Unit`
- `terminal.println(text): Unit`
- `terminal.readln(prompt = ""): String`
- `terminal.clear(): Unit`
- `terminal.setCursor(x, y): Unit`

### 13.3. Модуль `process`

Доступные функции:

- `process.currentDirectory(): String`
- `process.argument(): String`
- `process.changeDirectory(path): Bool`
- `process.run(path): Int`
- `process.run(path, argument): Int`

### 13.4. Модуль `strings`

Доступные функции:

- `strings.trim(text): String`
- `strings.beforeSpace(text): String`
- `strings.afterSpace(text): String`
- `strings.isBlank(text): Bool`

### 13.5. Глобальные builtins

На уровне `Instruction.CallBuiltin` без module name доступны:

- `yield()` -> возвращает `VmSignal.Yield`
- `sleep(ticks)` -> возвращает `VmSignal.Sleep(ticks)`

### 13.6. Модуль `events`

Поддерживается builtin:

- `events.pull(filter?)`

Он превращается в `VmSignal.WaitEvent(filter)`.

### 13.7. Capability checks

Перед вызовом модульной функции `RuntimeHostBridge.ensureCapability(moduleName)` проверяет, разрешена ли capability в `runtime.profile.allowedCapabilities`.

Соответствие сейчас такое:

- `system` -> `SYSTEM`
- `terminal` -> `TERMINAL`
- `events` -> `EVENTS`
- `process` -> `SYSTEM`

Если capability не разрешена, будет ошибка вида:

```text
Capability <module> is not allowed for this computer profile.
```

### 13.8. Что пока не реализовано полноценно

`VmRuntime` содержит поля:

- `redstoneApi = object : ComputerRedstoneApi {}`
- `peripheralsApi = object : ComputerPeripheralApi {}`

Но в `RuntimeHostBridge` нет экспорта модулей `redstone` и `peripherals`. То есть соответствующие capability могут быть в профиле, но языкового API для них в показанном коде пока нет.

## 14. Как работает shell и пользовательские команды

ROM-файл `shell.ck` показывает, как предполагается работа системы изнутри:

- shell печатает баннер;
- читает строку через `stdio.ck` helper поверх IPC stdin channel;
- builtin-команды обрабатывает сам:
  - `help`
  - `cd`
  - `reboot`
  - `shutdown`
Команда `cd` использует `process.changeDirectory(...)`.

Команда `pwd` запускает `bin/pwd.ck`, который печатает `process.currentDirectory()`.

То есть shell здесь не встроен в движок VM. Это обычная программа на том же языке, которая использует тот же API, что и пользовательский код.

## 15. Display frames и синхронизация с клиентом

Во время `RuntimeDeviceImpl.serverTick()` после обработки host calls вызывается `flushDisplaySessions()`:

1. `DisplayRegistry` отдает dirty pixel-buffer deltas;
2. сервер проверяет, что display session еще привязана к player/container/device/display;
3. dirty frame отправляется как `FrameDeltaClientMessage`;
4. клиент применяет frame к `ClientDisplayBuffer` и рендерит его в Computer screen.

VM не создает terminal snapshot и не рассылает stdout bytes. Видимый текст shell существует только потому, что ROM `terminal.ck` сам превращает stdout/stderr IPC chunks в draw calls `display::*`.

## 16. Что именно ограничивает ресурсы

Сводка по реальным ограничениям:

### 16.1. CPU steps per slice

Есть. Ограничивается `profile.resources.cpu.maxStepsPerSlice`.

### 16.2. Частота кооперативной отдачи управления

Есть. Основной CPU-лимит задается step budget через `profile.resources.cpu.maxStepsPerSlice`; фиксированный turn cap используется только как safety guard executor'а.

### 16.3. Event queue size

Есть. Ограничивается `profile.resources.queues.eventQueueSlots`, при переполнении используется `DROP_OLDEST`.

### 16.4. Размер display pixel buffer

Есть. Ограничивается display profile/resources и client display session dimensions.

### 16.5. Capability-based API access

Есть. `RuntimeHostBridge.ensureCapability(...)` блокирует вызовы модулей, если capability не разрешена профилем.

### 16.6. Ограничение числа host calls

Есть. Ограничивается `profile.resources.queues.hostCallQueueSlots`. При переполнении новый host call отклоняется с ошибкой `Host call queue is full (...)`.

### 16.7. Ограничение числа процессов

Явного лимита нет. `process.run(...)` просто синхронно запускает другую программу на том же VM-контексте.

### 16.8. Ограничение памяти программы

Есть. Ограничивается `profile.resources.memory.vmRamBytes`. Сейчас это логическая оценка текущего VM state: locals, operand stack, strings и record values. При превышении VM падает с ошибкой `VM out of memory ...`.

### 16.9. Ограничение размера программы

Есть. Ограничивается `profile.resources.storage.programRomBytes`. Проверяется по размеру скомпилированного `BytecodeModule`, а не по размеру исходника. Слишком большая boot- или child-программа отклоняется до старта выполнения.

## 17. Что стоит понимать как важные нюансы реализации

### 17.1. Runtime display и process I/O устроены по-разному

- runtime UI работает через `DisplayRegistry`/display frame deltas;
- IPC/stdio channels являются локальным process convention и сами по себе ничего не рендерят.

Это разное поведение и разная стоимость операций.

### 17.2. Shell это не системная магия, а обычная программа

Shell здесь не встроен в движок VM. Это обычная программа на том же языке, которая использует тот же API, что и пользовательский код.

### 17.3. `system.queueEvent(...)` есть в runtime API, но не экспортирован в bridge

Если смотреть только на `VmSystemApi`, можно решить, что программы могут свободно кидать события. Но по факту надо смотреть `RuntimeHostBridge`: именно он определяет, что доступно языку.

### 17.4. CPU и yield теперь разделены на две разные семантики

- явный builtin `yield()` возвращает в программу `unit` после возобновления;
- служебная пауза scheduler по instruction budget ничего в стек не пушит.

Это важно, иначе таймслайс-паузы портили бы стек значений VM.

## 18. Какие тесты подтверждают поведение

### `mod/src/test/kotlin/ck/mod/computer/vm/VmRuntimeSupportTest.kt`

Подтверждает, что:

- event text decoder работает как раньше;
- host call queue ограничена и отклоняет новые вызовы при переполнении.

### `mod/src/test/kotlin/ck/mod/computer/vm/BackgroundComputerVmTest.kt`

Подтверждает, что:

- ошибка `program ROM exceeded` доходит до `VmState.Crashed` как понятное сообщение при boot.

### `mod/src/test/kotlin/ck/mod/application/runtime/ComputerProgramSupportTest.kt`

Подтверждает, что:

- compile support отклоняет программу, если скомпилированный bytecode превышает `programRomBytes`.

### `compiler/src/test/kotlin/ck/lang/runtime/LanguageRuntimeTest.kt`

Подтверждает, что:

- execution window берется из `profile.resources.cpu.maxStepsPerSlice`;
- VM RAM limit реально ограничивает выполнение;
- существующие host/sleep/yield сценарии не сломаны новой моделью.

## 19. Короткий итог

Фактически VM в Compukter-Kraft это:

- кооперативная bytecode VM;
- исполняемая на фоновой корутине;
- двигаемая серверными тиками через slice permits;
- ограниченная по времени slice и по размеру event queue;
- работающая с display pixel buffer через `display::*`, а с process I/O через VM-local `ipc` conventions;
- запускающая BIOS и shell как обычные программы на том же языке;
- публикующая внутрь программ модульный API через `RuntimeHostBridge`.

Если нужно смотреть на поведение VM в порядке важности, то самые центральные файлы такие:

1. `mod/src/main/kotlin/ck/mod/computer/vm/BackgroundComputerVm.kt`
2. `mod/src/main/kotlin/ck/mod/computer/ServerComputer.kt`
3. `mod/src/main/kotlin/ck/mod/computer/vm/VmProcessApi.kt`
4. `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
5. `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
6. `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`

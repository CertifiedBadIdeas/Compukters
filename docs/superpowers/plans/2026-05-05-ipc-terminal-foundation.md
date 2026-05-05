# IPC Terminal Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the low-level IPC, event-payload, and async process foundation needed to replace ROM `terminal::*` usage with an in-VM terminal/shell stack.

**Architecture:** Runtime provides only generic `ipc`, raw event payload access, and `process::spawn` / `process::wait`. ROM code defines the stdio convention with channel ids passed in the process argument string. `terminal.ck` owns input handling and framebuffer rendering, while `shell.ck` and commands use `stdio.ck` helpers.

**Tech Stack:** Kotlin, coroutines, CKL compiler/runtime, Gradle multi-module tests, ROM `.ck` resources.

---

## Ground rules

- Work in `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/ipc-terminal-foundation`.
- Keep commits small; every task ends with a commit.
- Use TDD: write the failing test first, run the focused test, implement, run the focused test again.
- Do not add runtime `stdin`, `stdout`, `stderr`, TTY, PTY, prompt, cursor, shell history, or line editing concepts.
- `terminal` and `stdout` builtins may remain for backward compatibility during this feature, but ROM scripts listed in `rom.index` must stop calling `terminal::*` by the final task.
- If adding `Instruction` variants becomes necessary, also update `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt` ROM estimation.

## File map

- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`: add builtin signatures for `ipc`, `process::spawn`, `process::wait`, event payload helpers, and string helpers required by ROM parsing/rendering.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`: add `DeviceIpcApi`, `DeviceEventApi`, and async process methods.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`: dispatch new builtins.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`: allow non-`pull` `events::*` calls to reach the host bridge.
- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistry.kt`: per-VM bounded IPC channels.
- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmIpcApi.kt`: runtime adapter for `DeviceIpcApi`.
- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/EventPayloadStore.kt`: per-runtime event id and typed payload access.
- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmEventApi.kt`: runtime adapter for `DeviceEventApi`.
- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`: child process table, pid allocation, coroutine launch, and wait completion.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`: delegate `spawn`, `wait`, and `run` to `VmProcessManager`.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`: own shared IPC/process/event managers and wire APIs into every runtime.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/*.ck`: add `stdio.ck`, add `terminal.ck`, rewrite `shell.ck` and command scripts.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/rom.index`: include `stdio.ck` and `terminal.ck`.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/boot.ck`: run `terminal.ck`.
- Modify `docs/LANGUAGE.md`: document `ipc`, event payload helpers, `spawn` / `wait`, and ROM stdio convention.

## Concrete ROM convention

Use positional arguments for the first implementation because CKL already has `strings::beforeSpace` and `strings::afterSpace`:

```text
<stdin-channel-id> <stdout-channel-id> <stderr-channel-id> <command-argument>
```

Example: `1 2 3 /docs/readme.txt`.

`stdio.ck` reads the first three tokens with `strings::beforeSpace` / `strings::afterSpace`, converts them with the new `strings::toInt(text) -> Int`, and returns the remaining text as the user argument. Runtime never assigns meaning to those positions.

---

### Task 1: Builtin surface and compiler visibility

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt`

- [ ] **Step 1: Write the failing compiler visibility test**

Add this test near the existing runtime bridge tests in `LanguageRuntimeTest`:

```kotlin
@Test
fun compilesIpcSpawnWaitEventPayloadAndStringHelpers() {
    val artifact =
        frontend.compile(
            "surface.ck",
            """
            pub fun main() {
                val ch: Int = ipc::open();
                ipc::write(ch, "42");
                val immediate: String = ipc::tryRead(ch);
                ipc::write(ch, immediate);
                val blocking: String = ipc::read(ch);
                ipc::close(ch);

                val child: Int = process::spawn("child.ck", "1 2 3 arg");
                val code: Int = process::wait(child);

                val event: Event = events::pull("char");
                val count: Int = events::argCount(event);
                val text: String = events::argString(event, 0);
                val key: Int = events::argInt(event, 0);
                val repeated: Bool = events::argBool(event, 1);

                val parsed: Int = strings::toInt("123");
                val length: Int = strings::length("abc");
                val first: String = strings::charAt("abc", 0);

                terminal::println(blocking + text + first + code + count + key + parsed + length + repeated);
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.compilesIpcSpawnWaitEventPayloadAndStringHelpers"`

Expected: FAIL with diagnostics mentioning unknown `ipc`, `process::spawn`, `process::wait`, `events::argCount`, `events::argString`, `events::argInt`, `events::argBool`, `strings::toInt`, `strings::length`, or `strings::charAt`.

- [ ] **Step 3: Add builtin signatures and runtime interfaces**

In `DeviceVmModels.kt`, add `IPC` to `DeviceCapability` and add bounded IPC resources:

```kotlin
enum class DeviceCapability {
    TERMINAL,
    DISPLAY,
    FILESYSTEM,
    EVENTS,
    SYSTEM,
    IPC,
    REDSTONE,
    PERIPHERALS,
    IDE,
}

data class DeviceQueueResources(
    val eventQueueSlots: Int,
    val hostCallQueueSlots: Int = eventQueueSlots,
    val ipcChannelBytes: Int = 16 * 1024,
)
```

In `DeviceRuntime.kt`, extend `DeviceRuntime` and add interfaces:

```kotlin
interface DeviceRuntime {
    val profile: DeviceProfile
    val system: DeviceSystemApi
    val terminal: DeviceTerminalApi
    val display: DeviceDisplayApi
        get() = NoopDeviceDisplayApi
    val stdio: DeviceStdioApi
    val filesystem: DeviceFileSystemApi
    val process: DeviceProcessApi
    val ipc: DeviceIpcApi
    val events: DeviceEventApi
    val redstone: DeviceRedstoneApi
    val peripherals: DevicePeripheralApi

    suspend fun pullEvent(filter: String? = null): VmEvent

    suspend fun sleep(ticks: Long)

    suspend fun yield()
}

interface DeviceIpcApi {
    suspend fun open(): Int
    suspend fun write(channelId: Int, text: String)
    suspend fun read(channelId: Int): String
    fun tryRead(channelId: Int): String
    fun close(channelId: Int)
}

interface DeviceEventApi {
    fun capture(arguments: List<Any?>): Pair<Int, Int>
    fun argCount(eventId: Int): Int
    fun argInt(eventId: Int, index: Int): Int
    fun argBool(eventId: Int, index: Int): Boolean
    fun argString(eventId: Int, index: Int): String
}

interface DeviceProcessApi {
    val workingDirectory: String
    val argument: String

    suspend fun changeDirectory(path: String): Boolean

    suspend fun spawn(path: String): Int = spawn(path, "")

    suspend fun spawn(path: String, argument: String): Int

    suspend fun wait(pid: Int): Int

    suspend fun run(path: String): Int = run(path, "")

    suspend fun run(path: String, argument: String): Int = wait(spawn(path, argument))
}
```

In `LanguageBuiltins.kt`, add:

```kotlin
BuiltinModule(
    name = "ipc",
    documentation = "Low-level in-VM IPC channels.",
    origin = ModuleOrigin.BASE_VM,
    functions =
        listOf(
            BuiltinFunction("open", emptyList(), "Int", "Creates an IPC channel."),
            BuiltinFunction("write", listOf("Int", "String"), "Unit", "Writes text to a channel."),
            BuiltinFunction("read", listOf("Int"), "String", "Blocks until channel text is available."),
            BuiltinFunction("tryRead", listOf("Int"), "String", "Returns available channel text or an empty string."),
            BuiltinFunction("close", listOf("Int"), "Unit", "Closes a channel."),
        ),
),
```

Extend `events` functions with `argCount`, `argInt`, `argBool`, and `argString`. Extend `process` functions with `spawn(String)`, `spawn(String, String)`, and `wait(Int)`. Extend `strings` functions with `toInt(String)`, `length(String)`, and `charAt(String, Int)`. Extend the `Event` type fields to `name: String`, `id: Int`, and `argCount: Int`.

- [ ] **Step 4: Run test to verify it passes compilation**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.compilesIpcSpawnWaitEventPayloadAndStringHelpers"`

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt
git commit -m "feat: declare ipc and async process builtins"
```

---

### Task 2: Event payload runtime bridge

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/EventPayloadStore.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmEventApi.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/EventPayloadStoreTest.kt`

- [ ] **Step 1: Write failing unit tests for payload decoding**

Create `EventPayloadStoreTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventPayloadStoreTest {
    @Test
    fun storesPrimitiveArgumentsAndDecodesTextBuffers() {
        val store = EventPayloadStore(maxEvents = 8)
        val id = store.capture(listOf(65, true, "plain", "Ж".toByteArray(), ByteBuffer.wrap("paste".toByteArray()))).first

        assertEquals(5, store.argCount(id))
        assertEquals(65, store.argInt(id, 0))
        assertTrue(store.argBool(id, 1))
        assertEquals("plain", store.argString(id, 2))
        assertEquals("Ж", store.argString(id, 3))
        assertEquals("paste", store.argString(id, 4))
        assertFalse(store.argBool(id, 0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.device.vm.EventPayloadStoreTest"`

Expected: FAIL because `EventPayloadStore` does not exist.

- [ ] **Step 3: Implement `EventPayloadStore` and `VmEventApi`**

Implement `EventPayloadStore` with monotonically increasing ids, bounded retention of recent payloads, `argCount`, `argInt`, `argBool`, and UTF-8 `argString` for `String`, `ByteArray`, `ByteBuffer`, `Int`, and `Boolean`. Return `0`, `false`, or `""` for missing ids, wrong types, and out-of-range indexes.

`VmEventApi` should delegate every `DeviceEventApi` method to the shared `EventPayloadStore`.

- [ ] **Step 4: Route event helper calls through the host bridge**

In `LanguageRuntime.kt`, change the builtin special case so only `events::pull` creates `VmSignal.WaitEvent`; all other `events::*` calls produce `VmSignal.HostCall`.

In `RuntimeHostBridge.kt`, add:

```kotlin
"events" -> invokeEvents(functionName, arguments)
```

and change `fromEvent` to include captured payload metadata:

```kotlin
fun fromEvent(event: VmEvent): VmValue.RecordValue {
    val (id, argCount) = runtime.events.capture(event.arguments)
    return VmValue.RecordValue(
        typeName = "Event",
        fields = mapOf(
            "name" to VmValue.StringValue(event.name),
            "id" to VmValue.IntValue(id),
            "argCount" to VmValue.IntValue(argCount),
        ),
    )
}
```

`invokeEvents` must dispatch `argCount`, `argInt`, `argBool`, and `argString` using the event id from the `Event` record.

- [ ] **Step 5: Add a runtime bridge test**

Add this test to `LanguageRuntimeTest`:

```kotlin
@Test
fun exposesPulledEventPayloadToCkl() {
    val artifact =
        frontend.compile(
            "event-payload.ck",
            """
            pub fun main() {
                val event: Event = events::pull("char");
                terminal::println(event.name + ":" + event.argCount);
                terminal::println(events::argString(event, 0));
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )

    val runtime = RecordingRuntime(events = listOf(VmEvent("char", listOf("x".toByteArray()))))
    runBlocking {
        BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
    }

    assertEquals(listOf("char:1", "x"), runtime.lines)
}
```

Update `RecordingRuntime` to accept an `events: List<VmEvent>` constructor argument, to expose a simple in-memory `DeviceEventApi`, and to return those events from `pullEvent`.

- [ ] **Step 6: Wire event API in `BackgroundDeviceVm` and run tests**

Add one `EventPayloadStore` owned by `BackgroundDeviceVm`, pass `VmEventApi(eventPayloadStore)` to every `VmRuntime`, and run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.exposesPulledEventPayloadToCkl" :core:test --tests "ru.lazyhat.compukterkraft.core.device.vm.EventPayloadStoreTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/EventPayloadStore.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmEventApi.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/EventPayloadStoreTest.kt
git commit -m "feat: expose raw event payloads to ckl"
```

---

### Task 3: IPC channel registry and runtime bridge

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistry.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmIpcApi.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistryTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`

- [ ] **Step 1: Write failing IPC registry tests**

Create `IpcChannelRegistryTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IpcChannelRegistryTest {
    @Test
    fun tryReadReturnsAvailableTextWithoutBlocking() = runTest {
        val registry = IpcChannelRegistry(maxBufferedBytesPerChannel = 16)
        val channel = registry.open()

        registry.write(channel, "hello")

        assertEquals("hello", registry.tryRead(channel))
        assertEquals("", registry.tryRead(channel))
    }

    @Test
    fun readSuspendsUntilTextIsWritten() = runTest {
        val registry = IpcChannelRegistry(maxBufferedBytesPerChannel = 16)
        val channel = registry.open()

        val read = async { registry.read(channel) }
        registry.write(channel, "ready")

        assertEquals("ready", read.await())
    }

    @Test
    fun closeWakesReadersWithEmptyText() = runTest {
        val registry = IpcChannelRegistry(maxBufferedBytesPerChannel = 16)
        val channel = registry.open()

        val read = async { registry.read(channel) }
        registry.close(channel)

        assertEquals("", read.await())
    }

    @Test
    fun enforcesBoundedBuffering() = runTest {
        val registry = IpcChannelRegistry(maxBufferedBytesPerChannel = 4)
        val channel = registry.open()

        assertFailsWith<IllegalStateException> {
            registry.write(channel, "12345")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.device.vm.IpcChannelRegistryTest"`

Expected: FAIL because `IpcChannelRegistry` does not exist.

- [ ] **Step 3: Implement `IpcChannelRegistry` and `VmIpcApi`**

Implement `IpcChannelRegistry` with:

- `open(): Int` allocating positive channel ids.
- `write(channelId, text)` appending to a per-channel FIFO buffer and failing with `IllegalStateException("IPC channel buffer limit exceeded")` if the buffered UTF-8 byte count would exceed the channel quota.
- `read(channelId): String` suspending until text is available or the channel is closed.
- `tryRead(channelId): String` returning and clearing buffered text, or `""` when no text is available.
- `close(channelId)` marking the channel closed and waking suspended readers.

Use `Mutex` and a per-channel `Channel<Unit>` or `CompletableDeferred` wakeup mechanism. Invalid channel ids should behave like closed channels for reads and throw `IllegalArgumentException("Unknown IPC channel: $channelId")` for writes.

`VmIpcApi` delegates directly to the registry.

- [ ] **Step 4: Add host bridge dispatch and compiler runtime test**

In `RuntimeHostBridge.invoke`, add `"ipc" -> invokeIpc(functionName, arguments)`. `invokeIpc` returns `VmValue.IntValue`, `VmValue.StringValue`, or `VmValue.UnitValue` according to the builtin signature.

Add this test to `LanguageRuntimeTest`:

```kotlin
@Test
fun executesIpcBuiltinsThroughRuntimeBridge() {
    val artifact =
        frontend.compile(
            "ipc.ck",
            """
            pub fun main() {
                val channel: Int = ipc::open();
                ipc::write(channel, "hello");
                terminal::println(ipc::tryRead(channel));
                ipc::write(channel, "again");
                terminal::println(ipc::read(channel));
                ipc::close(channel);
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )

    val runtime = RecordingRuntime()
    runBlocking {
        BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
    }

    assertEquals(listOf("hello", "again"), runtime.lines)
}
```

Give `RecordingRuntime` an in-memory `DeviceIpcApi` backed by a small fake registry.

- [ ] **Step 5: Wire `ipc` into `BackgroundDeviceVm` and capability filtering**

Add one `IpcChannelRegistry(profile.resources.queues.ipcChannelBytes)` owned by `BackgroundDeviceVm`. Pass `VmIpcApi(ipcRegistry)` into every `VmRuntime`. Include `ipc` in `createRuntimeRegistryProfile()` only when `DeviceCapability.IPC` is allowed. Map `ipc` to `DeviceCapability.IPC` in `RuntimeHostBridge.ensureCapability`.

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.executesIpcBuiltinsThroughRuntimeBridge" :core:test --tests "ru.lazyhat.compukterkraft.core.device.vm.IpcChannelRegistryTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistry.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmIpcApi.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/IpcChannelRegistryTest.kt
git commit -m "feat: add in-vm ipc channels"
```

---

### Task 4: Async process manager, spawn, and wait

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Write failing runtime bridge test for `spawn` and `wait`**

Add this test to `LanguageRuntimeTest`:

```kotlin
@Test
fun executesSpawnAndWaitThroughRuntimeBridge() {
    val artifact =
        frontend.compile(
            "spawn.ck",
            """
            pub fun main() {
                val pid: Int = process::spawn("child.ck", "arg");
                terminal::println("pid=" + pid);
                terminal::println("code=" + process::wait(pid));
            }
            """.trimIndent(),
        )

    assertTrue(
        artifact.analysis.diagnostics.none { it.severity == FrontendSeverity.ERROR },
        artifact.analysis.diagnostics.joinToString { it.message },
    )

    val runtime = RecordingRuntime(spawnPid = 11, waitCode = 7)
    runBlocking {
        BytecodeComputerProgram(requireNotNull(artifact.module)).run(runtime)
    }

    assertEquals(listOf("pid=11", "code=7"), runtime.lines)
}
```

Update `RecordingRuntime.process` to record `spawn` and `wait` calls.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.executesSpawnAndWaitThroughRuntimeBridge"`

Expected: FAIL because the host bridge does not dispatch `spawn` and `wait`.

- [ ] **Step 3: Implement bridge dispatch**

In `RuntimeHostBridge.invokeProcess`, add:

```kotlin
"spawn" -> {
    VmValue.IntValue(
        when (arguments.size) {
            1 -> runtime.process.spawn(arguments[0].asString())
            2 -> runtime.process.spawn(arguments[0].asString(), arguments[1].asString())
            else -> error("Unsupported process.spawn arity ${arguments.size}")
        },
    )
}

"wait" -> {
    VmValue.IntValue(runtime.process.wait(arguments[0].asInt()))
}
```

- [ ] **Step 4: Write failing VM integration test for concurrent parent and child**

Add a `BackgroundDeviceVmTest` case with firmware that opens separate child input/output channels, spawns `boot.ck`, writes `parent-` to the child input, reads child output, waits, and prints the combined result. Seed `boot.ck` as a workspace program that reads the input channel and writes `child-` to the output channel. The assertion should confirm the final terminal snapshot contains `parent-child-code=0`.

Use CKL sources:

```ck
pub fun main() {
    val childInput: Int = ipc::open();
    val childOutput: Int = ipc::open();
    val pid: Int = process::spawn("boot.ck", childInput + " " + childOutput + " 0");
    ipc::write(childInput, "parent-");
    val text: String = ipc::read(childOutput);
    val code: Int = process::wait(pid);
    terminal::println(text + "code=" + code);
}
```

and child:

```ck
pub fun main() {
    val inputText: String = strings::beforeSpace(process::argument());
    val rest1: String = strings::afterSpace(process::argument());
    val outputText: String = strings::beforeSpace(rest1);
    val input: Int = strings::toInt(inputText);
    val output: Int = strings::toInt(outputText);
    ipc::write(output, ipc::read(input) + "child-");
}
```

- [ ] **Step 5: Implement `VmProcessManager` and refactor `VmProcessApi`**

`VmProcessManager` owns:

- `AtomicInteger` pid allocation starting at `2`.
- `ConcurrentHashMap<Int, CompletableDeferred<Int>>` exit codes.
- `spawn(path, argument, workingDirectory, runtimeCreator): Int` launching a child coroutine in the existing VM scope.
- `wait(pid): Int` awaiting the stored deferred or returning `1` for unknown pid.

Child execution must reuse the existing `VmProcessApi.run` behavior for loading, compiling, diagnostic printing, and returning `0` or `1`. Move that logic into a private `execute(path, argument, workingDirectory)` helper so both `spawn` and `run` use the same semantics. Keep `process::run` implemented as `wait(spawn(path, argument))`.

Wire `VmProcessManager` into `BackgroundDeviceVm`, pass it to every `VmProcessApi`, and cancel child jobs when `BackgroundDeviceVm.stopInternal` stops the VM.

- [ ] **Step 6: Run process tests**

Run:

```bash
./gradlew :compiler:test --tests "ru.lazyhat.compukterkraft.lang.runtime.LanguageRuntimeTest.executesSpawnAndWaitThroughRuntimeBridge" :core:test --tests "ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest"
```

Expected: PASS, including existing missing-program and compile-error `process::run` tests.

- [ ] **Step 7: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
git commit -m "feat: add async ckl process spawning"
```

---

### Task 5: ROM stdio library and command migration

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/stdio.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/shell.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/ls.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/pwd.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/mkdir.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/rmdir.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/nano.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/rom.index`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`

- [ ] **Step 1: Add failing ROM compile expectation for no legacy terminal calls**

In `RomScriptCompileTest`, add a test that loads each `rom.index` resource and asserts the source does not contain `terminal::`. Exclude no ROM script from this assertion.

- [ ] **Step 2: Run ROM tests to verify failure**

Run: `./gradlew :v1_21_1-neoforge:test --tests "ru.lazyhat.compukterkraft.impl.RomScriptCompileTest"`

Expected: FAIL with existing `shell.ck` or command files containing `terminal::`.

- [ ] **Step 3: Add `stdio.ck`**

Create a public CKL library that parses the positional convention and exposes helpers:

```ck
pub struct Stdio {
    pub val input: Int
    pub val output: Int
    pub val error: Int
    pub val argument: String
}

pub fun fromArgument(raw: String): Stdio {
    val inputText: String = strings::beforeSpace(raw)
    val rest1: String = strings::afterSpace(raw)
    val outputText: String = strings::beforeSpace(rest1)
    val rest2: String = strings::afterSpace(rest1)
    val errorText: String = strings::beforeSpace(rest2)
    val userArgument: String = strings::afterSpace(rest2)
    return Stdio(strings::toInt(inputText), strings::toInt(outputText), strings::toInt(errorText), userArgument)
}

pub fun write(ctx: Stdio, text: String) {
    ipc::write(ctx.output, text)
}

pub fun println(ctx: Stdio, text: String) {
    ipc::write(ctx.output, text + "\n")
}

pub fun error(ctx: Stdio, text: String) {
    ipc::write(ctx.error, text + "\n")
}

pub fun readLine(ctx: Stdio): String {
    return ipc::read(ctx.input)
}
```

- [ ] **Step 4: Rewrite ROM scripts to use `stdio.ck`**

Use selective imports:

```ck
import "stdio.ck" { Stdio, fromArgument, write, println, error, readLine };
```

Migration rules:

- `terminal::println(text)` becomes `println(ctx, text)`.
- `terminal::write(text)` becomes `write(ctx, text)`.
- `terminal::readln(prompt)` becomes `write(ctx, prompt); readLine(ctx)`.
- Each `main` starts with `val ctx: Stdio = fromArgument(process::argument())`.
- Commands use `ctx.argument` instead of raw `process::argument()` for user arguments.
- `shell.ck` passes `ctx.input`, `ctx.output`, and `ctx.error` to child commands.

- [ ] **Step 5: Update `rom.index`**

Add `stdio.ck` to `rom.index` before scripts that import it.

- [ ] **Step 6: Run ROM tests**

Run: `./gradlew :v1_21_1-neoforge:test --tests "ru.lazyhat.compukterkraft.impl.RomScriptCompileTest"`

Expected: PASS. The no-legacy-terminal assertion passes for every file in `rom.index`.

- [ ] **Step 7: Commit**

Run:

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt
git commit -m "feat: move rom commands to ipc stdio"
```

---

### Task 6: In-VM terminal program and boot cutover

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/boot.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/rom.index`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`

- [ ] **Step 1: Write failing ROM compile and boot tests**

Extend ROM tests to assert `rom.index` includes `terminal.ck` and `boot.ck` contains `terminal.ck`.

Add a core VM integration test that boots a firmware/user workspace, attaches a display, sends a `char` event for `p`, `w`, `d`, and Enter, runs ticks, then asserts at least one display frame is emitted. The test does not need OCR; it verifies terminal output reaches the framebuffer path.

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest" :v1_21_1-neoforge:test --tests "ru.lazyhat.compukterkraft.impl.RomScriptCompileTest"
```

Expected: FAIL because `terminal.ck` does not exist and `boot.ck` still runs `shell.ck`.

- [ ] **Step 3: Implement `terminal.ck` input/output loop**

Create `terminal.ck` with these responsibilities:

- Open input, output, and error IPC channels.
- Spawn `shell.ck` with the positional stdio argument string.
- Wait for a display with `display::primary()` and `events::pull("display_attach")`.
- Poll `ipc::tryRead(output)` and `ipc::tryRead(error)`.
- Pull raw events and convert `char` / `paste` payloads to input channel text.
- Send `"\n"` to the input channel when key code `257` arrives from a `key` event.
- Render output into the framebuffer using `display::clear`, `display::fillRect`, `display::setPixel`, and `display::present`.

Keep the first renderer intentionally simple and deterministic: clear the screen, draw a green cell for each visible non-newline character, advance by 6x9 cells, wrap at display width, and scroll by keeping only the visible tail of output in one `String` buffer. This proves the full in-VM path without adding a host text renderer.

- [ ] **Step 4: Update boot and ROM index**

Change `boot.ck` to:

```ck
pub fun main() {
    process::run("terminal.ck")
}
```

Add `terminal.ck` to `rom.index`.

- [ ] **Step 5: Run tests**

Run:

```bash
./gradlew :core:test --tests "ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest" :v1_21_1-neoforge:test --tests "ru.lazyhat.compukterkraft.impl.RomScriptCompileTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/boot.ck modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/rom.index modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt
git commit -m "feat: boot in-vm terminal program"
```

---

### Task 7: Documentation and full validation

**Files:**
- Modify: `docs/LANGUAGE.md`
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Update docs**

Document:

- `ipc::open`, `ipc::write`, `ipc::read`, `ipc::tryRead`, `ipc::close`.
- `process::spawn` and `process::wait`.
- `events::argCount`, `events::argInt`, `events::argBool`, `events::argString`.
- `strings::toInt`, `strings::length`, `strings::charAt`.
- ROM convention: first three process argument tokens are input, output, and error channel ids by CKL convention only.
- `terminal.ck` owns terminal rendering; Runtime does not provide stdin/out/err.

- [ ] **Step 2: Run focused module validation**

Run:

```bash
./gradlew :compiler:test :core:test :v1_21_1-neoforge:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run full validation**

Run:

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

Run:

```bash
git add docs/LANGUAGE.md docs/ARCHITECTURE.md
git commit -m "docs: document ipc terminal runtime model"
```

---

## Final review checklist

- `grep -R "terminal::" modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom` returns no matches.
- Runtime has no `stdin`, `stdout`, `stderr`, TTY, PTY, prompt, shell history, or line editing ownership.
- `ipc` channels are bounded and per `BackgroundDeviceVm`.
- `process::spawn` creates CKL child coroutines, not OS threads.
- `process::wait` suspends the caller without stopping sibling CKL processes.
- CKL can read event payloads for `char`, `paste`, `key`, and display events.
- `boot.ck` starts `terminal.ck`.
- `./gradlew test` is successful before completion is claimed.
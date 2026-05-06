# План реализации удаления VM Stdio/Terminal

> **Для agentic workers:** REQUIRED SUB-SKILL: используйте superpowers:subagent-driven-development (рекомендуется) или superpowers:executing-plans для выполнения плана task-by-task. Шаги используют checkbox (`- [ ]`) для tracking.

**Цель:** Удалить CKL `terminal`/`stdout` runtime APIs и VM-side stdio plumbing, сохранив child process errors через tagged VM-local stderr channels, которые программы рендерят сами.

**Архитектура:** Visible runtime output остаётся display-only и program-owned. `process::run`/`spawn` сохраняют текущие signatures; runtime decode-ит tagged `stdio-v1 <stdin> <stdout> <stderr> <argument>` descriptor из argument и пишет child loader/compiler/runtime errors в stderr IPC channel, если он есть. Terminal/stdout builtins, bridge handlers, VM APIs и VM-owned screen-buffer plumbing удаляются после перевода bundled firmware/ROM scripts.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, CKL ROM/firmware scripts, Kotlin test.

---

## Worktree и baseline

Implementation worktree создан и baseline проверен:

- Worktree: `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/vm-stdio-removal`
- Branch: `feature/vm-stdio-removal`
- Baseline command: `./gradlew test`
- Baseline result: `BUILD SUCCESSFUL` with 31 actionable tasks.

Все команды ниже выполнять из `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/vm-stdio-removal`.

## File map

### Compiler/runtime API

- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
  - удалить `BuiltinModule("terminal", ...)` и `BuiltinModule("stdout", ...)`.
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
  - удалить `terminal`/`stdout` dispatch branches и helper methods.
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
  - удалить `DeviceRuntime.terminal`, `DeviceRuntime.stdio`, `DeviceTerminalApi`.
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceStdioApi.kt`.

### Core VM

- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/StdioDescriptor.kt`
  - parse only tagged `stdio-v1` descriptors for runtime process stderr.
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/StdioDescriptorTest.kt`.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
  - удалить terminal/stdout constructor dependencies/properties.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
  - удалить `DeviceTerminalApi` dependency.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
  - decode tagged stdio descriptor и писать process errors в stderr IPC.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
  - удалить VM-owned terminal `ScreenBuffer`, `ComputerStdioBroadcaster`, `ScreenBufferVtSink`, `VmTerminalApi` construction.
- Delete when unused:
  - `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmTerminalApi.kt`
  - `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcaster.kt`
  - `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ScreenBufferVtSink.kt`
  - `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/CursorTracker.kt`
    - corresponding tests listed in Task 6.

### Runtime device / Workbench snapshot path

- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt`
  - удалить `readScreenSnapshot()` and `forceScreenSnapshot()` из `DeviceVmHandle`, если call sites can be removed.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt`
  - удалить `RuntimeDeviceScreen`, если Workbench snapshot path становится no-op/removable.
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
  - удалить `syncScreen()` и `lastScreenSnapshot`, если больше не нужны.
- Modify common Workbench snapshot message/menu/block files listed in Task 7 to keep Workbench attach-terminal disabled without VM screen snapshots.

### Bundled firmware/ROM scripts

- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/stdio.ck`
  - encode and parse `stdio-v1` only.
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/shell.ck`
  - use tagged stdio helpers for external command execution.
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/boot.ck` to keep launching `terminal.ck` after the stdio convention changes.
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
  - remove `terminal::println` and render boot status/errors through display-owned CKL code.
- Audit and update all bundled ROM tools under `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/*.ck`.

### Tests/docs

- Modify compiler frontend/runtime/IDE tests that mention `terminal` or `stdout`.
- Modify core VM tests that assert `forceScreenSnapshot()` output.
- Modify NeoForge ROM/Firmware tests.
- Modify docs:
  - `docs/LANGUAGE.md`
  - `docs/MACHINE.md`
  - `docs/ARCHITECTURE.md`

---

## Task 1: Добавить tagged stdio descriptor parser

**Files:**
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/StdioDescriptorTest.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/StdioDescriptor.kt`

- [ ] **Step 1: Написать failing parser tests**

Create `StdioDescriptorTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StdioDescriptorTest {
    @Test
    fun decodesTaggedDescriptor() {
        val descriptor = StdioDescriptor.decode("stdio-v1 3 4 5 hello world")

        assertEquals(StdioDescriptor(stdin = 3, stdout = 4, stderr = 5, argument = "hello world"), descriptor)
    }

    @Test
    fun decodesTaggedDescriptorWithBlankArgument() {
        val descriptor = StdioDescriptor.decode("stdio-v1 3 4 5 ")

        assertEquals(StdioDescriptor(stdin = 3, stdout = 4, stderr = 5, argument = ""), descriptor)
    }

    @Test
    fun rejectsUntaggedLegacyDescriptor() {
        assertNull(StdioDescriptor.decode("3 4 5 hello"))
    }

    @Test
    fun rejectsMalformedDescriptor() {
        assertNull(StdioDescriptor.decode("stdio-v1 input 4 5 hello"))
        assertNull(StdioDescriptor.decode("stdio-v1 3 4"))
        assertNull(StdioDescriptor.decode("stdio-v2 3 4 5 hello"))
    }

    @Test
    fun encodesTaggedDescriptor() {
        val text = StdioDescriptor(stdin = 3, stdout = 4, stderr = 5, argument = "hello world").encode()

        assertEquals("stdio-v1 3 4 5 hello world", text)
    }
}
```

- [ ] **Step 2: Запустить focused test и увидеть failure**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.StdioDescriptorTest`

Expected: FAIL compile-time, потому что `StdioDescriptor` ещё не существует.

- [ ] **Step 3: Реализовать parser**

Create `StdioDescriptor.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm

internal data class StdioDescriptor(
    val stdin: Int,
    val stdout: Int,
    val stderr: Int,
    val argument: String,
) {
    fun encode(): String = "$TAG $stdin $stdout $stderr $argument"

    companion object {
        private const val TAG = "stdio-v1"

        fun decode(raw: String): StdioDescriptor? {
            if (!raw.startsWith("$TAG ")) return null
            val rest = raw.removePrefix("$TAG ")
            val stdinText = rest.substringBefore(' ', missingDelimiterValue = "")
            val restAfterStdin = rest.substringAfter(' ', missingDelimiterValue = "")
            val stdoutText = restAfterStdin.substringBefore(' ', missingDelimiterValue = "")
            val restAfterStdout = restAfterStdin.substringAfter(' ', missingDelimiterValue = "")
            val stderrText = restAfterStdout.substringBefore(' ', missingDelimiterValue = "")
            val argument = restAfterStdout.substringAfter(' ', missingDelimiterValue = "")
            val stdin = stdinText.toIntOrNull() ?: return null
            val stdout = stdoutText.toIntOrNull() ?: return null
            val stderr = stderrText.toIntOrNull() ?: return null
            return StdioDescriptor(stdin = stdin, stdout = stdout, stderr = stderr, argument = argument)
        }
    }
}
```

- [ ] **Step 4: Запустить focused test и увидеть pass**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.StdioDescriptorTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/StdioDescriptor.kt modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/StdioDescriptorTest.kt && git commit -m "feat: add tagged stdio descriptors"`

---

## Task 2: Обновить ROM stdio format до `stdio-v1`

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/stdio.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`

- [ ] **Step 1: Добавить ROM source audit для tagged descriptor**

In `RomScriptCompileTest.kt`, add this test inside `RomScriptCompileTest`:

```kotlin
    @Test
    fun bundledRomStdioUsesTaggedDescriptorOnly() {
        val source = resourceText("rom/stdio.ck")
        assertTrue(source.contains("stdio-v1"), "rom/stdio.ck must emit tagged stdio-v1 descriptors")
        assertFalse(source.contains("strings::beforeSpace(raw)"), "rom/stdio.ck must not parse untagged descriptors directly")
    }
```

Add `import kotlin.test.assertTrue` if missing.

- [ ] **Step 2: Run focused test and verify failure**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`

Expected: FAIL because `rom/stdio.ck` still uses the untagged format.

- [ ] **Step 3: Обновить `stdio.ck`**

Replace `stdio.ck` with:

```ck
pub struct Stdio { input: Int, output: Int, error: Int, argument: String }

fun field(text: String, index: Int): String {
    var current: String = text
    var i: Int = 0
    while i < index {
        current = strings::afterSpace(current)
        i = i + 1
    }
    return strings::beforeSpace(current)
}

fun remainder(text: String, fields: Int): String {
    var current: String = text
    var i: Int = 0
    while i < fields {
        current = strings::afterSpace(current)
        i = i + 1
    }
    return current
}

pub fun fromArgument(raw: String): Stdio {
    if (field(raw, 0) != "stdio-v1") {
        return Stdio(input = -1, output = -1, error = -1, argument = raw)
    }
    return Stdio(
        input = strings::toInt(field(raw, 1)),
        output = strings::toInt(field(raw, 2)),
        error = strings::toInt(field(raw, 3)),
        argument = remainder(raw, 4),
    )
}

pub fun encode(ctx: Stdio, argument: String): String {
    return "stdio-v1 " + ctx.input + " " + ctx.output + " " + ctx.error + " " + argument
}

pub fun write(ctx: Stdio, text: String) {
    if (ctx.output >= 0) {
        ipc::write(ctx.output, text)
    }
}

pub fun println(ctx: Stdio, text: String) {
    write(ctx, text + "\n")
}

pub fun error(ctx: Stdio, text: String) {
    if (ctx.error >= 0) {
        ipc::write(ctx.error, text + "\n")
    }
}

pub fun readLine(ctx: Stdio): String {
    if (ctx.input < 0) {
        return ""
    }
    return ipc::read(ctx.input)
}

pub fun main() {
    return
}
```

- [ ] **Step 4: Run ROM compile tests**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run: `git add modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/stdio.ck modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt && git commit -m "refactor: tag rom stdio descriptors"`

---

## Task 3: Направить process manager errors в tagged stderr

**Files:**
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`

- [ ] **Step 1: Добавить failing VM stderr tests**

In core `BackgroundDeviceVmTest.kt`, add tests that open IPC channels, run a child with tagged descriptor, read stderr, and write it to server log through `system::log`:

```kotlin
    @Test
    fun processRunWritesLaunchErrorsToTaggedStderr() {
        runtimeTestWorkspace("process-stderr-launch") { workspace ->
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val input: Int = ipc::open()
                                val output: Int = ipc::open()
                                val error: Int = ipc::open()
                                val code: Int = process::run("missing.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
                                system::log("code=" + code)
                                system::log(ipc::read(error))
                            }
                            """.trimIndent(),
                        ),
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 24)

            assertTrue(logs.any { it.contains("code=1") }, logs.toString())
            assertTrue(logs.any { it.contains("Program not found: missing.ck") }, logs.toString())
        }
    }

    @Test
    fun processRunWritesCompilationErrorsToTaggedStderr() {
        runtimeTestWorkspace("process-stderr-compile") { workspace ->
            workspace.writeProgram(1, "bad.ck", "pub fun main() { val x: Int = \"bad\"; }")
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                val input: Int = ipc::open()
                                val output: Int = ipc::open()
                                val error: Int = ipc::open()
                                val code: Int = process::run("bad.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
                                system::log("code=" + code)
                                system::log(ipc::read(error))
                            }
                            """.trimIndent(),
                        ),
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 24)

            assertTrue(logs.any { it.contains("code=1") }, logs.toString())
            assertTrue(logs.any { it.contains("Compilation Error in bad.ck") }, logs.toString())
        }
    }
```

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest`

Expected: FAIL because errors are still printed through `terminal.println()` instead of tagged stderr.

- [ ] **Step 3: Remove terminal dependency from process APIs and write stderr**

In `VmProcessApi.kt`:

- remove `DeviceTerminalApi` import;
- remove constructor parameter `private val terminal: DeviceTerminalApi,`;
- change `spawn(path, argument)` to call `processManager.spawn(path, argument, workingDirectory)`.

In `VmProcessManager.kt`:

- remove `DeviceTerminalApi` import;
- change `spawn()` signature to:

```kotlin
    fun spawn(
        path: String,
        argument: String,
        workingDirectory: String,
    ): Int {
```

- change `execute()` signature to remove `terminal`;
- add `suspend fun writeIpc(channel: Int, text: String)` to `VmContext` if it does not exist, implemented by `BackgroundDeviceVm` via `ipcRegistry.write(channel, text)`;
- inside `execute()`, add:

```kotlin
        val stderr = StdioDescriptor.decode(argument)?.stderr
        suspend fun reportError(message: String) {
            ctx.log("VM[$deviceId] $message")
            if (stderr != null && stderr >= 0) {
                ctx.writeIpc(stderr, message + "\n")
            }
        }
```

Replace `terminal.println(...)` error writes with `reportError(...)`.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest`

Expected: PASS after adding `VmContext.writeIpc(channel, text)` and implementing it in `BackgroundDeviceVm`.

- [ ] **Step 5: Commit**

Run: `git add modules/core/src/main/kotlin modules/core/src/test/kotlin && git commit -m "refactor: route process errors to tagged stderr"`

---

## Task 4: Удалить terminal/stdout builtins из compiler registry

**Files:**
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontendTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageIdeTest.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`

- [ ] **Step 1: Add failing frontend tests**

In `LanguageFrontendTest.kt`, add:

```kotlin
    @Test
    fun terminalAndStdoutBuiltinsAreRemoved() {
        assertNull(LanguageBuiltins.defaultRuntimeRegistry.module("terminal"))
        assertNull(LanguageBuiltins.defaultRuntimeRegistry.module("stdout"))
    }

    @Test
    fun terminalAndStdoutCallsAreUnknownModules() {
        val terminal = frontend.compile("main.ck", "pub fun main() { terminal::println(\"hi\"); }")
        assertTrue(terminal.diagnostics.any { it.message.contains("terminal") })

        val stdout = frontend.compile("main.ck", "pub fun main() { stdout::write(\"hi\"); }")
        assertTrue(stdout.diagnostics.any { it.message.contains("stdout") })
    }
```

Add `import kotlin.test.assertNull` if missing.

In `LanguageIdeTest.kt`, add/update a completion test so module completions no longer contain terminal/stdout:

```kotlin
    @Test
    fun moduleCompletionsExcludeRemovedTerminalStdoutModules() {
        val source = "import "
        val cursor = source.length
        val completions = LanguageIde(LanguageBuiltins.defaultRuntimeRegistry).complete("main.ck", source, cursor)
        val labels = completions.items.map { it.label }.toSet()

        assertFalse("terminal" in labels)
        assertFalse("stdout" in labels)
        assertTrue("display" in labels)
    }
```

Adapt constructor/helper names to the existing `LanguageIdeTest` pattern.

- [ ] **Step 2: Run compiler tests and verify failure**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageFrontendTest --tests ru.lazyhat.compukterkraft.lang.frontend.LanguageIdeTest`

Expected: FAIL because terminal/stdout modules still exist and completion expectations still include them.

- [ ] **Step 3: Remove builtins**

In `LanguageBuiltins.kt`, delete the whole `BuiltinModule(name = "terminal", ...)` and `BuiltinModule(name = "stdout", ...)` entries.

- [ ] **Step 4: Rewrite compiler/IDE snippets**

Run: `rg "terminal::|stdout::|module\(\"terminal\"\)|module\(\"stdout\"\)|\"terminal\".*\"stdout\"" modules/compiler/src/test/kotlin`.

For tests that only need a valid program, replace old snippets with:

```ck
pub fun main() { val value: Int = 1 + 2; }
```

For tests that need a host call, use display:

```ck
pub fun main() { val id: Int = display::primary(); display::present(id); }
```

For import completion expectations, remove `terminal` and `stdout` from expected sets and keep `display`, `filesystem`, `system`, `events`, `ipc`, `process`, `strings`.

- [ ] **Step 5: Run compiler tests**

Run: `./gradlew :compiler:test`

Expected: PASS.

- [ ] **Step 6: Commit**

Run: `git add modules/compiler/src/main/kotlin modules/compiler/src/test/kotlin && git commit -m "refactor: remove terminal stdout builtins"`

---

## Task 5: Удалить runtime bridge и DeviceRuntime terminal/stdout APIs

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
- Delete: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceStdioApi.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntimeTest.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/UserFileImportsRuntimeTest.kt`

- [ ] **Step 1: Add source-level architecture test for bridge removal**

Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridgeArchitectureTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime

import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse

class RuntimeHostBridgeArchitectureTest {
    @Test
    fun bridgeDoesNotDispatchTerminalStdout() {
        val source = Paths.get("src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt").readText()

        assertFalse(source.contains("invokeTerminal"))
        assertFalse(source.contains("invokeStdout"))
        assertFalse(source.contains("\"terminal\" ->"))
        assertFalse(source.contains("\"stdout\" ->"))
    }
}
```

- [ ] **Step 2: Run architecture test and verify failure**

Run: `./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.runtime.RuntimeHostBridgeArchitectureTest`

Expected: FAIL because bridge still dispatches terminal/stdout.

- [ ] **Step 3: Remove bridge handlers**

In `RuntimeHostBridge.kt`:

- remove `"terminal" -> invokeTerminal(functionName, arguments)`;
- remove `"stdout" -> invokeStdout(functionName, arguments)`;
- delete `invokeTerminal()`;
- delete `invokeStdout()`;
- remove terminal/stdout handling from `ensureCapability()` if present.

- [ ] **Step 4: Remove runtime interfaces**

In `DeviceRuntime.kt`:

- remove `val terminal: DeviceTerminalApi`;
- remove `val stdio: DeviceStdioApi`;
- delete the `DeviceTerminalApi` interface.

Delete `DeviceStdioApi.kt`.

- [ ] **Step 5: Rewrite runtime tests and `RecordingRuntime`**

In `LanguageRuntimeTest.kt` and `UserFileImportsRuntimeTest.kt`:

- remove `RecordingRuntime.terminal` and `RecordingRuntime.stdio` implementations;
- replace snippets containing `terminal::println` with valid non-terminal snippets;
- delete assertions that check recorded terminal/stdout output;
- for runtime side effects, use `system::log`, `ipc`, or `display` depending on the test purpose.

Use this replacement for simple runtime execution snippets:

```ck
pub fun main() {
    val id: Int = system::deviceId();
    system::log("device=" + id);
}
```

- [ ] **Step 6: Run compiler tests**

Run: `./gradlew :compiler:test`

Expected: PASS.

- [ ] **Step 7: Commit**

Run: `git add modules/compiler/src/main/kotlin modules/compiler/src/test/kotlin && git commit -m "refactor: remove runtime terminal stdout bridge"`

---

## Task 6: Удалить core VM terminal/stdout implementations

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
- Delete:
  - `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmTerminalApi.kt`
  - `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcaster.kt`
  - `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ScreenBufferVtSink.kt`
  - `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/CursorTracker.kt`
- Delete corresponding tests listed in Step 5.

- [ ] **Step 1: Add source audit test for core VM terminal implementations**

Create `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmTerminalRemovalArchitectureTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse

class VmTerminalRemovalArchitectureTest {
    @Test
    fun coreMainDoesNotReferenceVmTerminalStdoutImplementations() {
        val root = Path.of("src/main/kotlin")
        val source =
            Files.walk(root).use { paths ->
                paths
                    .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                    .joinToString("\n") { it.readText() }
            }

        listOf(
            "DeviceTerminalApi",
            "DeviceStdioApi",
            "VmTerminalApi",
            "ComputerStdioBroadcaster",
            "ScreenBufferVtSink",
            "CursorTracker",
        ).forEach { forbidden ->
            assertFalse(source.contains(forbidden), "core main must not reference $forbidden")
        }
    }
}
```

- [ ] **Step 2: Run source audit and verify failure**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.VmTerminalRemovalArchitectureTest`

Expected: FAIL because core VM still references terminal/stdout implementations.

- [ ] **Step 3: Simplify `VmRuntime`**

In `VmRuntime.kt`:

- remove imports `DeviceTerminalApi` and `DeviceStdioApi`;
- remove constructor parameters `terminalApi` and `stdioApi`;
- remove override properties `terminal` and `stdio`;
- keep `display`, `filesystem`, `process`, `ipc`, `events`, `system`, `redstone`, `peripherals`.

- [ ] **Step 4: Simplify `BackgroundDeviceVm` runtime assembly**

In `BackgroundDeviceVm.kt`:

- remove imports `ComputerStdioBroadcaster`, `ScreenBufferVtSink`, `VmTerminalApi`, `ScreenBuffer`, `ScreenBufferSnapshot`, `VtParser`;
- remove fields `screenBuffer`, `screenBufferFeeder`, `stdioBroadcaster`;
- remove `runtime.terminal.clear()` in `boot()`;
- remove `readScreenSnapshot()` and `forceScreenSnapshot()` implementations if Task 7 removes them from `DeviceVmHandle` first, otherwise leave temporary no-op only until Task 7;
- update `createRuntime()` to construct `VmRuntime` without terminal/stdout APIs;
- update `VmProcessApi` construction without terminal.

- [ ] **Step 5: Delete implementation files and tests**

Run: `rg "VmTerminalApi|ComputerStdioBroadcaster|ScreenBufferVtSink|CursorTracker" modules/core/src/main modules/core/src/test`.

If remaining matches are only implementation files/tests, delete:

```text
modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmTerminalApi.kt
modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcaster.kt
modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ScreenBufferVtSink.kt
modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/CursorTracker.kt
modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ComputerStdioBroadcasterTest.kt
modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/ScreenBufferVtSinkTest.kt
modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/CursorTrackerTest.kt
```

- [ ] **Step 6: Run core compile/tests**

Run: `./gradlew :core:test`

Expected: PASS after Task 7 removes any remaining VM handle snapshot dependencies. If compile fails only because `DeviceVmHandle` still requires screen snapshots, proceed to Task 7 before committing and commit Tasks 6-7 together.

---

## Task 7: Удалить VM screen snapshot ownership path

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
- Modify Workbench snapshot files:
  - `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt`
  - `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/context/ServerWorkbench.kt`
  - `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/network/client/WorkbenchTerminalClientMessage.kt`

- [ ] **Step 1: Add source audit test for VM handle snapshots**

Create `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceNoScreenSnapshotTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse

class RuntimeDeviceNoScreenSnapshotTest {
    @Test
    fun runtimeDeviceNoLongerReadsVmScreenSnapshots() {
        val roots = listOf(Path.of("src/main/kotlin"))
        val source =
            roots.joinToString("\n") { root ->
                Files.walk(root).use { paths ->
                    paths
                        .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                        .joinToString("\n") { it.readText() }
                }
            }

        assertFalse(source.contains("readScreenSnapshot"))
        assertFalse(source.contains("forceScreenSnapshot"))
        assertFalse(source.contains("lastScreenSnapshot"))
    }
}
```

- [ ] **Step 2: Run audit test and verify failure**

Run: `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceNoScreenSnapshotTest`

Expected: FAIL because runtime device still exposes snapshots.

- [ ] **Step 3: Remove snapshot methods from `DeviceVmHandle`**

In `DeviceVmModels.kt`, remove:

```kotlin
fun readScreenSnapshot(): ScreenBufferSnapshot?
fun forceScreenSnapshot(): ScreenBufferSnapshot
```

Remove unused `ScreenBufferSnapshot` import if it becomes unused.

- [ ] **Step 4: Remove `RuntimeDeviceScreen` role**

In `RuntimeDevice.kt`, remove `RuntimeDeviceScreen` and remove it from `RuntimeDevice` inheritance.

- [ ] **Step 5: Remove sync screen from `RuntimeDeviceImpl`**

In `RuntimeDeviceImpl.kt`:

- remove `screenSnapshot` field;
- remove `lastScreenSnapshot` override;
- remove `syncScreen()`;
- remove `syncScreen()` call from `serverTick()`;
- remove `ScreenBufferSnapshot` import.

- [ ] **Step 6: Keep Workbench attach terminal disabled without VM snapshots**

In `WorkbenchBlockEntity.RuntimeBridge` and `ServerWorkbench`:

- remove `currentScreenSnapshot()` from `WorkbenchTargetRuntimeBridge` and its implementations;
- remove sync calls to `lastScreenSnapshot`;
- keep `attachTerminal()` as a no-op with a comment pointing to future display viewer.

Delete `WorkbenchTerminalClientMessage` and remove its `NetworkMessages` registration. Update affected tests to assert that Workbench attach-terminal stays disabled and does not send snapshot messages.

- [ ] **Step 7: Run common/core tests**

Run: `./gradlew :core:test :v1_21_1-common:test`

Expected: PASS.

- [ ] **Step 8: Commit Tasks 6-7 together**

Run: `git add modules/compiler/src/main/kotlin modules/core modules/v1_21_1/v1_21_1-common && git commit -m "refactor: remove vm terminal screen snapshots"`

---

## Task 8: Обновить firmware и ROM, чтобы они рендерили без terminal/stdout

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/shell.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/terminal.ck`
- Modify external ROM tools if their imports need the tagged stdio helper names:
    - `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/ls.ck`
    - `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/mkdir.ck`
    - `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/nano.ck`
    - `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/pwd.ck`
    - `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/rom/rmdir.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Strengthen ROM/Firmware audit tests**

In `RomScriptCompileTest.kt`, add:

```kotlin
    @Test
    fun bundledFirmwareAndRomDoNotUseRemovedTerminalStdoutBuiltins() {
        val paths =
            listOf("firmware/bios.ck") +
                bundledRomScriptPaths()

        paths.forEach { path ->
            val source = resourceText(path)
            assertFalse(source.contains("terminal::"), "$path still uses removed terminal builtins")
            assertFalse(source.contains("stdout::"), "$path still uses removed stdout builtins")
        }
    }
```

If `bundledRomScriptPaths()` does not exist, create a helper that reads `rom/rom.index` and returns each non-blank ROM path prefixed with `rom/`.

- [ ] **Step 2: Run ROM compile tests and verify failure**

Run: `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest`

Expected: FAIL because `firmware/bios.ck` still uses `terminal::println`.

- [ ] **Step 3: Update `shell.ck` external process errors to stderr**

In `shell.ck`, change `runExternal()` to pass tagged stdio descriptors and report unknown commands to stderr:

```ck
fun runExternal(ctx: Stdio, command: String, argument: String) {
    if (process::run(command + ".ck", encode(ctx, argument)) != 0) {
        error(ctx, "Unknown command: " + command)
    }
}
```

Ensure `error` is imported from `stdio.ck`.

- [ ] **Step 4: Update `terminal.ck` to render stderr text**

In `terminal.ck`, keep reading both output and error channels:

```ck
val chunk: String = ipc::tryRead(output) + ipc::tryRead(error)
```

If already present, no change is needed. The important check is that terminal receives shell/external stderr through the error channel and renders it through display.

- [ ] **Step 5: Replace BIOS terminal prints with display-owned rendering**

In `bios.ck`, add simple display text block helpers using the existing block-glyph style. Use this minimal renderer:

```ck
fun draw_text(displayId: Int, row: Int, text: String, color: Int) {
    var x: Int = 0
    var i: Int = 0
    while i < strings::length(text) {
        if (strings::charAt(text, i) != " ") {
            display::fillRect(displayId, x * 6, row * 9, 5, 8, color)
        }
        x = x + 1
        i = i + 1
    }
}

fun render_boot(lines: String) {
    val id: Int = display::primary()
    if (id < 0) {
        return
    }
    display::clear(id, 0)
    draw_text(id, 0, "Compukter Kraft BIOS", 2016)
    draw_text(id, 1, lines, 2016)
    display::present(id)
}
```

Then replace `terminal::println(...)` calls with updates to a `status` string and calls to `render_boot(status)`. For launching `boot.ck`, open stdio channels and pass a tagged descriptor:

```ck
val input: Int = ipc::open()
val output: Int = ipc::open()
val error: Int = ipc::open()
val code: Int = process::run("boot.ck", "stdio-v1 " + input + " " + output + " " + error + " ")
val processErrors: String = ipc::tryRead(error)
```

Render `processErrors` or generic failure text when `code != 0`.

- [ ] **Step 6: Update ROM behavior tests**

In NeoForge `BackgroundDeviceVmTest.kt`, replace any assertions on `forceScreenSnapshot()` or terminal text with display frame assertions. Use the existing `greenPixelCount()` / `dirtyPixelArea()` helpers from the previous display-only tests.

- [ ] **Step 7: Run NeoForge tests**

Run: `./gradlew :v1_21_1-neoforge:test`

Expected: PASS.

- [ ] **Step 8: Commit**

Run: `git add modules/v1_21_1/v1_21_1-neoforge && git commit -m "refactor: render firmware without terminal builtins"`

---

## Task 9: Documentation and final source audit

**Files:**
- Modify: `docs/LANGUAGE.md`
- Modify: `docs/MACHINE.md`
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Update language docs**

In `docs/LANGUAGE.md`:

- remove `terminal` and `stdout` from builtin module lists;
- describe display as the only visible runtime output API;
- document `stdio-v1 <stdin> <stdout> <stderr> <argument>` as ROM stdio convention, not as a language builtin;
- replace examples that use `terminal::println` with `display::*`, `system::log`, or pure examples.

- [ ] **Step 2: Update architecture/machine docs**

In `docs/ARCHITECTURE.md` and `docs/MACHINE.md`:

- remove statements that VM terminal/stdout APIs remain as staged compatibility;
- document that programs own rendering and VM does not have a diagnostics renderer;
- document process errors to tagged stderr descriptor.

- [ ] **Step 3: Run docs/source stale scan**

Run:

```bash
rg "terminal::|stdout::|DeviceTerminalApi|DeviceStdioApi|VmTerminalApi|ComputerStdioBroadcaster|ScreenBufferVtSink|invokeTerminal|invokeStdout|readScreenSnapshot|forceScreenSnapshot|lastScreenSnapshot" modules/compiler/src/main modules/core/src/main modules/v1_21_1/v1_21_1-common/src/main modules/v1_21_1/v1_21_1-neoforge/src/main/resources docs/ARCHITECTURE.md docs/MACHINE.md docs/LANGUAGE.md
```

Expected: no matches except historical explanatory text in docs that explicitly says the APIs were removed.

- [ ] **Step 4: Run full tests**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

Run: `git add docs && git commit -m "docs: remove vm terminal stdout model"`

---

## Task 10: Final verification

**Files:**
- No source edits expected.

- [ ] **Step 1: Run full tests**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run final audit**

Run:

```bash
rg "terminal::|stdout::|DeviceTerminalApi|DeviceStdioApi|VmTerminalApi|ComputerStdioBroadcaster|ScreenBufferVtSink|invokeTerminal|invokeStdout|readScreenSnapshot|forceScreenSnapshot|lastScreenSnapshot" modules/compiler/src/main modules/core/src/main modules/v1_21_1/v1_21_1-common/src/main modules/v1_21_1/v1_21_1-neoforge/src/main/resources
```

Expected: no matches.

- [ ] **Step 3: Check git status**

Run: `git status --short`

Expected: clean.

- [ ] **Step 4: Report remaining follow-up work**

Report that the remaining follow-ups are:

- Workbench display viewer through display sessions;
- optional user workspace migration for old terminal/stdout and untagged stdio scripts;
- optional structured diagnostics if display-rendered diagnostics are not enough later.

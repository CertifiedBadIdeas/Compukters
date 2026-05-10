# Explicit VM Process Table Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit Kotlin process table foundation so CKL process lifecycle and parent wait state are observable by pid instead of being implicit in coroutine suspension.

**Architecture:** Keep the existing coroutine-backed execution model for this slice, but introduce a focused `VmProcessTable` that records process metadata and state transitions. `VmProcessManager` updates the table on spawn, wait, exit, and failed launch; `VmProcessApi` passes its runtime pid into `wait` so parent processes can be marked as `WaitingProcess(childPid)`.

**Tech Stack:** Kotlin/JVM coroutines, existing CKVM native image runner, Gradle tests, current `VmProcessManager` and runtime APIs.

---

## File Structure

- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTable.kt`
  - Owns immutable process records and explicit process states.
  - Provides small mutation methods used by `VmProcessManager`.
- Create `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTableTest.kt`
  - Covers registration, waiting state, exit state, and snapshot isolation.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
  - Registers root and child process records.
  - Marks child exit.
  - Exposes `processSnapshot(pid)`.
  - Allows `wait(pid, waiterPid)` to mark a parent as waiting.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
  - Calls `processManager.wait(pid, waiterPid = processId)`.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`
  - Verifies manager state transitions and parent wait state.

## Task 1: Process Table Model

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTable.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTableTest.kt`

- [ ] **Step 1: Write failing process table tests**

Create `VmProcessTableTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class VmProcessTableTest {
    @Test
    fun registerProcessStoresMetadataAndRunnableState() {
        val table = VmProcessTable()

        table.registerProcess(pid = 2, parentPid = 1, programPath = "child.ck", argument = "arg", workingDirectory = "bin")

        val record = table.snapshot(2)
        assertEquals(2, record?.pid)
        assertEquals(1, record?.parentPid)
        assertEquals("child.ck", record?.programPath)
        assertEquals("arg", record?.argument)
        assertEquals("bin", record?.workingDirectory)
        assertEquals(VmProcessState.Runnable, record?.state)
    }

    @Test
    fun processCanMoveThroughWaitingAndExitedStates() {
        val table = VmProcessTable()
        table.registerProcess(pid = 1, parentPid = 0, programPath = "bios.ck", argument = "", workingDirectory = "")

        table.markWaitingProcess(pid = 1, targetPid = 2)
        assertEquals(VmProcessState.WaitingProcess(2), table.snapshot(1)?.state)

        table.markRunnable(pid = 1)
        assertEquals(VmProcessState.Runnable, table.snapshot(1)?.state)

        table.markExited(pid = 1, exitCode = 7)
        assertEquals(VmProcessState.Exited(7), table.snapshot(1)?.state)
    }

    @Test
    fun unknownProcessTransitionsAreIgnored() {
        val table = VmProcessTable()

        table.markWaitingProcess(pid = 99, targetPid = 2)
        table.markRunnable(pid = 99)
        table.markExited(pid = 99, exitCode = 1)

        assertNull(table.snapshot(99))
    }

    @Test
    fun snapshotListIsSortedByPid() {
        val table = VmProcessTable()
        table.registerProcess(pid = 3, parentPid = 1, programPath = "b.ck", argument = "", workingDirectory = "")
        table.registerProcess(pid = 2, parentPid = 1, programPath = "a.ck", argument = "", workingDirectory = "")

        assertEquals(listOf(2, 3), table.snapshot().map { it.pid })
        assertIs<VmProcessState.Runnable>(table.snapshot().first().state)
    }
}
```

- [ ] **Step 2: Run focused test and verify RED**

Run:

```bash
./gradlew :core:test --tests '*VmProcessTableTest' --rerun-tasks
```

Expected: FAIL because `VmProcessTable` and `VmProcessState` do not exist.

- [ ] **Step 3: Implement the process table**

Create `VmProcessTable.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm

import java.util.concurrent.ConcurrentHashMap

internal sealed interface VmProcessState {
    data object Runnable : VmProcessState
    data class WaitingProcess(val targetPid: Int) : VmProcessState
    data class Exited(val exitCode: Int) : VmProcessState
}

internal data class VmProcessRecord(
    val pid: Int,
    val parentPid: Int,
    val programPath: String,
    val argument: String,
    val workingDirectory: String,
    val state: VmProcessState,
)

internal class VmProcessTable {
    private val records = ConcurrentHashMap<Int, VmProcessRecord>()

    fun registerProcess(
        pid: Int,
        parentPid: Int,
        programPath: String,
        argument: String,
        workingDirectory: String,
    ) {
        records[pid] =
            VmProcessRecord(
                pid = pid,
                parentPid = parentPid,
                programPath = programPath,
                argument = argument,
                workingDirectory = workingDirectory,
                state = VmProcessState.Runnable,
            )
    }

    fun markRunnable(pid: Int) {
        updateState(pid, VmProcessState.Runnable)
    }

    fun markWaitingProcess(
        pid: Int,
        targetPid: Int,
    ) {
        updateState(pid, VmProcessState.WaitingProcess(targetPid))
    }

    fun markExited(
        pid: Int,
        exitCode: Int,
    ) {
        updateState(pid, VmProcessState.Exited(exitCode))
    }

    fun snapshot(pid: Int): VmProcessRecord? = records[pid]

    fun snapshot(): List<VmProcessRecord> = records.values.sortedBy { it.pid }

    private fun updateState(
        pid: Int,
        state: VmProcessState,
    ) {
        records.computeIfPresent(pid) { _, record -> record.copy(state = state) }
    }
}
```

- [ ] **Step 4: Run focused test**

Run:

```bash
./gradlew :core:test --tests '*VmProcessTableTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTable.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTableTest.kt
git commit -m "feat: add explicit VM process table"
```

## Task 2: Process Manager Lifecycle Integration

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`

- [ ] **Step 1: Add failing manager state test**

Add this test to `VmProcessManagerTest`:

```kotlin
@Test
fun spawnRecordsChildLifecycleInProcessTable() {
    runtimeTestWorkspace("vm-process-manager-process-table") { workspace ->
        val bridge = RecordingNativeProcessBridge()
        val ctx = StubVmContext()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager =
            VmProcessManager(
                scope = scope,
                ctx = ctx,
                deviceId = 1,
                programLoader = WorkspaceProgramLoader(workspace.host),
                profile = runtimeProfile(),
                runtimeCreator = { _, _, _, _ -> error("runtimeCreator should not run for a missing program") },
                compilerMetricsCollector = NoOpCompilerMetricsCollector,
                nativeProcessBridge = bridge,
            )

        try {
            val pid = manager.spawn("missing.ck", "arg", "bin", parentPid = 41)
            val code = runBlocking { withTimeout(5_000) { manager.wait(pid) } }

            val record = manager.processSnapshot(pid)
            assertEquals(1, code)
            assertEquals(pid, record?.pid)
            assertEquals(41, record?.parentPid)
            assertEquals("missing.ck", record?.programPath)
            assertEquals("arg", record?.argument)
            assertEquals("bin", record?.workingDirectory)
            assertEquals(VmProcessState.Exited(1), record?.state)
        } finally {
            runBlocking { manager.cancelAll() }
            scope.cancel()
        }
    }
}
```

- [ ] **Step 2: Run focused test and verify RED**

Run:

```bash
./gradlew :core:test --tests '*VmProcessManagerTest.spawnRecordsChildLifecycleInProcessTable' --rerun-tasks
```

Expected: FAIL because `VmProcessManager.processSnapshot(...)` does not exist.

- [ ] **Step 3: Integrate `VmProcessTable` into `VmProcessManager`**

In `VmProcessManager`, add:

```kotlin
private val processTable = VmProcessTable()
```

Register the root process in `init`:

```kotlin
init {
    processTable.registerProcess(pid = 1, parentPid = 0, programPath = profile.bootScriptName, argument = "", workingDirectory = "")
}
```

Add:

```kotlin
fun processSnapshot(pid: Int): VmProcessRecord? = processTable.snapshot(pid)
```

In `spawn(...)`, after `exitCode` creation and before native registration, add:

```kotlin
processTable.registerProcess(
    pid = pid,
    parentPid = parentPid,
    programPath = path,
    argument = argument,
    workingDirectory = workingDirectory,
)
```

After `execute(...)` returns `code`, before native completion, add:

```kotlin
processTable.markExited(pid, code)
```

In `job.invokeOnCompletion`, when completing an unhandled failure, also call:

```kotlin
processTable.markExited(pid, 1)
```

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew :core:test --tests '*VmProcessManagerTest.spawnRecordsChildLifecycleInProcessTable' --tests '*VmProcessManagerTest.spawnRegistersAndCompletesNativeProcess' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt
git commit -m "feat: record VM process lifecycle states"
```

## Task 3: Parent WaitingProcess State

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`

- [ ] **Step 1: Add failing parent wait state test**

Add a test to `VmProcessManagerTest` that spawns a child which blocks in `events::pull("release")`, starts `manager.wait(pid, waiterPid = 1)` asynchronously, verifies root process state is `WaitingProcess(pid)`, releases the child event, and verifies root returns to `Runnable` while the child is `Exited(0)`.

The test should skip only if `System.getProperty("ckl.vm.native.library")` is blank, because the compiled CKL child runs through the native image runner.

- [ ] **Step 2: Run focused test and verify RED**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :core:test --tests '*VmProcessManagerTest.waitMarksParentAsWaitingProcessUntilChildExits' --rerun-tasks
```

Expected: FAIL because `wait(pid, waiterPid = 1)` does not exist or does not update process state.

- [ ] **Step 3: Make wait pid-aware**

Change `VmProcessManager.wait` to:

```kotlin
suspend fun wait(
    pid: Int,
    waiterPid: Int? = null,
): Int {
    val handle = processes[pid] ?: return 1
    if (waiterPid != null) {
        processTable.markWaitingProcess(waiterPid, pid)
    }
    val code =
        try {
            handle.exitCode.await()
        } finally {
            if (waiterPid != null && processTable.snapshot(waiterPid)?.state == VmProcessState.WaitingProcess(pid)) {
                processTable.markRunnable(waiterPid)
            }
        }
    processes.remove(pid, handle)
    return code
}
```

Change `VmProcessApi.wait` to:

```kotlin
override suspend fun wait(pid: Int): Int = processManager.wait(pid, waiterPid = processId)
```

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :core:test --tests '*VmProcessManagerTest.waitMarksParentAsWaitingProcessUntilChildExits' --tests '*BackgroundDeviceVmTest.parentCanSpawnChildAndExchangeIpcText' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt
git commit -m "feat: mark VM parents waiting on child processes"
```

## Task 4: Verification

**Files:**
- No production file changes expected unless verification reveals a bug.

- [ ] **Step 1: Run core VM process tests**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :core:test --tests '*VmProcessTableTest' --tests '*VmProcessManagerTest' --tests '*BackgroundDeviceVmTest.parentCanSpawnChildAndExchangeIpcText' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 2: Run full core tests**

Run:

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :core:test --rerun-tasks
```

Expected: PASS.

- [ ] **Step 3: Commit verification fixes if needed**

If verification requires a fix, commit it with:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm
git commit -m "fix: stabilize explicit VM process table"
```

If no files changed, do not create an empty commit.

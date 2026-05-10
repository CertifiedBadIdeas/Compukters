# Device Quota Process Scheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the binary VM slice permit channel with a device execution quota foundation, make process runtime state process-local, and prepare process scheduling to move from Kotlin coroutine wake order toward an explicit VM-owned scheduler.

**Architecture:** Implement this as Kotlin-first scheduler groundwork on `dev`. Server ticks refill a bounded device quota object instead of sending anonymous `Unit` permits. Each runtime receives process identity and process-local working directory state, so later scheduler work can reason about pids instead of ambient device-global state. Existing Rust process wait/registration remains in place and is used by the new pid-aware process bridge.

**Tech Stack:** Kotlin/JVM coroutines, existing CKVM native image runner, Rust native process table through JNI, Gradle tests, runtime profiling reports.

---

## File Structure

- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceExecutionQuota.kt`
  - Owns bounded per-device execution quota.
  - Replaces anonymous `Channel<Unit>` semantics with explicit `refill(...)` and `awaitPermit(...)`.
- Create `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceExecutionQuotaTest.kt`
  - Covers refill cap, sleep-gated requests, and waiting consumers.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
  - Replace `slicePermits` with `DeviceExecutionQuota`.
  - Pass `processId`, parent id, working directory, and argument into runtime creation.
  - Remove shared path resolver mutation from runtime creation.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
  - Store `processId`.
  - Use process-local path resolver through process/filesystem APIs.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmContext.kt`
  - Keep compatibility for device-wide operations.
  - Add pid-aware scheduling if needed by later tasks.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmFileSystemApi.kt`
  - Resolve paths through a process-local `VmPathResolver`.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
  - Use process-local cwd.
  - Pass the parent pid into `VmProcessManager.spawn(...)`.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
  - Accept parent pid for spawn.
  - Create child runtimes with their real pid and parent pid.
  - Register native processes with real parent pid instead of hardcoded pid 1.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
  - Add quota refill/consume counters.
  - Keep existing historical fields readable.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
  - Include new quota metrics in TSV/Markdown.

## Task 1: Device Execution Quota Foundation

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceExecutionQuota.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceExecutionQuotaTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`

- [ ] **Step 1: Write failing quota tests**

Create `DeviceExecutionQuotaTest.kt` with tests for these behaviors:

```kotlin
@Test
fun refillCapsPendingQuotaAtSingleTickBudget() = runBlocking {
    val quota = DeviceExecutionQuota()

    assertTrue(quota.refill(available = true))
    assertFalse(quota.refill(available = true))

    quota.awaitPermit()

    assertTrue(quota.refill(available = true))
}

@Test
fun refillDoesNotAddQuotaWhenUnavailable() {
    val quota = DeviceExecutionQuota()

    assertFalse(quota.refill(available = false))
}

@Test
fun awaitPermitResumesWhenQuotaArrives() = runBlocking {
    val quota = DeviceExecutionQuota()
    val waiter = async { quota.awaitPermit() }

    assertFalse(waiter.isCompleted)
    assertTrue(quota.refill(available = true))

    withTimeout(1_000) { waiter.await() }
}
```

- [ ] **Step 2: Run focused test and verify RED**

Run:

```bash
./gradlew :core:test --tests '*DeviceExecutionQuotaTest' --rerun-tasks
```

Expected: FAIL because `DeviceExecutionQuota` does not exist.

- [ ] **Step 3: Implement `DeviceExecutionQuota`**

Create `DeviceExecutionQuota.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DeviceExecutionQuota {
    private val permits = Channel<Unit>(capacity = 1)
    private val lock = Mutex()
    private var pending: Boolean = false

    suspend fun awaitPermit() {
        permits.receive()
        lock.withLock {
            pending = false
        }
    }

    fun refill(available: Boolean): Boolean {
        if (!available) return false
        if (pending) return false
        val result = permits.trySend(Unit)
        if (result.isSuccess) {
            pending = true
            return true
        }
        return false
    }
}
```

If `pending` needs stricter synchronization after tests, replace it with `AtomicBoolean`.

- [ ] **Step 4: Replace `slicePermits` in `BackgroundDeviceVm`**

Replace:

```kotlin
private val slicePermits = Channel<Unit>(capacity = 1)
```

with:

```kotlin
private val executionQuota = DeviceExecutionQuota()
```

Update `requestSlice`:

```kotlin
val sent = executionQuota.refill(available = true)
runtimeMetricsCollector.recordSliceRequest(sent = sent, sleepGated = false)
```

Update `awaitSlicePermit`:

```kotlin
executionQuota.awaitPermit()
```

Keep metric names `slicePermitsSent` and `slicePermitsReceived` for historical compatibility in this task.

- [ ] **Step 5: Run focused tests**

Run:

```bash
./gradlew :core:test --tests '*DeviceExecutionQuotaTest' --tests '*BackgroundDeviceVmTest.recordsRuntimeSchedulingMetrics' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceExecutionQuota.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceExecutionQuotaTest.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt
git commit -m "feat: add device execution quota"
```

## Task 2: Process-Local Path Resolver

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmFileSystemApi.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Add failing cwd isolation test**

Add a test where one child changes directory and a second child still sees the parent cwd unchanged. The parent should log
`parent=`, `a=sub`, and `b=` after both children run.

- [ ] **Step 2: Run focused test and verify RED**

Run:

```bash
./gradlew :core:test --tests '*BackgroundDeviceVmTest.processWorkingDirectoryIsProcessLocal' --rerun-tasks
```

Expected: FAIL because the shared path resolver is mutated during runtime creation.

- [ ] **Step 3: Make filesystem/process APIs use a process-local resolver**

Change `VmFileSystemApi` to accept `pathResolver: VmPathResolver` and resolve paths through it instead of
`ctx.resolvePath(path)`.

Change `VmProcessApi` to use the same process-local resolver for `workingDirectory` and `changeDirectory`.

Change `BackgroundDeviceVm.createRuntime(...)` to instantiate a new `VmPathResolver(workingDirectory)` per runtime
instead of mutating the device-level resolver.

- [ ] **Step 4: Run focused test**

Run:

```bash
./gradlew :core:test --tests '*BackgroundDeviceVmTest.processWorkingDirectoryIsProcessLocal' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Run core VM tests**

Run:

```bash
./gradlew :core:test --tests '*BackgroundDeviceVmTest' --tests '*VmProcessManagerTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmFileSystemApi.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
git commit -m "feat: make VM runtime paths process-local"
```

## Task 3: PID-Aware Runtime Creation and Native Registration

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`

- [ ] **Step 1: Add failing parent pid registration test**

Extend `VmProcessManagerTest` so a spawned child can be registered with a non-root parent pid:

```kotlin
val pid = manager.spawn(path = "missing.ck", argument = "", workingDirectory = "", parentPid = 41)
assertEquals(listOf(Triple(2, 41, "missing.ck")), bridge.registrations)
```

- [ ] **Step 2: Run focused test and verify RED**

Run:

```bash
./gradlew :core:test --tests '*VmProcessManagerTest*' --rerun-tasks
```

Expected: FAIL because spawn currently hardcodes parent pid 1.

- [ ] **Step 3: Thread pid through runtime creation**

Change `VmProcessManager` constructor from:

```kotlin
runtimeCreator: (String, String) -> DeviceRuntime
```

to:

```kotlin
runtimeCreator: (Int, Int, String, String) -> DeviceRuntime
```

where parameters are `pid`, `parentPid`, `workingDirectory`, and `argument`.

Add `processId` and `parentProcessId` to `VmRuntime`.

Change `VmProcessApi.spawn(...)` to call:

```kotlin
processManager.spawn(path, argument, workingDirectory, parentPid = processId)
```

Register native process with the real parent pid.

- [ ] **Step 4: Run focused tests**

Run:

```bash
./gradlew :core:test --tests '*VmProcessManagerTest*' --tests '*BackgroundDeviceVmTest.parentCanSpawnChildAndExchangeIpcText' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmProcessApi.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt
git commit -m "feat: thread process identity through runtimes"
```

## Task 4: Quota Profiling Surface

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [ ] **Step 1: Add failing profiling assertions**

Add runtime VM metrics:

- `quotaRefills`;
- `quotaAcceptedRefills`;
- `quotaDeniedRefills`;
- `quotaWaits`.

Add summary line:

```text
  quota: refills=2, accepted=1, denied=1, waits=1
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
./gradlew :core:test --tests '*RuntimeProfilingTest' --rerun-tasks
```

Expected: FAIL because quota metrics do not exist.

- [ ] **Step 3: Implement quota profiling**

Add methods to `RuntimeMetricsCollector`:

```kotlin
fun recordExecutionQuotaRefill(accepted: Boolean)
fun recordExecutionQuotaWait()
```

Record accepted/denied refills from `BackgroundDeviceVm.requestSlice(...)`.

Record waits from `BackgroundDeviceVm.awaitSlicePermit()`.

- [ ] **Step 4: Update report serialization**

Append quota fields after existing process lifecycle fields to preserve historical TSV compatibility. Parsing old rows
must default missing quota fields to zero.

- [ ] **Step 5: Run focused profiling tests**

Run:

```bash
./gradlew :core:test --tests '*RuntimeProfilingTest' --rerun-tasks
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingReportFormatterTest' --tests '*RuntimeVmProfilingProfileCodecTest' --rerun-tasks
```

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt
git commit -m "test: report execution quota metrics"
```

## Task 5: Verification and Runtime Profile

**Files:**
- No production file changes expected unless verification reveals a bug.

- [ ] **Step 1: Run core tests**

```bash
./gradlew :core:test --rerun-tasks
```

Expected: PASS.

- [ ] **Step 2: Run native-enabled focused suite**

```bash
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true --no-parallel :compiler:test :core:test :v1_21_1-common:test :v1_21_1-neoforge:test --rerun-tasks
```

Expected: PASS.

- [ ] **Step 3: Run runtime profile**

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so -Dckl.vm.native.display=true --no-parallel profileRuntimeVmImage
```

Expected: PASS and emit a fresh profiling run path.

- [ ] **Step 4: Commit any verification fixes**

If verification required fixes, commit them with:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm
git commit -m "fix: stabilize device quota scheduler"
```

If there were no changes, do not create an empty commit.

## Task 6: Explicit Runtime Wait States

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTable.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTableTest.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntimeProcessStateTest.kt`

- [x] **Step 1: Add process table wait/sleep/crash states**

Extend `VmProcessState` with:

```kotlin
WaitingEvent(filter: String?)
WaitingIpc(channelId: Int)
Sleeping(untilTick: Long)
Crashed(message: String)
```

Add mark methods and table tests for all new transitions.

- [x] **Step 2: Add runtime state reporter tests**

Add tests proving:

- `pullEvent(filter)` marks the process as `WaitingEvent(filter)` while blocked and returns it to `Runnable` after a
  matching event arrives;
- `sleep(ticks)` marks the process as `Sleeping(untilTick)` while blocked and returns it to `Runnable` after the target
  tick is reached;
- `poll(channel)` marks the process as `WaitingIpc(channel)` while blocked and returns it to `Runnable` after IPC/event
  wakeup.

- [x] **Step 3: Wire runtime state reporter**

Introduce a small process-state reporter passed into each `VmRuntime`. `VmProcessManager` should delegate reporter
updates to `VmProcessTable`, and `BackgroundDeviceVm.createRuntime(...)` should pass the manager to child runtimes.

- [x] **Step 4: Run focused tests**

```bash
./gradlew :core:test --tests '*VmProcessTableTest' --tests '*VmRuntimeProcessStateTest' --tests '*VmProcessManagerTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 6**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTable.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTableTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntimeProcessStateTest.kt
git commit -m "feat: expose explicit VM runtime wait states"
```

## Task 7: Stop Device-Wide Sleep Gating

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [x] **Step 1: Add failing shared-quota sleep test**

Add a test proving that a process sleep marker no longer prevents the device from accepting a tick quota refill. This is
important before round-robin scheduling because one sleeping process must not freeze runnable siblings.

- [x] **Step 2: Remove `requestSlice` sleep gate**

`requestSlice(serverTick)` should always offer quota to `DeviceExecutionQuota` when the device is ticked. Sleep remains a
process/runtime state reported by `VmRuntime.sleep(...)`, not a device-global quota gate.

- [x] **Step 3: Run focused tests**

```bash
./gradlew :core:test --tests '*BackgroundDeviceVmTest.requestSliceDoesNotGateSharedQuotaOnSleepState' --tests '*BackgroundDeviceVmTest.recordsRuntimeSchedulingMetrics' --rerun-tasks
```

Expected: PASS.

- [x] **Step 4: Commit Task 7**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
git commit -m "feat: stop gating device quota on process sleep"
```

## Task 8: Process Table Runnable Queue

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTable.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTableTest.kt`

- [x] **Step 1: Add failing runnable queue tests**

Add tests proving:

- registered runnable processes are visible in insertion order;
- `nextRunnablePid()` rotates runnable processes round-robin;
- waiting, sleeping, exited, and crashed states remove pids from the runnable queue;
- `markRunnable(pid)` requeues an existing process once and does not create duplicates;
- unknown pids are ignored.

- [x] **Step 2: Implement runnable queue in `VmProcessTable`**

Maintain a small synchronized queue plus membership set next to the record map. State transitions should keep queue
membership in sync with `VmProcessState.Runnable`.

- [x] **Step 3: Run focused tests**

```bash
./gradlew :core:test --tests '*VmProcessTableTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 4: Commit Task 8**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTable.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTableTest.kt
git commit -m "feat: add VM process runnable queue"
```

## Task 9: Process Table Sleeper Wakeups

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTable.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTableTest.kt`

- [x] **Step 1: Add failing sleeper wake tests**

Add tests proving `wakeSleepers(currentTick)`:

- wakes only processes in `Sleeping(untilTick)` with `untilTick <= currentTick`;
- returns woken pids in deterministic pid order;
- moves woken processes back to `Runnable` and runnable queue membership;
- leaves future sleepers and non-sleeping states unchanged.

- [x] **Step 2: Implement sleeper wakeups**

Scan process records, find due sleepers, mark each due pid runnable, and return the woken pid list.

- [x] **Step 3: Run focused tests**

```bash
./gradlew :core:test --tests '*VmProcessTableTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 4: Commit Task 9**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTable.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTableTest.kt
git commit -m "feat: wake VM process sleepers by tick"
```

## Task 10: Kotlin Scheduler Tick Facade

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessScheduler.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessSchedulerTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`

- [x] **Step 1: Add failing scheduler tick tests**

Add tests proving a scheduler tick:

- wakes due sleepers through `VmProcessTable.wakeSleepers(currentTick)`;
- selects the next runnable pid through round-robin order;
- returns both the woken pid list and selected runnable pid;
- leaves no selected pid when all processes are waiting, sleeping in the future, exited, or crashed.

- [x] **Step 2: Implement `VmProcessScheduler`**

Create a small Kotlin-first scheduler facade over `VmProcessTable`. It should not execute process bytecode yet; it only
centralizes tick-time process table decisions.

- [x] **Step 3: Wire tick facade into `VmProcessManager` and `BackgroundDeviceVm.requestSlice`**

`VmProcessManager` should own the scheduler and expose `schedulerTick(currentTick)`. `BackgroundDeviceVm.requestSlice`
should call it after updating current tick and before refilling execution quota.

- [x] **Step 4: Run focused tests**

```bash
./gradlew :core:test --tests '*VmProcessSchedulerTest' --tests '*VmProcessManagerTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 10**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessScheduler.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessSchedulerTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt
git commit -m "feat: add VM process scheduler tick facade"
```

## Task 11: Process Waiter Wakeups

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTable.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTableTest.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`

- [x] **Step 1: Add failing process waiter wake tests**

Add tests proving `wakeProcessWaiters(targetPid)`:

- wakes only processes in `WaitingProcess(targetPid)`;
- returns woken pids in deterministic pid order;
- moves woken processes back to `Runnable` and runnable queue membership;
- leaves waiters for other pids unchanged.

- [x] **Step 2: Implement waiter wakeups**

Scan process records for `WaitingProcess(targetPid)`, mark matching waiters runnable, and return the woken pid list.

- [x] **Step 3: Use waiter wakeups on process completion**

When `VmProcessManager` records child exit/crash, call `wakeProcessWaiters(pid)`. The existing coroutine `wait(...)`
fallback should remain as a safety net.

- [x] **Step 4: Run focused tests**

```bash
./gradlew :core:test --tests '*VmProcessTableTest' --tests '*VmProcessManagerTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 11**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTable.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessTableTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt
git commit -m "feat: wake VM process waiters on completion"
```

## Task 12: Scheduler Tick Profiling

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [x] **Step 1: Add failing scheduler metric tests**

Add metrics for:

- scheduler ticks;
- selected runnable ticks;
- idle ticks with no runnable pid;
- total woken processes.

- [x] **Step 2: Record metrics from `VmProcessManager.schedulerTick(...)`**

Record each scheduler tick after `VmProcessScheduler.tick(...)` returns.

- [x] **Step 3: Include metrics in runtime profile reports**

Append scheduler fields after existing execution quota fields in the TSV format. Old rows must decode with zero
scheduler metrics.

- [x] **Step 4: Run focused tests**

```bash
./gradlew :core:test --tests '*RuntimeProfilingTest' --tests '*VmProcessManagerTest.schedulerTickWakesSleepingRootAndSelectsRunnableProcess' --rerun-tasks
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingProfileCodecTest' --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 12**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt
git commit -m "feat: report VM process scheduler metrics"
```

## Task 13: Native Process Scheduler Mirror

**Files:**
- Modify: `native/ckl-vm/src/runtime_kernel.rs`

- [x] **Step 1: Add failing Rust scheduler mirror tests**

Add native kernel tests proving:

- registered processes enter a native runnable queue;
- `scheduler_tick(currentTick)` wakes due sleepers and selects runnable pids round-robin;
- process waiters move back to runnable when the watched pid completes;
- completed processes are removed from native runnable scheduling.

- [x] **Step 2: Implement native process states and runnable queue**

Replace the native `Running` process state with explicit process states matching the Kotlin table shape:

- `Runnable`;
- `WaitingEvent`;
- `WaitingIpc`;
- `WaitingProcess`;
- `Sleeping`;
- `Completed`;
- `Crashed`.

Maintain a native runnable queue plus membership set so state transitions keep scheduling deterministic and duplicate-free.

- [x] **Step 3: Implement native scheduler tick**

Add `ProcessSchedulerTick` and `DeviceRuntimeKernel.scheduler_tick(currentTick)`:

- wake sleepers with `untilTick <= currentTick`;
- select the next runnable pid through round-robin order;
- return current tick, woken pids, and selected pid.

`complete_process(pid, exitCode)` should remove the completed pid from runnable scheduling and wake native process
waiters for that pid.

- [x] **Step 4: Run native tests**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml runtime_kernel
./gradlew :v1_21_1-neoforge:buildRustVmNativeLibrary
```

Expected: PASS.

- [x] **Step 5: Commit Task 13**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  native/ckl-vm/src/runtime_kernel.rs
git commit -m "feat: add native process scheduler mirror"
```

## Task 14: Native Process Scheduler JNI Bridge

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
- Modify: `native/ckl-vm/src/jni.rs`

- [x] **Step 1: Add failing JNI binding tests**

Add tests proving `NativeVmBindings` exposes:

- process state transition methods for runnable, process wait, sleep, and crash;
- a native process scheduler tick method;
- a JVM-friendly `NativeProcessSchedulerTick` result with `currentTick`, `selectedPid`, and `wokenPids`.

- [x] **Step 2: Add Kotlin binding surface**

Add public object methods on `NativeVmBindings`:

- `markProcessRunnable(...)`;
- `markProcessWaitingForProcess(...)`;
- `markProcessSleeping(...)`;
- `markProcessCrashed(...)`;
- `processSchedulerTick(...)`.

Use a compact `LongArray` ABI for the native tick result: `[currentTick, selectedPidOrZero, wokenCount, ...wokenPids]`.

- [x] **Step 3: Add JNI native functions**

Forward the new methods into `DeviceRuntimeKernel` under `with_kernel_mut(...)`.

- [x] **Step 4: Run focused tests**

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 14**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt \
  native/ckl-vm/src/jni.rs
git commit -m "feat: expose native process scheduler bindings"
```

## Task 15: Core Native Process State Bridge

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeProcessBridge.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`
- Modify: `native/ckl-vm/src/jni.rs`

- [x] **Step 1: Add failing bridge sync tests**

Add tests proving process state transitions in `VmProcessManager` are mirrored into `NativeProcessBridge`, and each
manager scheduler tick is also sent to the native bridge.

- [x] **Step 2: Complete native waiting state bindings**

Expose native event-wait and IPC-wait process states through `NativeVmBindings` and JNI so the bridge can mirror all
non-terminal process states.

- [x] **Step 3: Extend `NativeProcessBridge`**

Add bridge methods for runnable, event wait, IPC wait, process wait, sleep, crash, and scheduler tick. The no-op bridge
should keep fallback behavior unchanged.

- [x] **Step 4: Wire `VmProcessManager` state reporting into the native bridge**

After each Kotlin process table state transition, call the matching native bridge method. Keep Kotlin as the source of
truth for this task; native scheduler ticks are mirrored but not used to decide execution yet.

- [x] **Step 5: Run focused tests**

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
./gradlew :core:test --tests '*VmProcessManagerTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 6: Commit Task 15**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/NativeProcessBridge.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt \
  native/ckl-vm/src/jni.rs
git commit -m "feat: mirror process scheduler state to native"
```

## Task 16: Native Root Process Registration

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`

- [x] **Step 1: Add failing root registration expectation**

Update process manager tests so a native bridge sees pid `1` registered with parent pid `0` and the boot script path
during manager initialization.

- [x] **Step 2: Register root process in the native bridge**

After the Kotlin process table registers pid `1`, call `nativeProcessBridge.registerProcess(...)` for the same process.
Record the native process registration metric if the bridge accepts it.

- [x] **Step 3: Run focused tests**

```bash
./gradlew :core:test --tests '*VmProcessManagerTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 4: Commit Task 16**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt
git commit -m "feat: register root process in native scheduler"
```

## Task 17: Native Scheduler Decision Comparison

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`

- [x] **Step 1: Add failing comparison metric tests**

Add tests proving:

- runtime metrics count native scheduler comparisons;
- matching Kotlin/native scheduler ticks increment match count;
- different Kotlin/native scheduler ticks increment mismatch count.

- [x] **Step 2: Record native scheduler comparison metrics**

When `NativeProcessBridge.schedulerTick(currentTick)` returns a tick, compare it with the Kotlin scheduler tick and
record match or mismatch. Keep Kotlin as source of truth in this task.

- [x] **Step 3: Include comparison metrics in runtime summary**

Add the comparison counters to `RuntimeProfilingSnapshot.summary()` so native scheduler readiness is visible during
manual profiling.

- [x] **Step 4: Run focused tests**

```bash
./gradlew :core:test --tests '*RuntimeProfilingTest' --tests '*VmProcessManagerTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 17**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt
git commit -m "feat: compare native process scheduler decisions"
```

## Task 18: PID-Aware Scheduling Point Plumbing

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmContext.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntimeProcessStateTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`

- [x] **Step 1: Add failing process-id scheduling test**

Add a runtime test proving `VmRuntime.yield()` calls the VM context scheduling point with the runtime process id.

- [x] **Step 2: Add process id to `VmContext.schedulingPoint`**

Change the scheduling point signature to accept `processId: Int`, and update runtime call sites to pass their process id.

- [x] **Step 3: Thread process id through `BackgroundDeviceVm` scheduling helpers**

Change `applySchedulingPoint` and `awaitSlicePermit` to accept a process id. The quota may still ignore the pid in this
task; this is plumbing for the next task.

- [x] **Step 4: Run focused tests**

```bash
./gradlew :core:test --tests '*VmRuntimeProcessStateTest' --tests '*VmProcessManagerTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 18**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmContext.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntimeProcessStateTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt
git commit -m "feat: pass process id through scheduling points"
```

## Task 19: PID-Aware Device Execution Quota

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceExecutionQuota.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceExecutionQuotaTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [x] **Step 1: Add failing pid-aware quota tests**

Add tests proving:

- a permit is consumed only by the selected pid;
- a waiter for another pid remains suspended;
- a null selected pid does not refill quota.

- [x] **Step 2: Make `DeviceExecutionQuota` pid-aware**

Change `refill(...)` to accept `selectedPid: Int?` and `awaitPermit(...)` to accept `processId: Int`.
Keep the one-pending-per-device cap.

- [x] **Step 3: Refill quota from scheduler selected pid**

In `BackgroundDeviceVm.requestSlice`, use `processManager.schedulerTick(serverTick).selectedPid` as the selected pid
for quota refill. Pass process ids into `awaitSlicePermit`.

- [x] **Step 4: Run focused tests**

```bash
./gradlew :core:test --tests '*DeviceExecutionQuotaTest' --tests '*BackgroundDeviceVmTest.requestSliceDoesNotGateSharedQuotaOnSleepState' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 19**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceExecutionQuota.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceExecutionQuotaTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
git commit -m "feat: make device execution quota pid-aware"
```

## Task 20: Native Scheduler Comparison In Profiling Reports

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [x] **Step 1: Add failing report tests**

Extend profile codec and Markdown formatter tests so native scheduler comparison, match, and mismatch counters are
written, read, and displayed.

- [x] **Step 2: Extend runtime VM TSV fields**

Append native scheduler comparison fields after process scheduler fields. Preserve old-row compatibility by decoding
missing values as zero.

- [x] **Step 3: Extend Markdown reports**

Add per-run and historical rows for native scheduler comparisons, matches, and mismatches.

- [x] **Step 4: Run focused report tests**

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingProfileCodecTest' --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 20**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt
git commit -m "feat: report native scheduler comparison metrics"
```

## Task 21: Guarded Native Scheduler Source

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`

- [x] **Step 1: Add failing native scheduler source metric tests**

Add tests proving:

- matching native scheduler ticks are counted as accepted native scheduler ticks;
- mismatched native scheduler ticks are counted as Kotlin fallback ticks;
- no native tick leaves both counters unchanged.

- [x] **Step 2: Add native scheduler source metrics**

Extend runtime VM metrics with accepted native scheduler ticks and Kotlin fallback scheduler ticks.

- [x] **Step 3: Use native tick only when it matches**

Keep executing the Kotlin scheduler tick for state-table parity, but when native tick equals the Kotlin tick, return the
native tick as the effective scheduler decision. On mismatch, return Kotlin tick.

- [x] **Step 4: Run focused tests**

```bash
./gradlew :core:test --tests '*RuntimeProfilingTest' --tests '*VmProcessManagerTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 21**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt
git commit -m "feat: use guarded native scheduler decisions"
```

## Task 22: Native Scheduler Source In Profiling Reports

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [x] **Step 1: Add failing report tests for native scheduler source metrics**

Add codec and Markdown expectations for accepted native scheduler ticks and Kotlin fallback scheduler ticks.

- [x] **Step 2: Extend profile TSV codec**

Append the two new `runtimeVm` fields and keep old profile rows backward-compatible with zero defaults.

- [x] **Step 3: Extend Markdown reports**

Show accepted/fallback rows in per-run and historical comparison reports.

- [x] **Step 4: Run focused report tests**

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingProfileCodecTest' --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 22**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt
git commit -m "feat: report native scheduler source metrics"
```

## Task 23: Strict Native Scheduler Parity Guard

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt`

- [x] **Step 1: Add failing strict parity tests**

Add tests proving:

- strict mode throws when Rust and Kotlin scheduler ticks differ;
- strict mode still accepts matching native ticks;
- default non-strict mode keeps Kotlin fallback behavior on mismatch.

- [x] **Step 2: Add strict parity option**

Add a `strictNativeSchedulerParity` option to `VmProcessManager`, defaulting to false.

- [x] **Step 3: Wire system property from `BackgroundDeviceVm`**

Pass `System.getProperty("ckl.vm.native.scheduler.strict") == "true"` into `VmProcessManager`.

- [x] **Step 4: Run focused scheduler tests**

```bash
./gradlew :core:test --tests '*VmProcessManagerTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 23**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManagerTest.kt
git commit -m "feat: guard native scheduler parity"
```

## Task 24: Native Device Execution Quota Snapshot

**Files:**
- Modify: `native/ckl-vm/src/runtime_kernel.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [x] **Step 1: Add failing native quota tests**

Add tests proving:

- Rust kernel accepts an execution quota snapshot with instruction budget, wall-clock budget, and server tick;
- repeated quota refills replace the previous per-tick budget instead of accumulating CPU debt;
- Kotlin JNI bindings expose `addDeviceExecutionQuota(...)` and decode the returned snapshot.

- [x] **Step 2: Add Rust kernel quota state**

Store a bounded `DeviceExecutionQuotaSnapshot` in `DeviceRuntimeKernel`. Clamp negative budgets to zero and replace the
previous budget on each refill.

- [x] **Step 3: Add JNI and Kotlin bindings**

Expose `addDeviceExecutionQuotaNative(...)` returning a `LongArray` snapshot, and a Kotlin wrapper returning a typed
`NativeDeviceExecutionQuota`.

- [x] **Step 4: Run focused native quota tests**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml execution_quota
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 24**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  native/ckl-vm/src/runtime_kernel.rs \
  native/ckl-vm/src/jni.rs \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "feat: add native device execution quota"
```

## Task 25: Mirror JVM Tick Quota To Native Kernel

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [x] **Step 1: Add failing native quota refill tests**

Add tests proving `BackgroundDeviceVm.requestSlice(serverTick)` mirrors the profile CPU instruction budget,
wall-clock budget, and server tick into the native device kernel when one is attached.

- [x] **Step 2: Add native quota refill metrics**

Track native execution quota refill count, total returned instruction budget, total returned wall-clock budget, and last
returned server tick in `RuntimeVmMetrics`.

- [x] **Step 3: Wire `requestSlice` to native quota**

Before the scheduler decision, call `NativeVmBindings.addDeviceExecutionQuota(...)` under the native kernel read lock
and record the returned snapshot.

- [x] **Step 4: Run focused tests**

```bash
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :core:test --tests '*RuntimeProfilingTest' --tests '*BackgroundDeviceVmTest.requestSliceMirrorsExecutionQuotaToNativeKernelWhenConfigured' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 25**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
git commit -m "feat: mirror device quota to native kernel"
```

## Task 26: Native Execution Quota In Profiling Reports

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt`

- [x] **Step 1: Add failing report tests for native quota metrics**

Add codec and Markdown expectations for native execution quota refill count, returned instruction budget, returned
wall-clock budget, and last server tick.

- [x] **Step 2: Extend profile TSV codec**

Append native execution quota fields to `runtimeVm` rows and keep old rows backward-compatible with zero defaults.

- [x] **Step 3: Extend Markdown reports**

Show native execution quota rows in per-run and historical comparison reports.

- [x] **Step 4: Run focused report tests**

```bash
./gradlew :v1_21_1-neoforge:test --tests '*RuntimeVmProfilingProfileCodecTest' --tests '*RuntimeVmProfilingReportFormatterTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 26**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReport.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingProfileCodecTest.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeVmProfilingReportFormatterTest.kt
git commit -m "feat: report native execution quota metrics"
```

## Task 27: Native Device Scheduler Dry Run

**Files:**
- Modify: `native/ckl-vm/src/runtime_kernel.rs`
- Modify: `native/ckl-vm/src/jni.rs`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt`
- Modify: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt`

- [x] **Step 1: Add failing dry-run scheduler tests**

Add tests proving:

- Rust can produce a bounded device scheduler plan from the current execution quota;
- the dry run uses round-robin runnable pids;
- the dry run does not mutate the real native process scheduler state;
- Kotlin JNI bindings expose and decode the dry-run result.

- [x] **Step 2: Add Rust dry-run result and algorithm**

Add `DeviceSchedulerDryRun` with server tick, turn count, remaining instructions, and selected pids. Treat one dry-run
turn as one instruction for now.

- [x] **Step 3: Add JNI and Kotlin bindings**

Expose `runDeviceSchedulerDryRunNative(...)` returning a `LongArray`, and decode it into `NativeDeviceSchedulerDryRun`.

- [x] **Step 4: Run focused dry-run tests**

```bash
cargo test --manifest-path native/ckl-vm/Cargo.toml scheduler_dry_run
./gradlew -Dckl.vm.native.library=/home/lazyhat/IdeaProjects/Compukter-Kraft/native/ckl-vm/target/debug/libckl_vm.so :compiler:test --tests '*NativeImageVmBindingsJniTest' --rerun-tasks
```

Expected: PASS.

- [x] **Step 5: Commit Task 27**

```bash
git add docs/superpowers/plans/2026-05-10-device-quota-process-scheduler.md \
  native/ckl-vm/src/runtime_kernel.rs \
  native/ckl-vm/src/jni.rs \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeVmBindings.kt \
  modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/runtime/blazing/NativeImageVmBindingsJniTest.kt
git commit -m "feat: add native scheduler dry run"
```

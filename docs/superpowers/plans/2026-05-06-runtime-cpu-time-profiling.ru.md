# План реализации runtime CPU-time profiling

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить no-op-by-default runtime CPU-time profiling для server tick phases и VM scheduling/execution diagnostics.

**Architecture:** Добавляется runtime metrics collector в `:core`, он прокидывается через `DeviceVmSupervisor`, `DeviceManager`, `RuntimeDeviceImpl` и `BackgroundDeviceVm`, затем bundled ROM terminal profiling scenario печатает display и runtime summaries. Pass остаётся profiling-only: он записывает timings/counters, не меняя rendering behavior и не добавляя CKL APIs.

**Tech Stack:** Kotlin, Gradle, kotlin.test/JUnit 5, coroutines, существующие `BackgroundDeviceVm`, `RuntimeDeviceImpl` и display profiling tests.

---

## File structure

- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt` — runtime timing/scheduling metrics model и collectors.
- Create `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt` — unit tests для collector.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt` — VM-side request/scheduling/execution diagnostics.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceVmSupervisor.kt` — optional runtime collector для новых VM handles.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceManager.kt` — pass-through optional runtime collector.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt` — server tick phase timings.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt` — VM-side metrics regression.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImplDisplayTest.kt` — server tick metrics regression.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt` — combined display + runtime summary.
- Modify `docs/ARCHITECTURE.md` — runtime CPU-time profiling docs.

---

### Task 1: Add runtime profiling metrics model

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`

- [ ] **Step 1: Write the failing collector tests**

Create `RuntimeProfilingTest.kt` with exact tests from the English plan. The tests must verify:

```kotlin
val collector = RecordingRuntimeMetricsCollector()
collector.recordServerTick(nanos = 100)
collector.recordRequestSlice(nanos = 10)
collector.recordHostCallDrain(callCount = 2, nanos = 20)
collector.recordHostCallDispatch(callCount = 2, nanos = 30)
collector.recordHostResultDelivery(resultCount = 2, nanos = 40)
collector.recordDisplayFrameDrain(frameCount = 3, nanos = 50)
collector.recordDisplayFlush(frameCount = 3, nanos = 60)
collector.recordSliceRequest(sent = true, sleepGated = false)
collector.recordSliceRequest(sent = false, sleepGated = true)
collector.recordSlicePermitReceived()
collector.recordSchedulingPoint(waitedForSlice = false)
collector.recordSchedulingPoint(waitedForSlice = true)
collector.recordVmExecutionWindow(nanos = 70)
```

Assert exact accumulated values in `snapshot.tick` and `snapshot.vm`, and assert that `NoOpRuntimeMetricsCollector.snapshot()` equals `RuntimeProfilingSnapshot()` after record calls.

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingTest
```

Expected: FAIL with unresolved references for runtime profiling types.

- [ ] **Step 3: Implement `RuntimeProfiling.kt`**

Create:

```kotlin
interface RuntimeMetricsCollector {
    fun recordServerTick(nanos: Long)
    fun recordRequestSlice(nanos: Long)
    fun recordHostCallDrain(callCount: Int, nanos: Long)
    fun recordHostCallDispatch(callCount: Int, nanos: Long)
    fun recordHostResultDelivery(resultCount: Int, nanos: Long)
    fun recordDisplayFrameDrain(frameCount: Int, nanos: Long)
    fun recordDisplayFlush(frameCount: Int, nanos: Long)
    fun recordSliceRequest(sent: Boolean, sleepGated: Boolean)
    fun recordSlicePermitReceived()
    fun recordSchedulingPoint(waitedForSlice: Boolean)
    fun recordVmExecutionWindow(nanos: Long)
    fun snapshot(): RuntimeProfilingSnapshot
}
```

Add `RuntimeTickMetrics`, `RuntimeVmMetrics`, `RuntimeProfilingSnapshot.summary()`, `NoOpRuntimeMetricsCollector`, and `RecordingRuntimeMetricsCollector` with atomic counters. Use the exact fields and method names from the English plan so later tasks compile.

Add GPL headers to both new Kotlin files.

- [ ] **Step 4: Run GREEN**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt
git commit -m "feat: add runtime profiling metrics"
```

---

### Task 2: Wire VM-side scheduling and execution diagnostics

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt`

- [ ] **Step 1: Add failing VM metrics test**

In `BackgroundDeviceVmTest.kt`, import `RecordingRuntimeMetricsCollector` and add `recordsRuntimeSchedulingMetrics`. The test creates `BackgroundDeviceVm(... runtimeMetricsCollector = metrics)` with firmware:

```ck
pub fun main() {
    var count: Int = 0;
    while count < 3 {
        count = count + 1;
        sleep(1L);
    }
}
```

After `vm.boot()` and `runVmTicks(vm, ticks = 12)`, assert:

```kotlin
assertTrue(snapshot.vm.sliceRequests > 0, snapshot.summary())
assertTrue(snapshot.vm.slicePermitsSent > 0, snapshot.summary())
assertTrue(snapshot.vm.slicePermitsReceived > 0, snapshot.summary())
assertTrue(snapshot.vm.schedulingPoints > 0, snapshot.summary())
assertTrue(snapshot.vm.executionWindows > 0, snapshot.summary())
assertTrue(snapshot.vm.executionWindowNanos > 0, snapshot.summary())
```

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest.recordsRuntimeSchedulingMetrics
```

Expected: FAIL until `BackgroundDeviceVm` accepts and records runtime metrics.

- [ ] **Step 3: Wire collector into `BackgroundDeviceVm`**

Add imports for `NoOpRuntimeMetricsCollector` and `RuntimeMetricsCollector`. Add constructor parameter:

```kotlin
private val runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
```

Add `executionWindowStartedNanos: Long?`, `finishExecutionWindow()`, record slice requests in `requestSlice`, record permit receipt and execution-window start in `awaitSlicePermit`, record scheduling points in `applySchedulingPoint`, and call `finishExecutionWindow()` at the beginning of `stopInternal()`.

Use the exact implementation snippets from the English plan for these methods.

- [ ] **Step 4: Run GREEN**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest.recordsRuntimeSchedulingMetrics
```

Expected: PASS.

- [ ] **Step 5: Run core VM tests**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit Task 2**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt
git commit -m "feat: record vm runtime profiling metrics"
```

---

### Task 3: Wire server tick phase metrics

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceVmSupervisor.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceManager.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImplDisplayTest.kt`

- [ ] **Step 1: Add failing RuntimeDeviceImpl test**

Add `recordsServerTickRuntimeMetrics` to `RuntimeDeviceImplDisplayTest.kt`. It constructs `RuntimeDeviceImpl(... runtimeMetricsCollector = metrics)`, attaches a display session, calls `turnOn()` and `serverTick()`, then asserts:

```kotlin
assertEquals(1, snapshot.tick.serverTickCalls)
assertEquals(1, snapshot.tick.requestSliceCalls)
assertEquals(1, snapshot.tick.hostCallDrainCalls)
assertEquals(1, snapshot.tick.hostCallDispatchCalls)
assertTrue(snapshot.tick.displayFrameDrainCalls > 0, snapshot.summary())
assertTrue(snapshot.tick.displayFlushCalls > 0, snapshot.summary())
assertTrue(snapshot.tick.serverTickNanos > 0, snapshot.summary())
assertTrue(snapshot.vm.sliceRequests > 0, snapshot.summary())
```

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest.recordsServerTickRuntimeMetrics
```

Expected: FAIL until `RuntimeDeviceImpl` accepts and records runtime metrics.

- [ ] **Step 3: Pass collector through VM creation**

Update `DeviceVmSupervisor.getOrCreate(...)` and `DeviceManager.getOrCreateVm(...)` to accept `runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector`, then pass it into `BackgroundDeviceVm`.

- [ ] **Step 4: Record phases in `RuntimeDeviceImpl`**

Add `runtimeMetricsCollector` constructor parameter with no-op default. Add `measureNanos`. In `serverTick()`, measure request slice, drain host calls, dispatch host calls, deliver results, display flush, and total tick. Change `flushDisplaySessions(handle)` to return the number of flushed frames and record display frame drain duration/count.

Use method and field names from Task 1 exactly.

- [ ] **Step 5: Run GREEN**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest.recordsServerTickRuntimeMetrics
```

Expected: PASS.

- [ ] **Step 6: Run runtime core tests**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceNoScreenSnapshotTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit Task 3**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceVmSupervisor.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceManager.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImplDisplayTest.kt
git commit -m "feat: record runtime tick profiling metrics"
```

---

### Task 4: Extend bundled profiling workload and documentation

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt`
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Extend integration test with runtime metrics**

In `RuntimeDisplayProfilingTest.kt`, import `RecordingRuntimeMetricsCollector`, create `displayMetrics` and `runtimeMetrics`, pass both to `BackgroundDeviceVm`, and update `runTicks` to record request/drain/dispatch/deliver/serverTick durations before `delay(10)`. Measure final `vm.drainDisplayFrames()` via `recordDisplayFrameDrain`. Print both summaries:

```kotlin
println(displaySnapshot.summary())
println(runtimeSnapshot.summary())
```

Assert non-zero runtime counters for server ticks, request slice, host drain/dispatch/deliver, display drain, VM slice requests, VM permits received, and VM execution nanoseconds.

- [ ] **Step 2: Run profiling integration test**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest --info
```

Expected: PASS and output both `display:` and `runtime:` summary lines.

- [ ] **Step 3: Document runtime CPU-time profiling hooks**

In `docs/ARCHITECTURE.md`, extend runtime profiling docs with:

```markdown
Runtime CPU-time profiling is also available through optional runtime metrics collectors. These collectors can measure server tick phases, host-call dispatch, display frame drain/flush work, and coarse VM scheduling/execution diagnostics. Timing output is diagnostic and should guide optimization design; it is not a strict CI performance budget.
```

- [ ] **Step 4: Run focused behavior tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest --tests ru.lazyhat.compukterkraft.impl.computer.vm.BackgroundDeviceVmTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit Task 4**

Run:

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt \
    docs/ARCHITECTURE.md
git commit -m "test: add runtime cpu profiling baseline"
```

---

### Task 5: Final verification

**Files:**
- No new files.

- [ ] **Step 1: Run full test suite**

Run:

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Audit forbidden terminal/stdout APIs**

Run:

```bash
rg 'terminal::|stdout::|DeviceTerminalApi|DeviceStdioApi|VmTerminalApi|ComputerStdioBroadcaster|ScreenBufferVtSink' modules/compiler/src/main modules/core/src/main modules/v1_21_1/v1_21_1-common/src/main modules/v1_21_1/v1_21_1-neoforge/src/main/resources
```

Expected: no output, exit code 1.

- [ ] **Step 3: Check branch status**

Run:

```bash
git status --short
```

Expected: no output.

---

## Final verification checklist

- [ ] `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingTest` passes.
- [ ] `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest.recordsRuntimeSchedulingMetrics` passes.
- [ ] `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest.recordsServerTickRuntimeMetrics` passes.
- [ ] `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest --info` passes and prints display + runtime summaries.
- [ ] `./gradlew test` passes.
- [ ] Forbidden terminal/stdout audit has no production/resource matches.
- [ ] Branch remains profiling-only and contains no ROM terminal optimizations.

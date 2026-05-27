# Runtime CPU-time Profiling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add no-op-by-default runtime CPU-time profiling for server tick phases and VM scheduling/execution diagnostics.

**Architecture:** Add a runtime metrics collector in `:core`, wire it through `DeviceVmSupervisor`, `DeviceManager`, `RuntimeDeviceImpl`, and `BackgroundDeviceVm`, then extend the bundled ROM terminal profiling scenario to print display and runtime summaries. The pass is profiling-only: it records timings and counters without changing rendering behavior or adding CKL APIs.

**Tech Stack:** Kotlin, Gradle, kotlin.test/JUnit 5, coroutines, existing `BackgroundDeviceVm`, `RuntimeDeviceImpl`, and display profiling test infrastructure.

---

## File structure

- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt` — runtime timing/scheduling metrics model and collectors.
- Create `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt` — collector unit tests.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt` — record VM-side request/scheduling/execution diagnostics.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/DeviceVmSupervisor.kt` — pass optional runtime collector into new VM handles.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceManager.kt` — pass optional runtime collector through VM creation.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt` — record server tick phase timings.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmTest.kt` — verify VM-side metrics are recorded.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImplDisplayTest.kt` — verify server tick metrics are recorded.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt` — print combined display + runtime profiling summary.
- Modify `docs/ARCHITECTURE.md` — document runtime CPU-time profiling hooks.

---

### Task 1: Add runtime profiling metrics model

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`

- [ ] **Step 1: Write the failing collector tests**

Create `RuntimeProfilingTest.kt` with tests for recording and no-op behavior:

```kotlin
package ru.lazyhat.compukterkraft.core.device.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeProfilingTest {
    @Test
    fun recordingCollectorAccumulatesRuntimeAndVmMetrics() {
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

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.tick.serverTickCalls)
        assertEquals(100, snapshot.tick.serverTickNanos)
        assertEquals(1, snapshot.tick.requestSliceCalls)
        assertEquals(10, snapshot.tick.requestSliceNanos)
        assertEquals(1, snapshot.tick.hostCallDrainCalls)
        assertEquals(2, snapshot.tick.hostCallsDrained)
        assertEquals(20, snapshot.tick.hostCallDrainNanos)
        assertEquals(1, snapshot.tick.hostCallDispatchCalls)
        assertEquals(2, snapshot.tick.hostCallsDispatched)
        assertEquals(30, snapshot.tick.hostCallDispatchNanos)
        assertEquals(1, snapshot.tick.hostResultDeliveryCalls)
        assertEquals(2, snapshot.tick.hostResultsDelivered)
        assertEquals(40, snapshot.tick.hostResultDeliveryNanos)
        assertEquals(1, snapshot.tick.displayFrameDrainCalls)
        assertEquals(3, snapshot.tick.displayFramesDrained)
        assertEquals(50, snapshot.tick.displayFrameDrainNanos)
        assertEquals(1, snapshot.tick.displayFlushCalls)
        assertEquals(3, snapshot.tick.displayFramesFlushed)
        assertEquals(60, snapshot.tick.displayFlushNanos)
        assertEquals(2, snapshot.vm.sliceRequests)
        assertEquals(1, snapshot.vm.slicePermitsSent)
        assertEquals(1, snapshot.vm.sleepGatedSliceRequests)
        assertEquals(1, snapshot.vm.slicePermitsReceived)
        assertEquals(2, snapshot.vm.schedulingPoints)
        assertEquals(1, snapshot.vm.yieldSchedulingPoints)
        assertEquals(1, snapshot.vm.waitForSliceSchedulingPoints)
        assertEquals(1, snapshot.vm.executionWindows)
        assertEquals(70, snapshot.vm.executionWindowNanos)
        assertTrue(snapshot.summary().contains("runtime:"))
        assertTrue(snapshot.summary().contains("vm:"))
    }

    @Test
    fun noopCollectorKeepsEmptySnapshot() {
        val collector = NoOpRuntimeMetricsCollector

        collector.recordServerTick(nanos = 100)
        collector.recordRequestSlice(nanos = 10)
        collector.recordHostCallDrain(callCount = 2, nanos = 20)
        collector.recordHostCallDispatch(callCount = 2, nanos = 30)
        collector.recordHostResultDelivery(resultCount = 2, nanos = 40)
        collector.recordDisplayFrameDrain(frameCount = 3, nanos = 50)
        collector.recordDisplayFlush(frameCount = 3, nanos = 60)
        collector.recordSliceRequest(sent = true, sleepGated = false)
        collector.recordSlicePermitReceived()
        collector.recordSchedulingPoint(waitedForSlice = true)
        collector.recordVmExecutionWindow(nanos = 70)

        assertEquals(RuntimeProfilingSnapshot(), collector.snapshot())
    }
}
```

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingTest
```

Expected: FAIL with unresolved references for `RecordingRuntimeMetricsCollector`, `NoOpRuntimeMetricsCollector`, and `RuntimeProfilingSnapshot`.

- [ ] **Step 3: Implement `RuntimeProfiling.kt`**

Create `RuntimeProfiling.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.runtime

import java.util.concurrent.atomic.AtomicLong

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

data class RuntimeTickMetrics(
    val serverTickCalls: Long = 0,
    val serverTickNanos: Long = 0,
    val requestSliceCalls: Long = 0,
    val requestSliceNanos: Long = 0,
    val hostCallDrainCalls: Long = 0,
    val hostCallsDrained: Long = 0,
    val hostCallDrainNanos: Long = 0,
    val hostCallDispatchCalls: Long = 0,
    val hostCallsDispatched: Long = 0,
    val hostCallDispatchNanos: Long = 0,
    val hostResultDeliveryCalls: Long = 0,
    val hostResultsDelivered: Long = 0,
    val hostResultDeliveryNanos: Long = 0,
    val displayFrameDrainCalls: Long = 0,
    val displayFramesDrained: Long = 0,
    val displayFrameDrainNanos: Long = 0,
    val displayFlushCalls: Long = 0,
    val displayFramesFlushed: Long = 0,
    val displayFlushNanos: Long = 0,
)

data class RuntimeVmMetrics(
    val sliceRequests: Long = 0,
    val slicePermitsSent: Long = 0,
    val sleepGatedSliceRequests: Long = 0,
    val slicePermitsReceived: Long = 0,
    val schedulingPoints: Long = 0,
    val yieldSchedulingPoints: Long = 0,
    val waitForSliceSchedulingPoints: Long = 0,
    val executionWindows: Long = 0,
    val executionWindowNanos: Long = 0,
)

data class RuntimeProfilingSnapshot(
    val tick: RuntimeTickMetrics = RuntimeTickMetrics(),
    val vm: RuntimeVmMetrics = RuntimeVmMetrics(),
) {
    fun summary(): String =
        "runtime: serverTicks=${tick.serverTickCalls}, serverTickNanos=${tick.serverTickNanos}, " +
            "requestSliceCalls=${tick.requestSliceCalls}, requestSliceNanos=${tick.requestSliceNanos}\n" +
            "host: drainedCalls=${tick.hostCallsDrained}, dispatchedCalls=${tick.hostCallsDispatched}, " +
            "deliveredResults=${tick.hostResultsDelivered}, drainNanos=${tick.hostCallDrainNanos}, " +
            "dispatchNanos=${tick.hostCallDispatchNanos}, deliverNanos=${tick.hostResultDeliveryNanos}\n" +
            "display-runtime: drainFrames=${tick.displayFramesDrained}, drainNanos=${tick.displayFrameDrainNanos}, " +
            "flushCalls=${tick.displayFlushCalls}, flushFrames=${tick.displayFramesFlushed}, flushNanos=${tick.displayFlushNanos}\n" +
            "vm: sliceRequests=${vm.sliceRequests}, slicePermits=${vm.slicePermitsSent}, " +
            "sleepGated=${vm.sleepGatedSliceRequests}, permitsReceived=${vm.slicePermitsReceived}, " +
            "schedulingPoints=${vm.schedulingPoints}, yieldPoints=${vm.yieldSchedulingPoints}, " +
            "waitPoints=${vm.waitForSliceSchedulingPoints}, executionWindows=${vm.executionWindows}, " +
            "executionNanos=${vm.executionWindowNanos}"
}

object NoOpRuntimeMetricsCollector : RuntimeMetricsCollector {
    override fun recordServerTick(nanos: Long) = Unit
    override fun recordRequestSlice(nanos: Long) = Unit
    override fun recordHostCallDrain(callCount: Int, nanos: Long) = Unit
    override fun recordHostCallDispatch(callCount: Int, nanos: Long) = Unit
    override fun recordHostResultDelivery(resultCount: Int, nanos: Long) = Unit
    override fun recordDisplayFrameDrain(frameCount: Int, nanos: Long) = Unit
    override fun recordDisplayFlush(frameCount: Int, nanos: Long) = Unit
    override fun recordSliceRequest(sent: Boolean, sleepGated: Boolean) = Unit
    override fun recordSlicePermitReceived() = Unit
    override fun recordSchedulingPoint(waitedForSlice: Boolean) = Unit
    override fun recordVmExecutionWindow(nanos: Long) = Unit
    override fun snapshot(): RuntimeProfilingSnapshot = RuntimeProfilingSnapshot()
}

class RecordingRuntimeMetricsCollector : RuntimeMetricsCollector {
    private val serverTickCalls = AtomicLong()
    private val serverTickNanos = AtomicLong()
    private val requestSliceCalls = AtomicLong()
    private val requestSliceNanos = AtomicLong()
    private val hostCallDrainCalls = AtomicLong()
    private val hostCallsDrained = AtomicLong()
    private val hostCallDrainNanos = AtomicLong()
    private val hostCallDispatchCalls = AtomicLong()
    private val hostCallsDispatched = AtomicLong()
    private val hostCallDispatchNanos = AtomicLong()
    private val hostResultDeliveryCalls = AtomicLong()
    private val hostResultsDelivered = AtomicLong()
    private val hostResultDeliveryNanos = AtomicLong()
    private val displayFrameDrainCalls = AtomicLong()
    private val displayFramesDrained = AtomicLong()
    private val displayFrameDrainNanos = AtomicLong()
    private val displayFlushCalls = AtomicLong()
    private val displayFramesFlushed = AtomicLong()
    private val displayFlushNanos = AtomicLong()
    private val sliceRequests = AtomicLong()
    private val slicePermitsSent = AtomicLong()
    private val sleepGatedSliceRequests = AtomicLong()
    private val slicePermitsReceived = AtomicLong()
    private val schedulingPoints = AtomicLong()
    private val yieldSchedulingPoints = AtomicLong()
    private val waitForSliceSchedulingPoints = AtomicLong()
    private val executionWindows = AtomicLong()
    private val executionWindowNanos = AtomicLong()

    override fun recordServerTick(nanos: Long) {
        serverTickCalls.incrementAndGet()
        serverTickNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordRequestSlice(nanos: Long) {
        requestSliceCalls.incrementAndGet()
        requestSliceNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordHostCallDrain(callCount: Int, nanos: Long) {
        hostCallDrainCalls.incrementAndGet()
        hostCallsDrained.addAndGet(callCount.coerceAtLeast(0).toLong())
        hostCallDrainNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordHostCallDispatch(callCount: Int, nanos: Long) {
        hostCallDispatchCalls.incrementAndGet()
        hostCallsDispatched.addAndGet(callCount.coerceAtLeast(0).toLong())
        hostCallDispatchNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordHostResultDelivery(resultCount: Int, nanos: Long) {
        hostResultDeliveryCalls.incrementAndGet()
        hostResultsDelivered.addAndGet(resultCount.coerceAtLeast(0).toLong())
        hostResultDeliveryNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordDisplayFrameDrain(frameCount: Int, nanos: Long) {
        displayFrameDrainCalls.incrementAndGet()
        displayFramesDrained.addAndGet(frameCount.coerceAtLeast(0).toLong())
        displayFrameDrainNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordDisplayFlush(frameCount: Int, nanos: Long) {
        displayFlushCalls.incrementAndGet()
        displayFramesFlushed.addAndGet(frameCount.coerceAtLeast(0).toLong())
        displayFlushNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordSliceRequest(sent: Boolean, sleepGated: Boolean) {
        sliceRequests.incrementAndGet()
        if (sent) slicePermitsSent.incrementAndGet()
        if (sleepGated) sleepGatedSliceRequests.incrementAndGet()
    }

    override fun recordSlicePermitReceived() {
        slicePermitsReceived.incrementAndGet()
    }

    override fun recordSchedulingPoint(waitedForSlice: Boolean) {
        schedulingPoints.incrementAndGet()
        if (waitedForSlice) waitForSliceSchedulingPoints.incrementAndGet() else yieldSchedulingPoints.incrementAndGet()
    }

    override fun recordVmExecutionWindow(nanos: Long) {
        executionWindows.incrementAndGet()
        executionWindowNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun snapshot(): RuntimeProfilingSnapshot =
        RuntimeProfilingSnapshot(
            tick = RuntimeTickMetrics(
                serverTickCalls = serverTickCalls.get(),
                serverTickNanos = serverTickNanos.get(),
                requestSliceCalls = requestSliceCalls.get(),
                requestSliceNanos = requestSliceNanos.get(),
                hostCallDrainCalls = hostCallDrainCalls.get(),
                hostCallsDrained = hostCallsDrained.get(),
                hostCallDrainNanos = hostCallDrainNanos.get(),
                hostCallDispatchCalls = hostCallDispatchCalls.get(),
                hostCallsDispatched = hostCallsDispatched.get(),
                hostCallDispatchNanos = hostCallDispatchNanos.get(),
                hostResultDeliveryCalls = hostResultDeliveryCalls.get(),
                hostResultsDelivered = hostResultsDelivered.get(),
                hostResultDeliveryNanos = hostResultDeliveryNanos.get(),
                displayFrameDrainCalls = displayFrameDrainCalls.get(),
                displayFramesDrained = displayFramesDrained.get(),
                displayFrameDrainNanos = displayFrameDrainNanos.get(),
                displayFlushCalls = displayFlushCalls.get(),
                displayFramesFlushed = displayFramesFlushed.get(),
                displayFlushNanos = displayFlushNanos.get(),
            ),
            vm = RuntimeVmMetrics(
                sliceRequests = sliceRequests.get(),
                slicePermitsSent = slicePermitsSent.get(),
                sleepGatedSliceRequests = sleepGatedSliceRequests.get(),
                slicePermitsReceived = slicePermitsReceived.get(),
                schedulingPoints = schedulingPoints.get(),
                yieldSchedulingPoints = yieldSchedulingPoints.get(),
                waitForSliceSchedulingPoints = waitForSliceSchedulingPoints.get(),
                executionWindows = executionWindows.get(),
                executionWindowNanos = executionWindowNanos.get(),
            ),
        )
}
```

Add the standard GPL header to both new Kotlin files before committing.

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

Add imports in `BackgroundDeviceVmTest.kt`:

```kotlin
import ru.lazyhat.compukterkraft.core.device.runtime.RecordingRuntimeMetricsCollector
```

Add this test to `BackgroundDeviceVmTest`:

```kotlin
    @Test
    fun recordsRuntimeSchedulingMetrics() {
        runtimeTestWorkspace("vm-runtime-profiling") { workspace ->
            val metrics = RecordingRuntimeMetricsCollector()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace.host,
                    firmwareLoader =
                        StaticFirmwareLoader(
                            """
                            pub fun main() {
                                var count: Int = 0;
                                while count < 3 {
                                    count = count + 1;
                                    sleep(1L);
                                }
                            }
                            """.trimIndent(),
                        ),
                    runtimeMetricsCollector = metrics,
                )

            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 12)

            val snapshot = metrics.snapshot()
            assertTrue(snapshot.vm.sliceRequests > 0, snapshot.summary())
            assertTrue(snapshot.vm.slicePermitsSent > 0, snapshot.summary())
            assertTrue(snapshot.vm.slicePermitsReceived > 0, snapshot.summary())
            assertTrue(snapshot.vm.schedulingPoints > 0, snapshot.summary())
            assertTrue(snapshot.vm.executionWindows > 0, snapshot.summary())
            assertTrue(snapshot.vm.executionWindowNanos > 0, snapshot.summary())
        }
    }
```

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVmTest.recordsRuntimeSchedulingMetrics
```

Expected: FAIL because `BackgroundDeviceVm` does not accept `runtimeMetricsCollector`, or because VM metrics remain zero.

- [ ] **Step 3: Wire collector into `BackgroundDeviceVm`**

In `BackgroundDeviceVm.kt`, add imports:

```kotlin
import ru.lazyhat.compukterkraft.core.device.runtime.NoOpRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeMetricsCollector
```

Add constructor parameter after `displayMetricsCollector`:

```kotlin
    private val runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
```

Add a field near `runtime` fields:

```kotlin
    private var executionWindowStartedNanos: Long? = null
```

Change `requestSlice` to record slice request results:

```kotlin
    override fun requestSlice(serverTick: Long) {
        stateManager.updateCurrentTick(serverTick)
        val wakeTick = stateManager.sleepUntilTick
        if (wakeTick != null && serverTick < wakeTick) {
            runtimeMetricsCollector.recordSliceRequest(sent = false, sleepGated = true)
            return
        }
        val result = slicePermits.trySend(Unit)
        runtimeMetricsCollector.recordSliceRequest(sent = result.isSuccess, sleepGated = false)
    }
```

Add helper:

```kotlin
    private fun finishExecutionWindow() {
        val started = executionWindowStartedNanos ?: return
        executionWindowStartedNanos = null
        runtimeMetricsCollector.recordVmExecutionWindow(System.nanoTime() - started)
    }
```

At the start of `awaitSlicePermit()`, close the previous execution window. After `slicePermits.receive()`, record receipt and start a new window:

```kotlin
    private suspend fun awaitSlicePermit() {
        finishExecutionWindow()
        stateManager.setState(
            when {
                stateManager.sleepUntilTick != null -> VmState.Sleeping
                stateManager.isBooting -> VmState.Booting
                else -> VmState.Running
            },
        )
        slicePermits.receive()
        runtimeMetricsCollector.recordSlicePermitReceived()
        executionWindowStartedNanos = System.nanoTime()
        stateManager.updateSliceDeadlineNanos(profile.resources.cpu.wallTimeGuardNanosPerSlice)
        stateManager.setState(VmState.Running)
    }
```

In `applySchedulingPoint()`, record whether the point waits for the next slice or just yields:

```kotlin
    private suspend fun applySchedulingPoint() {
        coroutineContext.ensureActive()
        if (System.nanoTime() >= stateManager.sliceDeadlineNanos) {
            runtimeMetricsCollector.recordSchedulingPoint(waitedForSlice = true)
            awaitSlicePermit()
        } else {
            runtimeMetricsCollector.recordSchedulingPoint(waitedForSlice = false)
            coroutineYield()
        }
    }
```

Call `finishExecutionWindow()` at the beginning of `stopInternal()` before terminal state changes.

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

In `RuntimeDeviceImplDisplayTest.kt`, add this test:

```kotlin
    @Test
    fun recordsServerTickRuntimeMetrics() {
        val supervisor = DeviceVmSupervisor(ServerWorldAccess { createTempDirectory("runtime-profiling-test") })
        val manager = DeviceManager(supervisor)
        val displayNetwork = RecordingDisplayNetworkBridge()
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            RuntimeDeviceImpl(
                deviceId = 42,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = null),
                manager = manager,
                gameTime = { 0L },
                displayNetwork = displayNetwork,
                stateSink = {},
                runtimeMetricsCollector = metrics,
            )
        val playerUuid = UUID.randomUUID()

        device.attachDisplaySession(playerUuid, containerId = 11, displayId = 1, width = 32, height = 16)
        device.turnOn()
        device.serverTick()

        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.tick.serverTickCalls)
        assertEquals(1, snapshot.tick.requestSliceCalls)
        assertEquals(1, snapshot.tick.hostCallDrainCalls)
        assertEquals(1, snapshot.tick.hostCallDispatchCalls)
        assertTrue(snapshot.tick.displayFrameDrainCalls > 0, snapshot.summary())
        assertTrue(snapshot.tick.displayFlushCalls > 0, snapshot.summary())
        assertTrue(snapshot.tick.serverTickNanos > 0, snapshot.summary())
        assertTrue(snapshot.vm.sliceRequests > 0, snapshot.summary())

        device.close()
        manager.close()
    }
```

- [ ] **Step 2: Run RED**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImplDisplayTest.recordsServerTickRuntimeMetrics
```

Expected: FAIL because `RuntimeDeviceImpl` does not accept `runtimeMetricsCollector`, or metrics are zero.

- [ ] **Step 3: Pass collector through VM creation**

In `DeviceVmSupervisor.kt`, import:

```kotlin
import ru.lazyhat.compukterkraft.core.device.runtime.NoOpRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeMetricsCollector
```

Change `getOrCreate` signature:

```kotlin
    fun getOrCreate(
        deviceId: Int,
        profile: DeviceProfile,
        labelProvider: () -> String?,
        logger: DeviceVmLogger,
        runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
    ): BackgroundDeviceVm =
```

Pass the collector into `BackgroundDeviceVm`:

```kotlin
                runtimeMetricsCollector = runtimeMetricsCollector,
```

In `DeviceManager.kt`, import `NoOpRuntimeMetricsCollector` and `RuntimeMetricsCollector`, change `getOrCreateVm` signature to include the same default parameter, and pass it to `vmSupervisor.getOrCreate(...)`.

- [ ] **Step 4: Record phases in `RuntimeDeviceImpl`**

In `RuntimeDeviceImpl.kt`, add constructor parameter:

```kotlin
    private val runtimeMetricsCollector: RuntimeMetricsCollector = NoOpRuntimeMetricsCollector,
```

Pass it when creating VM handles:

```kotlin
        val handle = manager.getOrCreateVm(deviceId, profile, { labelBacking }, logger, runtimeMetricsCollector)
```

Add helper near internal methods:

```kotlin
    private inline fun <T> measureNanos(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - started)
    }
```

Replace `serverTick()` body with measured sections:

```kotlin
    override fun serverTick() {
        val handle = vmHandle ?: return
        val tickStarted = System.nanoTime()

        val (_, requestNanos) = measureNanos { handle.requestSlice(gameTime.gameTime()) }
        runtimeMetricsCollector.recordRequestSlice(requestNanos)

        val (calls, drainNanos) = measureNanos { handle.drainHostCalls() }
        runtimeMetricsCollector.recordHostCallDrain(calls.size, drainNanos)

        val (results, dispatchNanos) = measureNanos { calls.map(hostCallDispatcher::dispatch) }
        runtimeMetricsCollector.recordHostCallDispatch(calls.size, dispatchNanos)

        if (results.isNotEmpty()) {
            val (_, deliverNanos) = measureNanos { handle.deliverHostResults(results) }
            runtimeMetricsCollector.recordHostResultDelivery(results.size, deliverNanos)
        } else {
            runtimeMetricsCollector.recordHostResultDelivery(resultCount = 0, nanos = 0)
        }

        val (_, flushNanos) = measureNanos { flushDisplaySessions(handle) }
        runtimeMetricsCollector.recordDisplayFlush(frameCount = 0, nanos = flushNanos)
        runtimeMetricsCollector.recordServerTick(System.nanoTime() - tickStarted)
    }
```

Change `flushDisplaySessions` so it records actual drained frame count and returns it:

```kotlin
    private fun flushDisplaySessions(handle: BackgroundDeviceVm): Int {
        if (displaySessions.isEmpty()) return 0
        val (frames, drainNanos) = measureNanos { handle.drainDisplayFrames() }
        runtimeMetricsCollector.recordDisplayFrameDrain(frames.size, drainNanos)
        if (frames.isEmpty()) return 0

        val sessionsByDisplay = displaySessions.values.groupBy { it.displayId }
        val toDetach = mutableListOf<Pair<UUID, Int>>()
        for (frame in frames) {
            val sessions = sessionsByDisplay[frame.displayId].orEmpty()
            for (session in sessions) {
                if (!displayNetwork.isDisplaySessionStillBound(session.playerUuid, session.containerId, deviceId, session.displayId)) {
                    toDetach += session.playerUuid to session.displayId
                    continue
                }
                displayNetwork.sendDisplayFrame(session.playerUuid, session.containerId, frame)
            }
        }
        toDetach.forEach { (playerUuid, displayId) -> detachDisplaySession(playerUuid, displayId) }
        return frames.size
    }
```

Then update the `serverTick()` display flush section to record returned frame count:

```kotlin
        val (flushedFrames, flushNanos) = measureNanos { flushDisplaySessions(handle) }
        runtimeMetricsCollector.recordDisplayFlush(frameCount = flushedFrames, nanos = flushNanos)
```

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

In `RuntimeDisplayProfilingTest.kt`, import:

```kotlin
import ru.lazyhat.compukterkraft.core.device.runtime.RecordingRuntimeMetricsCollector
```

Create runtime metrics next to display metrics:

```kotlin
            val displayMetrics = RecordingDisplayMetricsCollector()
            val runtimeMetrics = RecordingRuntimeMetricsCollector()
```

Pass both collectors into `BackgroundDeviceVm`:

```kotlin
                    displayMetricsCollector = displayMetrics,
                    runtimeMetricsCollector = runtimeMetrics,
```

Change `runTicks` so it records host/test harness phase timings without counting `delay(10)` as CPU time:

```kotlin
    private fun runTicks(
        vm: BackgroundDeviceVm,
        dispatcher: HostCallDispatcher,
        metrics: RecordingRuntimeMetricsCollector,
        ticks: Int,
    ) =
        runBlocking {
            repeat(ticks) { tick ->
                val tickStarted = System.nanoTime()
                val requestStarted = System.nanoTime()
                vm.requestSlice(tick.toLong())
                metrics.recordRequestSlice(System.nanoTime() - requestStarted)

                val drainStarted = System.nanoTime()
                val calls = vm.drainHostCalls()
                metrics.recordHostCallDrain(calls.size, System.nanoTime() - drainStarted)

                val dispatchStarted = System.nanoTime()
                val results = calls.map(dispatcher::dispatch)
                metrics.recordHostCallDispatch(calls.size, System.nanoTime() - dispatchStarted)

                val deliverStarted = System.nanoTime()
                if (results.isNotEmpty()) {
                    vm.deliverHostResults(results)
                }
                metrics.recordHostResultDelivery(results.size, System.nanoTime() - deliverStarted)
                metrics.recordServerTick(System.nanoTime() - tickStarted)

                kotlinx.coroutines.delay(10)
            }
        }
```

Update calls:

```kotlin
            runTicks(vm, dispatcher, runtimeMetrics, ticks = 80)
            runTicks(vm, dispatcher, runtimeMetrics, ticks = 20)
            runTicks(vm, dispatcher, runtimeMetrics, ticks = 40)
```

Measure final display drain:

```kotlin
            val drainStarted = System.nanoTime()
            val frames = vm.drainDisplayFrames()
            runtimeMetrics.recordDisplayFrameDrain(frames.size, System.nanoTime() - drainStarted)
```

Print both summaries and assert runtime counters:

```kotlin
            val displaySnapshot = displayMetrics.snapshot()
            val runtimeSnapshot = runtimeMetrics.snapshot()
            println(displaySnapshot.summary())
            println(runtimeSnapshot.summary())

            assertTrue(runtimeSnapshot.tick.serverTickCalls > 0, runtimeSnapshot.summary())
            assertTrue(runtimeSnapshot.tick.requestSliceCalls > 0, runtimeSnapshot.summary())
            assertTrue(runtimeSnapshot.tick.hostCallDrainCalls > 0, runtimeSnapshot.summary())
            assertTrue(runtimeSnapshot.tick.hostCallDispatchCalls > 0, runtimeSnapshot.summary())
            assertTrue(runtimeSnapshot.tick.hostResultDeliveryCalls > 0, runtimeSnapshot.summary())
            assertTrue(runtimeSnapshot.tick.displayFrameDrainCalls > 0, runtimeSnapshot.summary())
            assertTrue(runtimeSnapshot.vm.sliceRequests > 0, runtimeSnapshot.summary())
            assertTrue(runtimeSnapshot.vm.slicePermitsReceived > 0, runtimeSnapshot.summary())
            assertTrue(runtimeSnapshot.vm.executionWindowNanos > 0, runtimeSnapshot.summary())
```

Keep existing display assertions, replacing `snapshot` with `displaySnapshot`.

- [ ] **Step 2: Run profiling integration test**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest --info
```

Expected: PASS and output both `display:` and `runtime:` summary lines.

- [ ] **Step 3: Document runtime CPU-time profiling hooks**

In `docs/ARCHITECTURE.md`, extend the runtime display profiling note with:

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
- [ ] The branch remains profiling-only and contains no ROM terminal optimizations.

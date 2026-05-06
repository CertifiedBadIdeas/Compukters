# Runtime Display Performance Profiling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add profiling-only instrumentation for runtime display/terminal workloads so later optimizations are evidence-driven.

**Architecture:** Add an optional no-op-by-default metrics collector at the display boundary, wire it into `DisplayRegistry` and `BackgroundDeviceVm`, then add a reusable bundled ROM terminal workload test that records display operation counts, frame/tile/payload counts, and broad tick diagnostics. This plan does not optimize rendering or add new display APIs.

**Tech Stack:** Kotlin, Gradle, kotlin.test/JUnit 5, CKL ROM resources, existing `DisplayRegistry`/`DisplayFrameDelta` runtime display stack.

---

## File structure

- Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt` — data classes and no-op/recording metrics collector.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt` — record display operation metrics and frame metrics.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt` — accept optional `DisplayMetricsCollector` and pass it into `DisplayRegistry`.
- Create `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt` — unit tests for collector behavior and registry wiring.
- Create `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt` — integration profiling scenario for bundled firmware/ROM terminal.

---

### Task 1: Add display profiling model

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt`

- [ ] **Step 1: Write the failing unit test**

Create `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayProfilingTest {
    @Test
    fun recordingCollectorCountsOperationsAndFrames() {
        val collector = RecordingDisplayMetricsCollector()
        collector.recordClear(displayId = 1)
        collector.recordSetPixel(displayId = 1)
        collector.recordFillRect(displayId = 1, width = 3, height = 4)
        collector.recordPresent(displayId = 1, emittedFrame = true)
        collector.recordFrameDrain(
            listOf(
                DisplayFrameDelta(
                    displayId = 1,
                    sequence = 1,
                    width = 16,
                    height = 16,
                    pixelFormat = DisplayPixelFormat.RGB565,
                    fullRefresh = false,
                    tiles =
                        listOf(
                            DisplayTile(
                                tileX = 0,
                                tileY = 0,
                                x = 0,
                                y = 0,
                                width = 2,
                                height = 2,
                                payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                            ),
                        ),
                ),
            ),
        )

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.operations.clearCalls)
        assertEquals(1, snapshot.operations.setPixelCalls)
        assertEquals(1, snapshot.operations.fillRectCalls)
        assertEquals(12, snapshot.operations.fillRectArea)
        assertEquals(1, snapshot.operations.presentCalls)
        assertEquals(1, snapshot.operations.presentFrames)
        assertEquals(1, snapshot.frames.frameCount)
        assertEquals(0, snapshot.frames.fullRefreshFrames)
        assertEquals(1, snapshot.frames.tileCount)
        assertEquals(8, snapshot.frames.payloadBytes)
    }

    @Test
    fun noopCollectorKeepsEmptySnapshot() {
        val collector = NoOpDisplayMetricsCollector
        collector.recordClear(displayId = 1)
        collector.recordSetPixel(displayId = 1)
        collector.recordFillRect(displayId = 1, width = 3, height = 4)
        collector.recordPresent(displayId = 1, emittedFrame = true)
        collector.recordFrameDrain(emptyList())

        val snapshot = collector.snapshot()

        assertEquals(DisplayOperationMetrics(), snapshot.operations)
        assertEquals(DisplayFrameMetrics(), snapshot.frames)
    }
}
```

- [ ] **Step 2: Run the test to verify RED**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest
```

Expected: FAIL because `RecordingDisplayMetricsCollector`, `NoOpDisplayMetricsCollector`, `DisplayOperationMetrics`, and `DisplayFrameMetrics` do not exist.

- [ ] **Step 3: Add the profiling model**

Create `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt`:

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.util.concurrent.atomic.AtomicLong

interface DisplayMetricsCollector {
    fun recordClear(displayId: Int)

    fun recordSetPixel(displayId: Int)

    fun recordFillRect(
        displayId: Int,
        width: Int,
        height: Int,
    )

    fun recordPresent(
        displayId: Int,
        emittedFrame: Boolean,
    )

    fun recordFrameDrain(frames: List<DisplayFrameDelta>)

    fun snapshot(): DisplayProfilingSnapshot
}

data class DisplayOperationMetrics(
    val clearCalls: Long = 0,
    val setPixelCalls: Long = 0,
    val fillRectCalls: Long = 0,
    val fillRectArea: Long = 0,
    val presentCalls: Long = 0,
    val presentFrames: Long = 0,
)

data class DisplayFrameMetrics(
    val frameCount: Long = 0,
    val fullRefreshFrames: Long = 0,
    val tileCount: Long = 0,
    val payloadBytes: Long = 0,
)

data class DisplayProfilingSnapshot(
    val operations: DisplayOperationMetrics = DisplayOperationMetrics(),
    val frames: DisplayFrameMetrics = DisplayFrameMetrics(),
) {
    fun summary(): String =
        "display: clear=${operations.clearCalls}, setPixel=${operations.setPixelCalls}, " +
            "fillRect=${operations.fillRectCalls}, fillArea=${operations.fillRectArea}, " +
            "present=${operations.presentCalls}, presentFrames=${operations.presentFrames}\n" +
            "frames: count=${frames.frameCount}, fullRefresh=${frames.fullRefreshFrames}, " +
            "tiles=${frames.tileCount}, payloadBytes=${frames.payloadBytes}"
}

object NoOpDisplayMetricsCollector : DisplayMetricsCollector {
    override fun recordClear(displayId: Int) = Unit

    override fun recordSetPixel(displayId: Int) = Unit

    override fun recordFillRect(
        displayId: Int,
        width: Int,
        height: Int,
    ) = Unit

    override fun recordPresent(
        displayId: Int,
        emittedFrame: Boolean,
    ) = Unit

    override fun recordFrameDrain(frames: List<DisplayFrameDelta>) = Unit

    override fun snapshot(): DisplayProfilingSnapshot = DisplayProfilingSnapshot()
}

class RecordingDisplayMetricsCollector : DisplayMetricsCollector {
    private val clearCalls = AtomicLong()
    private val setPixelCalls = AtomicLong()
    private val fillRectCalls = AtomicLong()
    private val fillRectArea = AtomicLong()
    private val presentCalls = AtomicLong()
    private val presentFrames = AtomicLong()
    private val frameCount = AtomicLong()
    private val fullRefreshFrames = AtomicLong()
    private val tileCount = AtomicLong()
    private val payloadBytes = AtomicLong()

    override fun recordClear(displayId: Int) {
        clearCalls.incrementAndGet()
    }

    override fun recordSetPixel(displayId: Int) {
        setPixelCalls.incrementAndGet()
    }

    override fun recordFillRect(
        displayId: Int,
        width: Int,
        height: Int,
    ) {
        fillRectCalls.incrementAndGet()
        if (width > 0 && height > 0) {
            fillRectArea.addAndGet(width.toLong() * height.toLong())
        }
    }

    override fun recordPresent(
        displayId: Int,
        emittedFrame: Boolean,
    ) {
        presentCalls.incrementAndGet()
        if (emittedFrame) {
            presentFrames.incrementAndGet()
        }
    }

    override fun recordFrameDrain(frames: List<DisplayFrameDelta>) {
        frameCount.addAndGet(frames.size.toLong())
        fullRefreshFrames.addAndGet(frames.count { it.fullRefresh }.toLong())
        tileCount.addAndGet(frames.sumOf { it.tiles.size }.toLong())
        payloadBytes.addAndGet(frames.sumOf { frame -> frame.tiles.sumOf { it.payload.size } }.toLong())
    }

    override fun snapshot(): DisplayProfilingSnapshot =
        DisplayProfilingSnapshot(
            operations =
                DisplayOperationMetrics(
                    clearCalls = clearCalls.get(),
                    setPixelCalls = setPixelCalls.get(),
                    fillRectCalls = fillRectCalls.get(),
                    fillRectArea = fillRectArea.get(),
                    presentCalls = presentCalls.get(),
                    presentFrames = presentFrames.get(),
                ),
            frames =
                DisplayFrameMetrics(
                    frameCount = frameCount.get(),
                    fullRefreshFrames = fullRefreshFrames.get(),
                    tileCount = tileCount.get(),
                    payloadBytes = payloadBytes.get(),
                ),
        )
}
```

- [ ] **Step 4: Run the test to verify GREEN**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt
git commit -m "feat: add display profiling metrics"
```

---

### Task 2: Wire metrics into DisplayRegistry and VM construction

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Test: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt`

- [ ] **Step 1: Add failing registry wiring test**

Append this test to `DisplayProfilingTest`:

```kotlin
    @Test
    fun displayRegistryRecordsOperationsAndDrainedFrames() {
        val collector = RecordingDisplayMetricsCollector()
        val registry = DisplayRegistry(metricsCollector = collector)

        registry.attach(displayId = 7, width = 16, height = 16)
        registry.clear(displayId = 7, rgb565 = 0)
        registry.fillRect(displayId = 7, x = 0, y = 0, width = 5, height = 7, rgb565 = 0x07E0)
        registry.present(displayId = 7)
        val frames = registry.drainFrames()

        val snapshot = collector.snapshot()

        assertEquals(2, frames.size, "attach full-refresh plus present frame")
        assertEquals(1, snapshot.operations.clearCalls)
        assertEquals(1, snapshot.operations.fillRectCalls)
        assertEquals(35, snapshot.operations.fillRectArea)
        assertEquals(1, snapshot.operations.presentCalls)
        assertEquals(1, snapshot.operations.presentFrames)
        assertEquals(2, snapshot.frames.frameCount)
        assertEquals(1, snapshot.frames.fullRefreshFrames)
        assertEquals(frames.sumOf { it.tiles.size }.toLong(), snapshot.frames.tileCount)
        assertEquals(frames.sumOf { frame -> frame.tiles.sumOf { it.payload.size } }.toLong(), snapshot.frames.payloadBytes)
    }
```

- [ ] **Step 2: Run the test to verify RED**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest.displayRegistryRecordsOperationsAndDrainedFrames
```

Expected: FAIL because `DisplayRegistry(metricsCollector = collector)` constructor parameter does not exist or metrics are not recorded.

- [ ] **Step 3: Wire metrics into `DisplayRegistry`**

Modify `DisplayRegistry` constructor and methods:

```kotlin
class DisplayRegistry(
    private val metricsCollector: DisplayMetricsCollector = NoOpDisplayMetricsCollector,
) {
```

Change the operation methods to record metrics:

```kotlin
    fun clear(
        displayId: Int,
        rgb565: Int,
    ) {
        metricsCollector.recordClear(displayId)
        displays[displayId]?.clear(rgb565)
    }

    fun setPixel(
        displayId: Int,
        x: Int,
        y: Int,
        rgb565: Int,
    ) {
        metricsCollector.recordSetPixel(displayId)
        displays[displayId]?.setPixel(x, y, rgb565)
    }

    fun fillRect(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) {
        metricsCollector.recordFillRect(displayId, width, height)
        displays[displayId]?.fillRect(x, y, width, height, rgb565)
    }

    fun present(displayId: Int) {
        val frame = displays[displayId]?.present()
        metricsCollector.recordPresent(displayId, emittedFrame = frame != null)
        frame?.let(pendingFrames::add)
    }

    fun drainFrames(): List<DisplayFrameDelta> =
        buildList {
            while (true) {
                add(pendingFrames.poll() ?: break)
            }
        }.also(metricsCollector::recordFrameDrain)
```

- [ ] **Step 4: Wire metrics through `BackgroundDeviceVm` constructor**

Modify constructor parameters in `BackgroundDeviceVm`:

```kotlin
class BackgroundDeviceVm(
    private val deviceId: Int,
    private val profile: DeviceProfile,
    dispatcher: CoroutineDispatcher,
    private val labelProvider: () -> String?,
    private val logger: DeviceVmLogger,
    private val workspace: DeviceWorkspace,
    private val callbacks: DeviceVmCallbacks = DeviceVmCallbacks(),
    private val firmwareLoader: FirmwareProgramLoader = ClasspathFirmwareProgramLoader(),
    private val displayMetricsCollector: DisplayMetricsCollector = NoOpDisplayMetricsCollector,
) : DeviceVmHandle,
```

Add imports:

```kotlin
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayMetricsCollector
import ru.lazyhat.compukterkraft.core.device.vm.display.NoOpDisplayMetricsCollector
```

Change display registry construction:

```kotlin
    private val displayRegistry = DisplayRegistry(displayMetricsCollector)
```

- [ ] **Step 5: Run registry tests**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest
```

Expected: PASS.

- [ ] **Step 6: Run core tests**

Run:

```bash
./gradlew :core:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit Task 2**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt
git commit -m "feat: record display profiling metrics"
```

---

### Task 3: Add bundled ROM terminal profiling scenario

**Files:**
- Create: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt`

- [ ] **Step 1: Write the failing integration test**

Create `RuntimeDisplayProfilingTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.impl.computer.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukterkraft.core.device.runtime.FirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.HostCallDispatcher
import ru.lazyhat.compukterkraft.core.device.runtime.LoadedFirmwareProgramSource
import ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVm
import ru.lazyhat.compukterkraft.core.device.vm.DeviceVmLogger
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceHost
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceInitializer
import ru.lazyhat.compukterkraft.core.device.vm.display.RecordingDisplayMetricsCollector
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeDisplayProfilingTest {
    private class ClasspathFirmwareLoader : FirmwareProgramLoader {
        override fun load(path: String): LoadedFirmwareProgramSource {
            val source =
                RuntimeDisplayProfilingTest::class.java.classLoader
                    .getResourceAsStream("firmware/$path")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("firmware/$path missing from classpath")
            return LoadedFirmwareProgramSource(path, source)
        }
    }

    private fun profile(): DeviceProfile =
        DeviceProfile(
            id = "display-profiling-test",
            displayName = "Display Profiling Test",
            cpuBudgetNanosPerSlice = 5_000_000,
            maxEventQueueSize = 64,
            allowedCapabilities =
                setOf(
                    DeviceCapability.DISPLAY,
                    DeviceCapability.FILESYSTEM,
                    DeviceCapability.EVENTS,
                    DeviceCapability.SYSTEM,
                    DeviceCapability.IPC,
                ),
            resources =
                DeviceResources(
                    cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 5_000_000),
                    memory = DeviceMemoryResources(),
                    storage = DeviceStorageResources(programRomBytes = 128 * 1024, diskBytes = 1024 * 1024),
                    queues = DeviceQueueResources(eventQueueSlots = 64, hostCallQueueSlots = 64),
                ),
        )

    private fun runTicks(
        vm: BackgroundDeviceVm,
        dispatcher: HostCallDispatcher,
        ticks: Int,
    ): Long =
        runBlocking {
            val started = System.nanoTime()
            repeat(ticks) { tick ->
                vm.requestSlice(tick.toLong())
                val results = vm.drainHostCalls().map(dispatcher::dispatch)
                if (results.isNotEmpty()) {
                    vm.deliverHostResults(results)
                }
                kotlinx.coroutines.delay(10)
            }
            System.nanoTime() - started
        }

    @Test
    fun bundledTerminalWorkloadProducesProfilingMetrics() {
        val root = createTempDirectory("compukterkraft-display-profiling")
        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val metrics = RecordingDisplayMetricsCollector()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                    displayMetricsCollector = metrics,
                )
            val dispatcher = HostCallDispatcher(deviceId = 1, workspace = workspace)

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            val bootNanos = runTicks(vm, dispatcher, ticks = 80)

            "help".forEach { ch -> vm.enqueueEvent(VmEvent("char", listOf(byteArrayOf(ch.code.toByte())))) }
            val inputNanos = runTicks(vm, dispatcher, ticks = 20)

            vm.enqueueEvent(VmEvent("key", listOf(KeyCodes.KEY_ENTER, false)))
            val outputNanos = runTicks(vm, dispatcher, ticks = 40)
            vm.drainDisplayFrames()

            val snapshot = metrics.snapshot()
            println(snapshot.summary())
            println("timing: bootNanos=$bootNanos, inputNanos=$inputNanos, outputNanos=$outputNanos")

            assertTrue(snapshot.operations.fillRectCalls > 0, snapshot.summary())
            assertTrue(snapshot.operations.presentCalls > 0, snapshot.summary())
            assertTrue(snapshot.frames.frameCount > 0, snapshot.summary())
            assertTrue(snapshot.frames.tileCount > 0, snapshot.summary())
            assertTrue(snapshot.frames.payloadBytes > 0, snapshot.summary())
            assertTrue(bootNanos > 0)
            assertTrue(inputNanos > 0)
            assertTrue(outputNanos > 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
```

- [ ] **Step 2: Run the test to verify RED**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest
```

Expected: FAIL before Task 2 is implemented because `BackgroundDeviceVm` does not accept `displayMetricsCollector`, or PASS if Task 2 already wired it correctly. If it passes immediately after Task 2, confirm the printed summary contains non-zero display/frame metrics.

- [ ] **Step 3: Fix only integration issues**

If imports or constructor arguments differ after Task 2, adjust this test to use the exact names introduced in Task 1 and Task 2. Do not change production behavior in this task.

- [ ] **Step 4: Run the profiling test to verify GREEN**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest
```

Expected: PASS and output a metrics summary with non-zero `fillRect`, `present`, `frames`, `tiles`, and `payloadBytes`.

- [ ] **Step 5: Run existing terminal behavior tests**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.BackgroundDeviceVmTest --tests ru.lazyhat.compukterkraft.impl.RomScriptCompileTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit Task 3**

Run:

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt
git commit -m "test: profile bundled terminal display workload"
```

---

### Task 4: Add documentation note and final verification

**Files:**
- Modify: `docs/ARCHITECTURE.md`

- [ ] **Step 1: Add documentation note**

In `docs/ARCHITECTURE.md`, under the runtime display/output section, add a short note:

```markdown
### Runtime display profiling

The VM display path has optional profiling hooks for local tests and diagnostics. They count display operations (`clear`, `setPixel`, `fillRect`, `present`), emitted frame deltas, dirty tiles, and approximate payload bytes. These hooks are disabled by default and should be used to justify display/terminal optimizations before changing rendering behavior.
```

- [ ] **Step 2: Run full verification**

Run:

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Audit that no forbidden terminal/stdout APIs were reintroduced**

Run:

```bash
rg 'terminal::|stdout::|DeviceTerminalApi|DeviceStdioApi|VmTerminalApi|ComputerStdioBroadcaster|ScreenBufferVtSink' modules/compiler/src/main modules/core/src/main modules/v1_21_1/v1_21_1-common/src/main modules/v1_21_1/v1_21_1-neoforge/src/main/resources
```

Expected: no output, exit code 1.

- [ ] **Step 4: Commit Task 4**

Run:

```bash
git add docs/ARCHITECTURE.md
git commit -m "docs: document display profiling hooks"
```

---

## Final verification checklist

- [ ] `./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest` passes.
- [ ] `./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest` passes and prints non-zero metrics.
- [ ] `./gradlew test` passes.
- [ ] Forbidden terminal/stdout audit has no production/resource matches.
- [ ] The branch contains only profiling/instrumentation changes, not rendering optimizations.

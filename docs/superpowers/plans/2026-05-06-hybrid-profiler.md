# Hybrid Profiler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add first-slice hybrid profiling: granular display timings, VM signal distribution, compiler phase metrics, sustained terminal profiling workload, and profiling documentation.

**Architecture:** Extend existing no-op-by-default collectors instead of adding a separate profiler subsystem. Keep domain metrics in Kotlin for portable Gradle workloads, and document JFR/async-profiler for CPU/allocation truth. Do not introduce native code or hard performance budgets.

**Tech Stack:** Kotlin/JVM 2.3, Gradle Kotlin DSL, Java 21, kotlin.test, kotlinx.coroutines, NeoForge test workload.

---

## File Structure

- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt`
  - Add display operation timing fields, frame-build timing fields, derived averages, no-op signatures, and recording counters.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt`
  - Measure display API operation durations and record `DisplayFrameBuildMetrics` from attach/resize/present.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt`
  - Add profiled present/full-refresh variants that return frame plus internal timing metrics while preserving existing `present()` and `fullRefresh()` APIs.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt`
  - Add `copyTileWithMetrics()` while preserving `copyTile()`.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt`
  - Add tests for operation timings, frame build timings, and derived averages.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
  - Add no-op-by-default VM signal metrics API to the language runtime boundary.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
  - Record VM signal kind after each `runUntilSignal()` result.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
  - Add VM signal distribution fields and derived execution-window average.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
  - Expose a `DeviceRuntimeMetrics` implementation from core runtime instances.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
  - Pass runtime signal metrics and compiler metrics into boot/runtime construction.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
  - Pass compiler metrics into spawned program compilation.
- Modify `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`
  - Add tests for signal distribution and average execution-window summary values.
- Create `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilerProfiling.kt`
  - Add compiler metrics collector, recording collector, snapshots, and summary formatting.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
  - Accept a `CompilerMetricsCollector` and pass it to frontend pipeline facades.
- Modify `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`
  - Record parse/project analysis/codegen/compile-total metrics.
- Modify `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt`
  - Accept and pass `CompilerMetricsCollector` through `ComputerProgramCompiler.compile()`.
- Create `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilerProfilingTest.kt`
  - Add tests for compiler metrics and `LanguageFrontend` instrumentation.
- Modify `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt`
  - Add sustained no-delay workload and print compiler summary with runtime/display summaries.
- Create `docs/PROFILING.md`
  - Document Gradle workload commands, JFR, optional async-profiler, and native-candidate interpretation.

## Task 1: Display Operation Timings

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt`

- [ ] **Step 1: Write failing tests for operation timings and averages**

Add this test to `DisplayProfilingTest` after `recordingCollectorCountsOperationsAndFrames()`:

```kotlin
    @Test
    fun recordingCollectorAccumulatesOperationTimingsAndAverages() {
        val collector = RecordingDisplayMetricsCollector()

        collector.recordClear(displayId = 1, nanos = 10)
        collector.recordSetPixel(displayId = 1, nanos = 20)
        collector.recordFillRect(displayId = 1, width = 3, height = 4, nanos = 30)
        collector.recordCopyRect(displayId = 1, width = 4, height = 5, nanos = 40)
        collector.recordBlitMono(displayId = 1, width = 6, height = 7, nanos = 50)
        collector.recordPresent(displayId = 1, emittedFrame = true, nanos = 60)

        val snapshot = collector.snapshot()

        assertEquals(10, snapshot.operations.clearNanos)
        assertEquals(20, snapshot.operations.setPixelNanos)
        assertEquals(30, snapshot.operations.fillRectNanos)
        assertEquals(40, snapshot.operations.copyRectNanos)
        assertEquals(50, snapshot.operations.blitMonoNanos)
        assertEquals(60, snapshot.operations.presentNanos)
        assertEquals(30, snapshot.operations.averageFillRectNanos)
        assertEquals(40, snapshot.operations.averageCopyRectNanos)
        assertEquals(50, snapshot.operations.averageBlitMonoNanos)
        assertEquals(60, snapshot.operations.averagePresentNanos)
        assertTrue(snapshot.summary().contains("fillNanos=30"), snapshot.summary())
        assertTrue(snapshot.summary().contains("avgBlitNanos=50"), snapshot.summary())
    }
```

Update existing test calls in `DisplayProfilingTest` to pass `nanos = 0` for all operation records:

```kotlin
collector.recordClear(displayId = 1, nanos = 0)
collector.recordSetPixel(displayId = 1, nanos = 0)
collector.recordFillRect(displayId = 1, width = 3, height = 4, nanos = 0)
collector.recordCopyRect(displayId = 1, width = 4, height = 5, nanos = 0)
collector.recordBlitMono(displayId = 1, width = 6, height = 7, nanos = 0)
collector.recordPresent(displayId = 1, emittedFrame = true, nanos = 0)
```

- [ ] **Step 2: Run the display profiling tests and verify they fail**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest
```

Expected: FAIL because `recordClear(..., nanos = ...)` and timing fields such as `clearNanos` do not exist.

- [ ] **Step 3: Extend display metrics data and collector signatures**

In `DisplayProfiling.kt`, change `DisplayMetricsCollector` operation methods to include `nanos: Long`:

```kotlin
interface DisplayMetricsCollector {
    fun recordClear(
        displayId: Int,
        nanos: Long,
    )

    fun recordSetPixel(
        displayId: Int,
        nanos: Long,
    )

    fun recordFillRect(
        displayId: Int,
        width: Int,
        height: Int,
        nanos: Long,
    )

    fun recordCopyRect(
        displayId: Int,
        width: Int,
        height: Int,
        nanos: Long,
    )

    fun recordBlitMono(
        displayId: Int,
        width: Int,
        height: Int,
        nanos: Long,
    )

    fun recordPresent(
        displayId: Int,
        emittedFrame: Boolean,
        nanos: Long,
    )

    fun recordFrameDrain(frames: List<DisplayFrameDelta>)

    fun snapshot(): DisplayProfilingSnapshot
}
```

Replace `DisplayOperationMetrics` with:

```kotlin
data class DisplayOperationMetrics(
    val clearCalls: Long = 0,
    val clearNanos: Long = 0,
    val setPixelCalls: Long = 0,
    val setPixelNanos: Long = 0,
    val fillRectCalls: Long = 0,
    val fillRectArea: Long = 0,
    val fillRectNanos: Long = 0,
    val copyRectCalls: Long = 0,
    val copyRectArea: Long = 0,
    val copyRectNanos: Long = 0,
    val blitMonoCalls: Long = 0,
    val blitMonoArea: Long = 0,
    val blitMonoNanos: Long = 0,
    val presentCalls: Long = 0,
    val presentFrames: Long = 0,
    val presentNanos: Long = 0,
) {
    val averageClearNanos: Long get() = average(clearNanos, clearCalls)
    val averageSetPixelNanos: Long get() = average(setPixelNanos, setPixelCalls)
    val averageFillRectNanos: Long get() = average(fillRectNanos, fillRectCalls)
    val averageCopyRectNanos: Long get() = average(copyRectNanos, copyRectCalls)
    val averageBlitMonoNanos: Long get() = average(blitMonoNanos, blitMonoCalls)
    val averagePresentNanos: Long get() = average(presentNanos, presentCalls)
}

private fun average(
    total: Long,
    count: Long,
): Long = if (count <= 0) 0 else total / count
```

Update `DisplayProfilingSnapshot.summary()` to include timing totals and averages:

```kotlin
    fun summary(): String =
        "display: clear=${operations.clearCalls}, clearNanos=${operations.clearNanos}, " +
            "setPixel=${operations.setPixelCalls}, setPixelNanos=${operations.setPixelNanos}, " +
            "fillRect=${operations.fillRectCalls}, fillArea=${operations.fillRectArea}, fillNanos=${operations.fillRectNanos}, " +
            "copyRect=${operations.copyRectCalls}, copyArea=${operations.copyRectArea}, copyNanos=${operations.copyRectNanos}, " +
            "blitMono=${operations.blitMonoCalls}, blitArea=${operations.blitMonoArea}, blitNanos=${operations.blitMonoNanos}, " +
            "present=${operations.presentCalls}, presentFrames=${operations.presentFrames}, presentNanos=${operations.presentNanos}\n" +
            "display-avg: avgClearNanos=${operations.averageClearNanos}, avgSetPixelNanos=${operations.averageSetPixelNanos}, " +
            "avgFillNanos=${operations.averageFillRectNanos}, avgCopyNanos=${operations.averageCopyRectNanos}, " +
            "avgBlitNanos=${operations.averageBlitMonoNanos}, avgPresentNanos=${operations.averagePresentNanos}\n" +
            "frames: count=${frames.frameCount}, fullRefresh=${frames.fullRefreshFrames}, " +
            "tiles=${frames.tileCount}, payloadBytes=${frames.payloadBytes}"
```

Update `NoOpDisplayMetricsCollector` methods with matching signatures and `Unit` bodies.

Add these fields to `RecordingDisplayMetricsCollector`:

```kotlin
    private val clearNanos = AtomicLong()
    private val setPixelNanos = AtomicLong()
    private val fillRectNanos = AtomicLong()
    private val copyRectNanos = AtomicLong()
    private val blitMonoNanos = AtomicLong()
    private val presentNanos = AtomicLong()
```

Update each recording method to add `nanos.coerceAtLeast(0)` to its timing counter. For example:

```kotlin
    override fun recordFillRect(
        displayId: Int,
        width: Int,
        height: Int,
        nanos: Long,
    ) {
        fillRectCalls.incrementAndGet()
        fillRectNanos.addAndGet(nanos.coerceAtLeast(0))
        if (width > 0 && height > 0) {
            fillRectArea.addAndGet(width.toLong() * height.toLong())
        }
    }
```

Update `snapshot()` to populate all new timing fields.

- [ ] **Step 4: Measure operations in DisplayRegistry**

In `DisplayRegistry.kt`, wrap each public display operation with `System.nanoTime()` and record after the call. Example for `fillRect`:

```kotlin
    fun fillRect(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) {
        val started = System.nanoTime()
        displays[displayId]?.fillRect(x, y, width, height, rgb565)
        metricsCollector.recordFillRect(displayId, width, height, System.nanoTime() - started)
    }
```

Apply the same pattern to `clear`, `setPixel`, `copyRect`, `blitMono`, `blitMono5x7`, and `present`. For `present`, record `emittedFrame = frame != null` and the measured duration after adding the frame to `pendingFrames`.

- [ ] **Step 5: Run the display profiling tests and verify they pass**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest
```

Expected: PASS.

- [ ] **Step 6: Commit display operation timings**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt
git commit -m "feat: record display operation timings"
```

## Task 2: Display Frame Build and Tile Serialization Timings

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt`

- [ ] **Step 1: Write failing tests for frame build timings**

Add this test to `DisplayProfilingTest`:

```kotlin
    @Test
    fun recordingCollectorAccumulatesFrameBuildTimings() {
        val collector = RecordingDisplayMetricsCollector()

        collector.recordFrameBuild(
            displayId = 1,
            metrics = DisplayFrameBuildMetrics(
                dirtyTileScanNanos = 10,
                frameBuildNanos = 20,
                tileSerializationNanos = 30,
                frontCopyNanos = 40,
                totalNanos = 100,
                tileCount = 2,
                payloadBytes = 16,
            ),
        )

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.frameBuild.buildCalls)
        assertEquals(10, snapshot.frameBuild.dirtyTileScanNanos)
        assertEquals(20, snapshot.frameBuild.frameBuildNanos)
        assertEquals(30, snapshot.frameBuild.tileSerializationNanos)
        assertEquals(40, snapshot.frameBuild.frontCopyNanos)
        assertEquals(100, snapshot.frameBuild.totalNanos)
        assertEquals(2, snapshot.frameBuild.tileCount)
        assertEquals(16, snapshot.frameBuild.payloadBytes)
        assertEquals(50, snapshot.frameBuild.averageTotalNanosPerTile)
        assertEquals(15, snapshot.frameBuild.averageTileSerializationNanosPerTile)
        assertEquals(1, snapshot.frameBuild.averageTileSerializationNanosPerPayloadByte)
        assertTrue(snapshot.summary().contains("frame-build:"), snapshot.summary())
        assertTrue(snapshot.summary().contains("nanosPerPayloadByte=1"), snapshot.summary())
    }
```

Add this test to `DisplayStateTest.kt`:

```kotlin
    @Test
    fun profiledPresentReturnsFrameBuildMetrics() {
        val state = DisplayState(displayId = 8, width = 16, height = 16, pixelFormat = DisplayPixelFormat.RGB565)
        state.fillRect(x = 0, y = 0, width = 8, height = 8, rgb565 = 0x07E0)

        val result = assertNotNull(state.presentWithMetrics())

        assertEquals(1, result.frame.tiles.size)
        assertEquals(1, result.metrics.tileCount)
        assertEquals(result.frame.tiles.sumOf { it.payload.size }.toLong(), result.metrics.payloadBytes)
        assertTrue(result.metrics.totalNanos >= 0)
        assertTrue(result.metrics.tileSerializationNanos >= 0)
    }
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest \
    --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayStateTest
```

Expected: FAIL because `DisplayFrameBuildMetrics`, `recordFrameBuild`, and `presentWithMetrics()` do not exist.

- [ ] **Step 3: Add frame build metrics models and collector support**

In `DisplayProfiling.kt`, add:

```kotlin
data class DisplayFrameBuildMetrics(
    val dirtyTileScanNanos: Long = 0,
    val frameBuildNanos: Long = 0,
    val tileSerializationNanos: Long = 0,
    val frontCopyNanos: Long = 0,
    val totalNanos: Long = 0,
    val tileCount: Long = 0,
    val payloadBytes: Long = 0,
)

data class DisplayFrameBuildTotals(
    val buildCalls: Long = 0,
    val dirtyTileScanNanos: Long = 0,
    val frameBuildNanos: Long = 0,
    val tileSerializationNanos: Long = 0,
    val frontCopyNanos: Long = 0,
    val totalNanos: Long = 0,
    val tileCount: Long = 0,
    val payloadBytes: Long = 0,
) {
    val averageTotalNanosPerBuild: Long get() = average(totalNanos, buildCalls)
    val averageTotalNanosPerTile: Long get() = average(totalNanos, tileCount)
    val averageTileSerializationNanosPerTile: Long get() = average(tileSerializationNanos, tileCount)
    val averageTileSerializationNanosPerPayloadByte: Long get() = average(tileSerializationNanos, payloadBytes)
}
```

Add to `DisplayMetricsCollector`:

```kotlin
    fun recordFrameBuild(
        displayId: Int,
        metrics: DisplayFrameBuildMetrics,
    )
```

Add `val frameBuild: DisplayFrameBuildTotals = DisplayFrameBuildTotals()` to `DisplayProfilingSnapshot` and extend `summary()` with:

```kotlin
            "\nframe-build: builds=${frameBuild.buildCalls}, dirtyScanNanos=${frameBuild.dirtyTileScanNanos}, " +
            "frameBuildNanos=${frameBuild.frameBuildNanos}, tileSerializationNanos=${frameBuild.tileSerializationNanos}, " +
            "frontCopyNanos=${frameBuild.frontCopyNanos}, totalNanos=${frameBuild.totalNanos}, " +
            "tiles=${frameBuild.tileCount}, payloadBytes=${frameBuild.payloadBytes}, " +
            "avgBuildNanos=${frameBuild.averageTotalNanosPerBuild}, " +
            "nanosPerTile=${frameBuild.averageTileSerializationNanosPerTile}, " +
            "nanosPerPayloadByte=${frameBuild.averageTileSerializationNanosPerPayloadByte}"
```

Update `NoOpDisplayMetricsCollector` and `RecordingDisplayMetricsCollector` with new counters and `snapshot()` population.

- [ ] **Step 4: Add tile serialization metrics to PixelBuffer**

In `PixelBuffer.kt`, add:

```kotlin
data class TileCopyResult(
    val payload: ByteArray,
    val nanos: Long,
)
```

Replace `copyTile()` with a delegating implementation and add `copyTileWithMetrics()`:

```kotlin
    fun copyTile(tile: DirtyTile): ByteArray = copyTileWithMetrics(tile).payload

    fun copyTileWithMetrics(tile: DirtyTile): TileCopyResult {
        val started = System.nanoTime()
        val out = ByteArray(tile.width * tile.height * BYTES_PER_PIXEL)
        var offset = 0
        for (row in tile.y until tile.y + tile.height) {
            for (col in tile.x until tile.x + tile.width) {
                val value = pixels[row * width + col].toInt() and 0xFFFF
                out[offset++] = (value ushr 8).toByte()
                out[offset++] = value.toByte()
            }
        }
        return TileCopyResult(out, System.nanoTime() - started)
    }
```

- [ ] **Step 5: Add profiled frame building to DisplayState**

In `DisplayState.kt`, add:

```kotlin
data class DisplayFrameBuildResult(
    val frame: DisplayFrameDelta,
    val metrics: DisplayFrameBuildMetrics,
)
```

Update `present()` and `fullRefresh()` to delegate:

```kotlin
    @Synchronized
    fun present(): DisplayFrameDelta? = presentWithMetrics()?.frame

    @Synchronized
    fun fullRefresh(): DisplayFrameDelta = fullRefreshWithMetrics().frame
```

Add profiled variants:

```kotlin
    @Synchronized
    fun presentWithMetrics(): DisplayFrameBuildResult? {
        val totalStarted = System.nanoTime()
        val dirtyStarted = System.nanoTime()
        val dirtyTiles = dirty.dirtyTiles()
        val dirtyNanos = System.nanoTime() - dirtyStarted
        if (dirtyTiles.isEmpty()) return null
        sequence += 1
        val frameStarted = System.nanoTime()
        val frame = buildFrameWithMetrics(dirtyTiles, fullRefresh = false)
        val frameBuildNanos = System.nanoTime() - frameStarted
        val copyStarted = System.nanoTime()
        front.copyFrom(back)
        val frontCopyNanos = System.nanoTime() - copyStarted
        dirty.clear()
        return DisplayFrameBuildResult(
            frame = frame.frame,
            metrics =
                DisplayFrameBuildMetrics(
                    dirtyTileScanNanos = dirtyNanos,
                    frameBuildNanos = frameBuildNanos,
                    tileSerializationNanos = frame.tileSerializationNanos,
                    frontCopyNanos = frontCopyNanos,
                    totalNanos = System.nanoTime() - totalStarted,
                    tileCount = frame.frame.tiles.size.toLong(),
                    payloadBytes = frame.frame.tiles.sumOf { it.payload.size }.toLong(),
                ),
        )
    }

    @Synchronized
    fun fullRefreshWithMetrics(): DisplayFrameBuildResult {
        dirty.markAllDirty()
        return presentWithMetrics() ?: error("Full refresh should always produce a frame")
    }
```

Replace `buildFrame()` with a profiled helper and compatibility wrapper:

```kotlin
    private data class BuiltFrame(
        val frame: DisplayFrameDelta,
        val tileSerializationNanos: Long,
    )

    private fun buildFrame(
        tiles: List<DirtyTile>,
        fullRefresh: Boolean,
    ): DisplayFrameDelta = buildFrameWithMetrics(tiles, fullRefresh).frame

    private fun buildFrameWithMetrics(
        tiles: List<DirtyTile>,
        fullRefresh: Boolean,
    ): BuiltFrame {
        var tileSerializationNanos = 0L
        val displayTiles =
            tiles.map { tile ->
                val copied = back.copyTileWithMetrics(tile)
                tileSerializationNanos += copied.nanos
                DisplayTile(
                    tileX = tile.tileX,
                    tileY = tile.tileY,
                    x = tile.x,
                    y = tile.y,
                    width = tile.width,
                    height = tile.height,
                    payload = copied.payload,
                )
            }
        return BuiltFrame(
            frame =
                DisplayFrameDelta(
                    displayId = displayId,
                    sequence = sequence,
                    width = width,
                    height = height,
                    pixelFormat = pixelFormat,
                    fullRefresh = fullRefresh,
                    tiles = displayTiles,
                ),
            tileSerializationNanos = tileSerializationNanos,
        )
    }
```

- [ ] **Step 6: Record frame build metrics in DisplayRegistry**

Update `attach()` to use `fullRefreshWithMetrics()`:

```kotlin
        val result = state.fullRefreshWithMetrics()
        pendingFrames.add(result.frame)
        metricsCollector.recordFrameBuild(displayId, result.metrics)
```

Update `present()` to use `presentWithMetrics()`:

```kotlin
    fun present(displayId: Int) {
        val started = System.nanoTime()
        val result = displays[displayId]?.presentWithMetrics()
        result?.let {
            pendingFrames.add(it.frame)
            metricsCollector.recordFrameBuild(displayId, it.metrics)
        }
        metricsCollector.recordPresent(displayId, emittedFrame = result != null, nanos = System.nanoTime() - started)
    }
```

- [ ] **Step 7: Run display tests and verify they pass**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest \
    --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayStateTest
```

Expected: PASS.

- [ ] **Step 8: Commit frame build timings**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfiling.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayProfilingTest.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt
git commit -m "feat: profile display frame building"
```

## Task 3: Runtime VM Signal Distribution

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt`

- [ ] **Step 1: Write failing tests for runtime signal distribution**

Add this to `RuntimeProfilingTest.recordingCollectorAccumulatesRuntimeAndVmMetrics()` before `val snapshot = collector.snapshot()`:

```kotlin
        collector.recordVmSignal(VmSignalKind.PAUSE)
        collector.recordVmSignal(VmSignalKind.YIELD)
        collector.recordVmSignal(VmSignalKind.SLEEP)
        collector.recordVmSignal(VmSignalKind.WAIT_EVENT)
        collector.recordVmSignal(VmSignalKind.HOST_CALL)
        collector.recordVmSignal(VmSignalKind.HALT)
```

Add assertions after existing VM assertions:

```kotlin
        assertEquals(1, snapshot.vm.pauseSignals)
        assertEquals(1, snapshot.vm.yieldSignals)
        assertEquals(1, snapshot.vm.sleepSignals)
        assertEquals(1, snapshot.vm.waitEventSignals)
        assertEquals(1, snapshot.vm.hostCallSignals)
        assertEquals(1, snapshot.vm.haltSignals)
        assertEquals(70, snapshot.vm.averageExecutionWindowNanos)
        assertTrue(snapshot.summary().contains("signals:"), snapshot.summary())
```

Update `noopCollectorKeepsEmptySnapshot()` to call `collector.recordVmSignal(VmSignalKind.PAUSE)` before asserting the empty snapshot.

- [ ] **Step 2: Run runtime profiling tests and verify they fail**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingTest
```

Expected: FAIL because `VmSignalKind` and `recordVmSignal()` do not exist.

- [ ] **Step 3: Add runtime metrics API at the language/runtime boundary**

In `DeviceRuntime.kt`, add after `interface DeviceProgram`:

```kotlin
enum class VmSignalKind {
    HALT,
    PAUSE,
    YIELD,
    SLEEP,
    WAIT_EVENT,
    HOST_CALL,
}

interface DeviceRuntimeMetrics {
    fun recordVmSignal(kind: VmSignalKind)
}

object NoopDeviceRuntimeMetrics : DeviceRuntimeMetrics {
    override fun recordVmSignal(kind: VmSignalKind) = Unit
}
```

Add to `DeviceRuntime`:

```kotlin
    val metrics: DeviceRuntimeMetrics
        get() = NoopDeviceRuntimeMetrics
```

- [ ] **Step 4: Record signal kinds in BytecodeComputerProgram**

In `LanguageRuntime.kt`, add imports if needed for `VmSignalKind` from the same package. Add this extension near `sealed interface VmSignal`:

```kotlin
private val VmSignal.kind: VmSignalKind
    get() =
        when (this) {
            VmSignal.Halt -> VmSignalKind.HALT
            VmSignal.Pause -> VmSignalKind.PAUSE
            VmSignal.Yield -> VmSignalKind.YIELD
            is VmSignal.Sleep -> VmSignalKind.SLEEP
            is VmSignal.WaitEvent -> VmSignalKind.WAIT_EVENT
            is VmSignal.HostCall -> VmSignalKind.HOST_CALL
        }
```

Update the loop in `BytecodeComputerProgram.run()`:

```kotlin
        while (true) {
            val signal = vm.runUntilSignal()
            runtime.metrics.recordVmSignal(signal.kind)
            when (signal) {
                VmSignal.Halt -> {
                    return
                }

                VmSignal.Pause -> {
                    runtime.yield()
                }

                VmSignal.Yield -> {
                    runtime.yield()
                    vm.resumeWith(VmValue.UnitValue)
                }

                is VmSignal.HostCall -> {
                    vm.resumeWith(bridge.invoke(signal.moduleName, signal.functionName, signal.arguments))
                }

                is VmSignal.Sleep -> {
                    runtime.sleep(signal.ticks)
                    vm.resumeWith(VmValue.UnitValue)
                }

                is VmSignal.WaitEvent -> {
                    vm.resumeWith(bridge.fromEvent(runtime.pullEvent(signal.filter)))
                }
            }
        }
```

- [ ] **Step 5: Extend core RuntimeMetricsCollector and recording snapshot**

In `RuntimeProfiling.kt`, import `ru.lazyhat.compukterkraft.lang.runtime.VmSignalKind` and add to `RuntimeMetricsCollector`:

```kotlin
    fun recordVmSignal(kind: VmSignalKind)
```

Add fields to `RuntimeVmMetrics`:

```kotlin
    val haltSignals: Long = 0,
    val pauseSignals: Long = 0,
    val yieldSignals: Long = 0,
    val sleepSignals: Long = 0,
    val waitEventSignals: Long = 0,
    val hostCallSignals: Long = 0,
) {
    val averageExecutionWindowNanos: Long get() = if (executionWindows <= 0) 0 else executionWindowNanos / executionWindows
```

Convert `RuntimeVmMetrics` from a single-line closing `)` to a body with the derived property. Update summary with a new line:

```kotlin
            "executionNanos=${vm.executionWindowNanos}, avgExecutionWindowNanos=${vm.averageExecutionWindowNanos}\n" +
            "signals: halt=${vm.haltSignals}, pause=${vm.pauseSignals}, yield=${vm.yieldSignals}, " +
            "sleep=${vm.sleepSignals}, waitEvent=${vm.waitEventSignals}, hostCall=${vm.hostCallSignals}"
```

Add no-op method to `NoOpRuntimeMetricsCollector`.

Add atomic counters and implementation to `RecordingRuntimeMetricsCollector`:

```kotlin
    private val haltSignals = AtomicLong()
    private val pauseSignals = AtomicLong()
    private val yieldSignals = AtomicLong()
    private val sleepSignals = AtomicLong()
    private val waitEventSignals = AtomicLong()
    private val hostCallSignals = AtomicLong()

    override fun recordVmSignal(kind: VmSignalKind) {
        when (kind) {
            VmSignalKind.HALT -> haltSignals.incrementAndGet()
            VmSignalKind.PAUSE -> pauseSignals.incrementAndGet()
            VmSignalKind.YIELD -> yieldSignals.incrementAndGet()
            VmSignalKind.SLEEP -> sleepSignals.incrementAndGet()
            VmSignalKind.WAIT_EVENT -> waitEventSignals.incrementAndGet()
            VmSignalKind.HOST_CALL -> hostCallSignals.incrementAndGet()
        }
    }
```

Populate the new fields in `snapshot()`.

- [ ] **Step 6: Bridge core runtime metrics into DeviceRuntime**

In `VmRuntime.kt`, import `DeviceRuntimeMetrics`, `NoopDeviceRuntimeMetrics`, and add constructor parameter:

```kotlin
    private val metricsApi: DeviceRuntimeMetrics = NoopDeviceRuntimeMetrics,
```

Add override:

```kotlin
    override val metrics: DeviceRuntimeMetrics = metricsApi
```

In `BackgroundDeviceVm.kt`, import `DeviceRuntimeMetrics` and `VmSignalKind`, add private adapter near `createRuntime()`:

```kotlin
    private inner class RuntimeMetricsApi : DeviceRuntimeMetrics {
        override fun recordVmSignal(kind: VmSignalKind) {
            runtimeMetricsCollector.recordVmSignal(kind)
        }
    }
```

Pass it into `VmRuntime`:

```kotlin
            metricsApi = RuntimeMetricsApi(),
```

- [ ] **Step 7: Run runtime tests and profiling workload**

Run:

```bash
./gradlew :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingTest \
    :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest
```

Expected: PASS, and profiling output includes a `signals:` line.

- [ ] **Step 8: Commit runtime signal metrics**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt \
    modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/LanguageRuntime.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfiling.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
    modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeProfilingTest.kt
git commit -m "feat: record VM signal metrics"
```

## Task 4: Compiler Phase Profiling

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilerProfiling.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilationArtifact.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt`
- Create: `modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilerProfilingTest.kt`

- [ ] **Step 1: Write failing compiler profiling tests**

Create `CompilerProfilingTest.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompilerProfilingTest {
    @Test
    fun recordingCollectorAccumulatesCompilerPhases() {
        val collector = RecordingCompilerMetricsCollector()

        collector.recordParse("main.ck", sourceBytes = 24, tokenCount = 7, nanos = 10)
        collector.recordAnalyze("main.ck", diagnostics = 0, symbols = 1, references = 0, nanos = 20)
        collector.recordCodegen("main.ck", functionCount = 1, instructionCount = 2, nanos = 30)
        collector.recordCompile("main.ck", sourceCount = 1, sourceBytes = 24, diagnostics = 0, nanos = 60)

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.parseCalls)
        assertEquals(10, snapshot.parseNanos)
        assertEquals(7, snapshot.tokens)
        assertEquals(1, snapshot.analyzeCalls)
        assertEquals(20, snapshot.analyzeNanos)
        assertEquals(1, snapshot.symbols)
        assertEquals(1, snapshot.codegenCalls)
        assertEquals(30, snapshot.codegenNanos)
        assertEquals(2, snapshot.instructions)
        assertEquals(1, snapshot.compileCalls)
        assertEquals(60, snapshot.compileNanos)
        assertEquals(60, snapshot.averageCompileNanos)
        assertTrue(snapshot.summary().contains("compiler:"), snapshot.summary())
    }

    @Test
    fun languageFrontendRecordsCompileMetrics() {
        val collector = RecordingCompilerMetricsCollector()
        val artifact = LanguageFrontend(compilerMetricsCollector = collector).compile("main.ck", "pub fun main() { val answer: Int = 42; }")

        val snapshot = collector.snapshot()

        assertNotNull(artifact.module)
        assertTrue(snapshot.parseCalls >= 1, snapshot.summary())
        assertTrue(snapshot.analyzeCalls >= 1, snapshot.summary())
        assertTrue(snapshot.codegenCalls >= 1, snapshot.summary())
        assertEquals(1, snapshot.compileCalls)
        assertTrue(snapshot.compileNanos > 0, snapshot.summary())
        assertTrue(snapshot.instructions > 0, snapshot.summary())
    }
}
```

- [ ] **Step 2: Run compiler tests and verify they fail**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.CompilerProfilingTest
```

Expected: FAIL because compiler profiling classes and constructor parameter do not exist.

- [ ] **Step 3: Add compiler profiling models and collector**

Create `CompilerProfiling.kt`:

```kotlin
package ru.lazyhat.compukterkraft.lang.frontend

import java.util.concurrent.atomic.AtomicLong

interface CompilerMetricsCollector {
    fun recordParse(
        sourceName: String,
        sourceBytes: Int,
        tokenCount: Int,
        nanos: Long,
    )

    fun recordAnalyze(
        sourceName: String,
        diagnostics: Int,
        symbols: Int,
        references: Int,
        nanos: Long,
    )

    fun recordCodegen(
        sourceName: String,
        functionCount: Int,
        instructionCount: Int,
        nanos: Long,
    )

    fun recordCompile(
        rootName: String,
        sourceCount: Int,
        sourceBytes: Int,
        diagnostics: Int,
        nanos: Long,
    )

    fun snapshot(): CompilerProfilingSnapshot
}

data class CompilerProfilingSnapshot(
    val parseCalls: Long = 0,
    val parseNanos: Long = 0,
    val sourceBytes: Long = 0,
    val tokens: Long = 0,
    val analyzeCalls: Long = 0,
    val analyzeNanos: Long = 0,
    val diagnostics: Long = 0,
    val symbols: Long = 0,
    val references: Long = 0,
    val codegenCalls: Long = 0,
    val codegenNanos: Long = 0,
    val functions: Long = 0,
    val instructions: Long = 0,
    val compileCalls: Long = 0,
    val compileNanos: Long = 0,
    val compiledSources: Long = 0,
) {
    val averageParseNanos: Long get() = average(parseNanos, parseCalls)
    val averageAnalyzeNanos: Long get() = average(analyzeNanos, analyzeCalls)
    val averageCodegenNanos: Long get() = average(codegenNanos, codegenCalls)
    val averageCompileNanos: Long get() = average(compileNanos, compileCalls)

    fun summary(): String =
        "compiler: compileCalls=$compileCalls, compileNanos=$compileNanos, avgCompileNanos=$averageCompileNanos, " +
            "sources=$compiledSources, sourceBytes=$sourceBytes, diagnostics=$diagnostics\n" +
            "compiler-phases: parseCalls=$parseCalls, parseNanos=$parseNanos, avgParseNanos=$averageParseNanos, tokens=$tokens, " +
            "analyzeCalls=$analyzeCalls, analyzeNanos=$analyzeNanos, avgAnalyzeNanos=$averageAnalyzeNanos, " +
            "symbols=$symbols, references=$references, codegenCalls=$codegenCalls, codegenNanos=$codegenNanos, " +
            "avgCodegenNanos=$averageCodegenNanos, functions=$functions, instructions=$instructions"
}

object NoOpCompilerMetricsCollector : CompilerMetricsCollector {
    override fun recordParse(sourceName: String, sourceBytes: Int, tokenCount: Int, nanos: Long) = Unit
    override fun recordAnalyze(sourceName: String, diagnostics: Int, symbols: Int, references: Int, nanos: Long) = Unit
    override fun recordCodegen(sourceName: String, functionCount: Int, instructionCount: Int, nanos: Long) = Unit
    override fun recordCompile(rootName: String, sourceCount: Int, sourceBytes: Int, diagnostics: Int, nanos: Long) = Unit
    override fun snapshot(): CompilerProfilingSnapshot = CompilerProfilingSnapshot()
}

class RecordingCompilerMetricsCollector : CompilerMetricsCollector {
    private val parseCalls = AtomicLong()
    private val parseNanos = AtomicLong()
    private val sourceBytes = AtomicLong()
    private val tokens = AtomicLong()
    private val analyzeCalls = AtomicLong()
    private val analyzeNanos = AtomicLong()
    private val diagnostics = AtomicLong()
    private val symbols = AtomicLong()
    private val references = AtomicLong()
    private val codegenCalls = AtomicLong()
    private val codegenNanos = AtomicLong()
    private val functions = AtomicLong()
    private val instructions = AtomicLong()
    private val compileCalls = AtomicLong()
    private val compileNanos = AtomicLong()
    private val compiledSources = AtomicLong()

    override fun recordParse(sourceName: String, sourceBytes: Int, tokenCount: Int, nanos: Long) {
        parseCalls.incrementAndGet()
        parseNanos.addAndGet(nanos.coerceAtLeast(0))
        this.sourceBytes.addAndGet(sourceBytes.coerceAtLeast(0).toLong())
        tokens.addAndGet(tokenCount.coerceAtLeast(0).toLong())
    }

    override fun recordAnalyze(sourceName: String, diagnostics: Int, symbols: Int, references: Int, nanos: Long) {
        analyzeCalls.incrementAndGet()
        analyzeNanos.addAndGet(nanos.coerceAtLeast(0))
        this.diagnostics.addAndGet(diagnostics.coerceAtLeast(0).toLong())
        this.symbols.addAndGet(symbols.coerceAtLeast(0).toLong())
        this.references.addAndGet(references.coerceAtLeast(0).toLong())
    }

    override fun recordCodegen(sourceName: String, functionCount: Int, instructionCount: Int, nanos: Long) {
        codegenCalls.incrementAndGet()
        codegenNanos.addAndGet(nanos.coerceAtLeast(0))
        functions.addAndGet(functionCount.coerceAtLeast(0).toLong())
        instructions.addAndGet(instructionCount.coerceAtLeast(0).toLong())
    }

    override fun recordCompile(rootName: String, sourceCount: Int, sourceBytes: Int, diagnostics: Int, nanos: Long) {
        compileCalls.incrementAndGet()
        compileNanos.addAndGet(nanos.coerceAtLeast(0))
        compiledSources.addAndGet(sourceCount.coerceAtLeast(0).toLong())
        this.diagnostics.addAndGet(diagnostics.coerceAtLeast(0).toLong())
    }

    override fun snapshot(): CompilerProfilingSnapshot =
        CompilerProfilingSnapshot(
            parseCalls = parseCalls.get(),
            parseNanos = parseNanos.get(),
            sourceBytes = sourceBytes.get(),
            tokens = tokens.get(),
            analyzeCalls = analyzeCalls.get(),
            analyzeNanos = analyzeNanos.get(),
            diagnostics = diagnostics.get(),
            symbols = symbols.get(),
            references = references.get(),
            codegenCalls = codegenCalls.get(),
            codegenNanos = codegenNanos.get(),
            functions = functions.get(),
            instructions = instructions.get(),
            compileCalls = compileCalls.get(),
            compileNanos = compileNanos.get(),
            compiledSources = compiledSources.get(),
        )
}

private fun average(total: Long, count: Long): Long = if (count <= 0) 0 else total / count
```

- [ ] **Step 4: Wire compiler metrics through LanguageFrontend and FrontendPipelines**

In `LanguageFrontend.kt`, update constructor and facade construction:

```kotlin
class LanguageFrontend(
    val registry: BuiltinRegistry = LanguageBuiltins.defaultRuntimeRegistry,
    private val compilerMetricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
) {
    private val analyzer: AnalyzerFacade =
        DefaultAnalyzerFacade(registry, metricsCollector = compilerMetricsCollector)
    private val compiler: CompilerFacade =
        DefaultCompilerFacade(registry, analyzer, metricsCollector = compilerMetricsCollector)
```

In `FrontendPipelines.kt`, update `DefaultParserFacade` to accept a collector and record parse time:

```kotlin
internal class DefaultParserFacade(
    private val metricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
) : ParserFacade {
    override fun parse(
        name: String,
        source: String,
    ): ParsedSource {
        val started = System.nanoTime()
        val lexer = Lexer(source)
        val tokens = lexer.lex()
        val parser = Parser(tokens, lexer.diagnostics)
        val program = parser.parseProgram()
        metricsCollector.recordParse(name, sourceBytes = source.length, tokenCount = tokens.size, nanos = System.nanoTime() - started)
        return ParsedSource(
            name = name,
            source = source,
            tokens = tokens,
            comments = lexer.comments,
            syntaxDiagnostics = lexer.diagnostics + parser.diagnostics,
            program = program,
        )
    }
}
```

Update `DefaultAnalyzerFacade` constructor and `analyze()` to pass the collector into `DefaultParserFacade` and record analyze time after semantic analysis.

Update `DefaultCompilerFacade` constructor to accept `metricsCollector`. In `compile()`, wrap the whole body with `compileStarted`, record codegen around `BytecodeCompiler(...).compile(name)`, and record total compile after artifact creation. Use helper:

```kotlin
private fun BytecodeModule.instructionCount(): Int = functions.sumOf { it.instructions.size }
```

Use `project.values.sumOf { it.source.length }` for compile source bytes and total diagnostics count from all analyses.

- [ ] **Step 5: Expose compiler profiling through artifacts and core compiler entrypoints**

In `CompilationArtifact.kt`, add snapshot field:

```kotlin
data class CompilationArtifact(
    val module: BytecodeModule?,
    val analysis: AnalyzedProgram,
    val analyses: Map<String, AnalyzedProgram> = mapOf(analysis.name to analysis),
    val profiling: CompilerProfilingSnapshot = CompilerProfilingSnapshot(),
)
```

When `DefaultCompilerFacade.compile()` returns `CompilationArtifact`, set `profiling = metricsCollector.snapshot()`.

In `DeviceProgramSupport.kt`, import compiler metrics types and add parameter to `ComputerProgramCompiler.compile()`:

```kotlin
        compilerMetricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
```

Use:

```kotlin
        val artifact = LanguageFrontend(runtimeRegistry, compilerMetricsCollector).compile(path, source, sourceLoader)
```

In `BackgroundDeviceVm.kt`, add constructor parameter:

```kotlin
    private val compilerMetricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector,
```

Pass it to boot compilation and into `VmProcessManager`.

In `VmProcessManager.kt`, add constructor parameter `compilerMetricsCollector: CompilerMetricsCollector = NoOpCompilerMetricsCollector` and pass it to `ComputerProgramCompiler.compile()`.

- [ ] **Step 6: Run compiler and core tests**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.CompilerProfilingTest \
    :core:test
```

Expected: PASS.

- [ ] **Step 7: Commit compiler phase profiling**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilerProfiling.kt \
    modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageFrontend.kt \
    modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/FrontendPipelines.kt \
    modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilationArtifact.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmProcessManager.kt \
    modules/compiler/src/test/kotlin/ru/lazyhat/compukterkraft/lang/frontend/CompilerProfilingTest.kt
git commit -m "feat: record compiler phase metrics"
```

## Task 5: Sustained Profiling Workload

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt`

- [ ] **Step 1: Write failing sustained workload assertions**

Add compiler metrics import:

```kotlin
import ru.lazyhat.compukterkraft.lang.frontend.RecordingCompilerMetricsCollector
```

Add helper data class inside `RuntimeDisplayProfilingTest`:

```kotlin
    private data class ProfilingRun(
        val displayMetrics: RecordingDisplayMetricsCollector,
        val runtimeMetrics: RecordingRuntimeMetricsCollector,
        val compilerMetrics: RecordingCompilerMetricsCollector,
    )
```

Add test after `bundledTerminalWorkloadProducesProfilingMetrics()`:

```kotlin
    @Test
    fun sustainedTerminalWorkloadProducesNoDelayProfilingMetrics() {
        val run = runTerminalWorkload(delayMillis = 0, bootTicks = 120, inputTicks = 40, enterTicks = 80)
        val displaySnapshot = run.displayMetrics.snapshot()
        val runtimeSnapshot = run.runtimeMetrics.snapshot()
        val compilerSnapshot = run.compilerMetrics.snapshot()

        println(displaySnapshot.summary())
        println(runtimeSnapshot.summary())
        println(compilerSnapshot.summary())

        assertTrue(displaySnapshot.operations.blitMonoNanos >= 0, displaySnapshot.summary())
        assertTrue(displaySnapshot.frameBuild.buildCalls > 0, displaySnapshot.summary())
        assertTrue(runtimeSnapshot.vm.pauseSignals + runtimeSnapshot.vm.yieldSignals + runtimeSnapshot.vm.hostCallSignals > 0, runtimeSnapshot.summary())
        assertTrue(runtimeSnapshot.vm.averageExecutionWindowNanos >= 0, runtimeSnapshot.summary())
        assertTrue(compilerSnapshot.compileCalls > 0, compilerSnapshot.summary())
        assertTrue(compilerSnapshot.compileNanos > 0, compilerSnapshot.summary())
    }
```

- [ ] **Step 2: Run workload test and verify it fails**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest
```

Expected: FAIL because `runTerminalWorkload()` and compiler metrics wiring in the test are not present.

- [ ] **Step 3: Refactor workload test into reusable helper with optional delay**

Update `runTicks()` signature and delay behavior:

```kotlin
    private fun runTicks(
        vm: BackgroundDeviceVm,
        dispatcher: HostCallDispatcher,
        metrics: RecordingRuntimeMetricsCollector,
        ticks: Int,
        delayMillis: Long = 10,
    ) = runBlocking {
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

            if (delayMillis > 0) {
                kotlinx.coroutines.delay(delayMillis)
            } else {
                kotlinx.coroutines.yield()
            }
        }
    }
```

Add helper:

```kotlin
    private fun runTerminalWorkload(
        delayMillis: Long,
        bootTicks: Int,
        inputTicks: Int,
        enterTicks: Int,
    ): ProfilingRun {
        val root = createTempDirectory("compukterkraft-display-profiling")
        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val displayMetrics = RecordingDisplayMetricsCollector()
            val runtimeMetrics = RecordingRuntimeMetricsCollector()
            val compilerMetrics = RecordingCompilerMetricsCollector()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                    displayMetricsCollector = displayMetrics,
                    runtimeMetricsCollector = runtimeMetrics,
                    compilerMetricsCollector = compilerMetrics,
                )
            val dispatcher = HostCallDispatcher(deviceId = 1, workspace = workspace)

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            runTicks(vm, dispatcher, runtimeMetrics, ticks = bootTicks, delayMillis = delayMillis)

            "help".forEach { ch -> vm.enqueueEvent(VmEvent("char", listOf(byteArrayOf(ch.code.toByte())))) }
            runTicks(vm, dispatcher, runtimeMetrics, ticks = inputTicks, delayMillis = delayMillis)

            vm.enqueueEvent(VmEvent("key", listOf(KeyCodes.KEY_ENTER, false)))
            runTicks(vm, dispatcher, runtimeMetrics, ticks = enterTicks, delayMillis = delayMillis)
            val drainStarted = System.nanoTime()
            val frames = vm.drainDisplayFrames()
            runtimeMetrics.recordDisplayFrameDrain(frames.size, System.nanoTime() - drainStarted)

            return ProfilingRun(displayMetrics, runtimeMetrics, compilerMetrics)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
```

Refactor `bundledTerminalWorkloadProducesProfilingMetrics()` to call:

```kotlin
        val run = runTerminalWorkload(delayMillis = 10, bootTicks = 80, inputTicks = 20, enterTicks = 40)
        val displaySnapshot = run.displayMetrics.snapshot()
        val runtimeSnapshot = run.runtimeMetrics.snapshot()
        val compilerSnapshot = run.compilerMetrics.snapshot()
        println(displaySnapshot.summary())
        println(runtimeSnapshot.summary())
        println(compilerSnapshot.summary())
```

Add assertions that `compilerSnapshot.compileCalls > 0` and `compilerSnapshot.compileNanos > 0`.

- [ ] **Step 4: Run workload test and verify it passes**

Run:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest --info
```

Expected: PASS, with display, runtime, signals, frame-build, and compiler summaries printed.

- [ ] **Step 5: Commit sustained workload**

Run:

```bash
git add modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/computer/vm/RuntimeDisplayProfilingTest.kt
git commit -m "test: add sustained profiling workload"
```

## Task 6: Profiling Guide

**Files:**
- Create: `docs/PROFILING.md`

- [ ] **Step 1: Create profiling guide**

Create `docs/PROFILING.md` with this content:

```markdown
# Profiling Compukter Kraft

Compukter Kraft profiling has two layers:

1. **In-code domain metrics** from runtime, display, and compiler collectors.
2. **External CPU/allocation profiling** through JFR or async-profiler.

The in-code metrics explain what the VM/display/compiler did. External profilers explain where CPU time and allocations were spent.

## Runtime/display profiling workload

Run the bundled terminal profiling workload:

```bash
./gradlew :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest --info
```

The output includes:

- `display:` operation counts, areas, and operation timings;
- `display-avg:` per-operation averages;
- `frames:` emitted frame/tile/payload counts;
- `frame-build:` dirty tile scan, tile serialization, front-copy, and build timings;
- `runtime:` server tick and slice request timings;
- `host:` host call drain/dispatch/delivery timings;
- `display-runtime:` frame drain/flush timings;
- `vm:` scheduler and execution window metrics;
- `signals:` VM signal distribution;
- `compiler:` compile totals;
- `compiler-phases:` parse/analyze/codegen metrics.

## JFR

JFR is available with the JDK and is the first external profiler to try.

```bash
./gradlew :v1_21_1-neoforge:test \
  --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest \
  --info \
  -Dorg.gradle.jvmargs="-XX:StartFlightRecording=filename=build/reports/profiling/runtime-display.jfr,settings=profile,dumponexit=true"
```

If Gradle daemon JVM arguments are already configured locally, stop daemons before rerunning:

```bash
./gradlew --stop
```

Open the `.jfr` file in JDK Mission Control or another JFR viewer. Compare CPU and allocation hotspots with the in-code summary printed by the workload.

## async-profiler

async-profiler is optional and depends on local OS/tooling setup.

Use it when JFR shows a broad hotspot and you need flamegraphs. Attach to the Gradle test JVM that is running `RuntimeDisplayProfilingTest`, collect CPU or allocation data, then compare the flamegraph with the in-code summaries.

## Native candidate heuristic

A path is a plausible JNI/Rust candidate only if all are true:

1. It is a measured CPU or allocation hotspot.
2. It can be invoked as a coarse batch.
3. Inputs and outputs can be represented as primitive buffers or compact handles.
4. It does not require frequent native-to-Java callbacks.
5. Expected savings exceed native packaging and crash-risk costs.

Likely candidates after measurement:

- full CKL VM slice runner;
- batched framebuffer operations;
- tile serialization or compression.

Unlikely candidates:

- individual `setPixel` calls;
- individual glyph calls;
- host filesystem calls;
- event queue bookkeeping;
- Minecraft UI glue.

## Interpretation notes

- High operation counts with low total nanos usually do not justify native code.
- High total nanos in small per-call operations suggests batching before native code.
- High tile payload bytes with high serialization nanos may justify native serialization or compression.
- High VM execution nanos plus many pause/yield signals points to VM interpreter work.
- Compiler phase timings affect startup and IDE latency, not steady-state display FPS.
```

- [ ] **Step 2: Verify guide references real workload command**

Run:

```bash
grep -F ":v1_21_1-neoforge:test" docs/PROFILING.md && \
grep -F "RuntimeDisplayProfilingTest" docs/PROFILING.md && \
grep -F "Native candidate heuristic" docs/PROFILING.md
```

Expected: command prints matching lines and exits with code 0.

- [ ] **Step 3: Commit profiling guide**

Run:

```bash
git add docs/PROFILING.md
git commit -m "docs: add profiling guide"
```

## Task 7: Final Verification

**Files:**
- Verify all changed files from Tasks 1-6.

- [ ] **Step 1: Run targeted test suite**

Run:

```bash
./gradlew :compiler:test --tests ru.lazyhat.compukterkraft.lang.frontend.CompilerProfilingTest \
    :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingTest \
    :core:test --tests ru.lazyhat.compukterkraft.core.device.vm.display.DisplayStateTest \
    :core:test --tests ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingTest \
    :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest --info
```

Expected: BUILD SUCCESSFUL. Profiling output contains `display:`, `display-avg:`, `frame-build:`, `runtime:`, `vm:`, `signals:`, `compiler:`, and `compiler-phases:`.

- [ ] **Step 2: Run broader module tests**

Run:

```bash
./gradlew :compiler:test :core:test :v1_21_1-neoforge:test --tests ru.lazyhat.compukterkraft.impl.computer.vm.RuntimeDisplayProfilingTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Check no Russian profiler plan/spec was created**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
root = Path('.')
paths = [
    root / 'docs/superpowers/specs/2026-05-06-hybrid-profiler-design.ru.md',
    root / 'docs/superpowers/plans/2026-05-06-hybrid-profiler.ru.md',
]
existing = [str(path) for path in paths if path.exists()]
if existing:
    raise SystemExit('Unexpected Russian profiler docs: ' + ', '.join(existing))
print('PASS: no Russian profiler docs')
PY
```

Expected: `PASS: no Russian profiler docs`.

- [ ] **Step 4: Inspect git history and status**

Run:

```bash
git status --short && git --no-pager log --oneline --decorate --max-count=8
```

Expected: clean status and task commits visible on `feature/profiler-hybrid`.

- [ ] **Step 5: Commit plan updates if this plan was edited during execution**

If execution required plan corrections, commit the plan changes:

```bash
git add docs/superpowers/plans/2026-05-06-hybrid-profiler.md
git commit -m "docs: update hybrid profiler plan"
```

If the plan file was not changed after its initial plan commit, skip this step.

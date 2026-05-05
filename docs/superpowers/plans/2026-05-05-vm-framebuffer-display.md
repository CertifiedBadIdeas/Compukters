# VM Framebuffer Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the terminal-first computer output path with a VM-rendered framebuffer display path whose resolution is supplied by the client/display endpoint.

**Architecture:** Add pure display models in `:compiler`, framebuffer/dirty-tile state in `:core`, a `display` runtime API implemented by `BackgroundDeviceVm`, and Minecraft-facing endpoint/network/client buffers in `v1_21_1-common`. Use host/runtime calls, not new bytecode instructions, so `Instruction` and ROM estimation stay stable in this phase.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, CKL compiler/runtime, Minecraft 1.21.1 common module networking, kotlin.test.

**Worktree:** `/home/lazyhat/IdeaProjects/Compukter-Kraft/.worktrees/vm-framebuffer-display` on branch `feature/vm-framebuffer-display`.

**Baseline verified:** `./gradlew :compiler:test` and `./gradlew test` both passed before this plan was written.

---

## File structure

Create pure cross-module models:

- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/display/DisplayModels.kt` — `DisplayPixelFormat`, `DisplayInfo`, `DisplayTile`, `DisplayFrameDelta`.

Create core framebuffer runtime:

- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt` — RGB565 buffer mutation and tile extraction.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTracker.kt` — tile coordinate bitset.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt` — per-display buffers, sequence, `present()`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt` — attach/resize/detach/query/drain.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt` — runtime implementation of CKL `display::` builtins.

Modify runtime interfaces and host wiring:

- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt` — add `DeviceDisplayApi` and `DeviceRuntime.display`.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt` — add `DeviceCapability.DISPLAY`.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt` — add `display` module.
- `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt` — dispatch `display` functions.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt` — carry `DeviceDisplayApi`.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt` — own `DisplayRegistry`, expose attach/resize/detach/drain, include `display` in the runtime registry.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt` — add display-session role.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt` — bridge display sessions and flush frame deltas.
- `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/DisplayNetworkBridge.kt` — platform-neutral frame sender/session validator.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt` — implement `DisplayNetworkBridge`.

Create common client/network files:

- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBuffer.kt` — client staging/front buffers.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayAttachServerMessage.kt` — attach display endpoint.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayResizeServerMessage.kt` — resize display endpoint.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayDetachServerMessage.kt` — detach display endpoint.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/FrameDeltaClientMessage.kt` — deliver display frames.

Modify common client integration:

- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt` — register display packets with IDs `22`, `23`, `24`, and `25`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt` — add `handleDisplayFrame(containerId, delta)`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt` — route display frames to the current `ComputerMenu`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt` — store/apply `ClientDisplayBuffer` on the client side.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlockEntity.kt` — pass the host display network bridge into `RuntimeDeviceImpl`.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt` — pass the host display network bridge into `RuntimeDeviceImpl` for workbench target runtime devices.
- `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt` — announce display attach/resize/detach and keep the old terminal surface out of the active display path.

Add tests:

- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTrackerTest.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApiTest.kt`
- `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmDisplayTest.kt`
- `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBufferTest.kt`
- `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/DisplayMessageCodecTest.kt`

---

### Task 1: Pure display models and dirty tiles

**Files:**
- Create: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/display/DisplayModels.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTracker.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTrackerTest.kt`

- [ ] **Step 1: Write the failing dirty tile test**

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

import kotlin.test.Test
import kotlin.test.assertEquals

class TileDirtyTrackerTest {
    @Test
    fun marksEveryTileTouchedByRectangleOnceInStableOrder() {
        val tracker = TileDirtyTracker(width = 40, height = 24, tileSize = 16)

        tracker.markRectDirty(x = 15, y = 15, width = 20, height = 9)

        assertEquals(
            listOf(
                DirtyTile(tileX = 0, tileY = 0, x = 0, y = 0, width = 16, height = 16),
                DirtyTile(tileX = 1, tileY = 0, x = 16, y = 0, width = 16, height = 16),
                DirtyTile(tileX = 2, tileY = 0, x = 32, y = 0, width = 8, height = 16),
                DirtyTile(tileX = 0, tileY = 1, x = 0, y = 16, width = 16, height = 8),
                DirtyTile(tileX = 1, tileY = 1, x = 16, y = 16, width = 16, height = 8),
                DirtyTile(tileX = 2, tileY = 1, x = 32, y = 16, width = 8, height = 8),
            ),
            tracker.dirtyTiles(),
        )
    }

    @Test
    fun ignoresRectanglesOutsideTheDisplay() {
        val tracker = TileDirtyTracker(width = 32, height = 32, tileSize = 16)

        tracker.markRectDirty(x = 40, y = 40, width = 5, height = 5)

        assertEquals(emptyList(), tracker.dirtyTiles())
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `./gradlew :core:test --tests "*TileDirtyTrackerTest"`

Expected: `Compilation error` mentioning unresolved `TileDirtyTracker` and `DirtyTile`.

- [ ] **Step 3: Add display wire models**

```kotlin
package ru.lazyhat.compukterkraft.lang.runtime.display

enum class DisplayPixelFormat {
    RGB565,
}

data class DisplayInfo(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val pixelFormat: DisplayPixelFormat,
)

data class DisplayTile(
    val tileX: Int,
    val tileY: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is DisplayTile &&
            tileX == other.tileX &&
            tileY == other.tileY &&
            x == other.x &&
            y == other.y &&
            width == other.width &&
            height == other.height &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = tileX
        result = 31 * result + tileY
        result = 31 * result + x
        result = 31 * result + y
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

data class DisplayFrameDelta(
    val displayId: Int,
    val sequence: Long,
    val width: Int,
    val height: Int,
    val pixelFormat: DisplayPixelFormat,
    val fullRefresh: Boolean,
    val tiles: List<DisplayTile>,
)
```

- [ ] **Step 4: Add dirty tile tracker**

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

data class DirtyTile(
    val tileX: Int,
    val tileY: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

class TileDirtyTracker(
    private val width: Int,
    private val height: Int,
    private val tileSize: Int = DEFAULT_TILE_SIZE,
) {
    private val tilesX = ((width + tileSize - 1) / tileSize).coerceAtLeast(0)
    private val tilesY = ((height + tileSize - 1) / tileSize).coerceAtLeast(0)
    private val dirty = BooleanArray(tilesX * tilesY)

    fun markRectDirty(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        val minX = x.coerceAtLeast(0)
        val minY = y.coerceAtLeast(0)
        val maxX = (x + width - 1).coerceAtMost(this.width - 1)
        val maxY = (y + height - 1).coerceAtMost(this.height - 1)
        if (minX > maxX || minY > maxY) return

        val startTileX = minX / tileSize
        val endTileX = maxX / tileSize
        val startTileY = minY / tileSize
        val endTileY = maxY / tileSize
        for (tileY in startTileY..endTileY) {
            for (tileX in startTileX..endTileX) {
                dirty[tileY * tilesX + tileX] = true
            }
        }
    }

    fun markAllDirty() {
        dirty.fill(true)
    }

    fun dirtyTiles(): List<DirtyTile> =
        buildList {
            for (tileY in 0 until tilesY) {
                for (tileX in 0 until tilesX) {
                    if (!dirty[tileY * tilesX + tileX]) continue
                    val x = tileX * tileSize
                    val y = tileY * tileSize
                    add(
                        DirtyTile(
                            tileX = tileX,
                            tileY = tileY,
                            x = x,
                            y = y,
                            width = minOf(tileSize, width - x),
                            height = minOf(tileSize, height - y),
                        ),
                    )
                }
            }
        }

    fun clear() {
        dirty.fill(false)
    }

    companion object {
        const val DEFAULT_TILE_SIZE = 16
    }
}
```

- [ ] **Step 5: Add RGB565 pixel buffer**

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

class PixelBuffer(
    val width: Int,
    val height: Int,
) {
    private val pixels = ShortArray(width * height)

    fun clear(rgb565: Int) {
        pixels.fill(rgb565.toShort())
    }

    fun setPixel(
        x: Int,
        y: Int,
        rgb565: Int,
    ) {
        if (x !in 0 until width || y !in 0 until height) return
        pixels[y * width + x] = rgb565.toShort()
    }

    fun fillRect(
        x: Int,
        y: Int,
        rectWidth: Int,
        rectHeight: Int,
        rgb565: Int,
    ) {
        if (rectWidth <= 0 || rectHeight <= 0) return
        val minX = x.coerceAtLeast(0)
        val minY = y.coerceAtLeast(0)
        val maxX = (x + rectWidth).coerceAtMost(width)
        val maxY = (y + rectHeight).coerceAtMost(height)
        for (row in minY until maxY) {
            val base = row * width
            for (col in minX until maxX) {
                pixels[base + col] = rgb565.toShort()
            }
        }
    }

    fun copyTile(tile: DirtyTile): ByteArray {
        val out = ByteArray(tile.width * tile.height * BYTES_PER_PIXEL)
        var offset = 0
        for (row in tile.y until tile.y + tile.height) {
            for (col in tile.x until tile.x + tile.width) {
                val value = pixels[row * width + col].toInt() and 0xFFFF
                out[offset++] = (value ushr 8).toByte()
                out[offset++] = value.toByte()
            }
        }
        return out
    }

    fun copyFrom(other: PixelBuffer) {
        require(width == other.width && height == other.height) { "Pixel buffer sizes differ" }
        other.pixels.copyInto(pixels)
    }

    companion object {
        const val BYTES_PER_PIXEL = 2
    }
}
```

- [ ] **Step 6: Run the dirty tile tests**

Run: `./gradlew :core:test --tests "*TileDirtyTrackerTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/display/DisplayModels.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTracker.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/PixelBuffer.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/TileDirtyTrackerTest.kt
git commit -m "feat: add display framebuffer primitives"
```

---

### Task 2: Display state and frame deltas

**Files:**
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt`

- [ ] **Step 1: Write failing display state tests**

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayStateTest {
    @Test
    fun presentReturnsDirtyTilesAndIncrementsSequence() {
        val state = DisplayState(displayId = 7, width = 20, height = 10, pixelFormat = DisplayPixelFormat.RGB565)

        state.fillRect(x = 1, y = 2, width = 3, height = 4, rgb565 = 0xF800)
        val first = assertNotNull(state.present())

        assertEquals(7, first.displayId)
        assertEquals(1L, first.sequence)
        assertEquals(20, first.width)
        assertEquals(10, first.height)
        assertEquals(DisplayPixelFormat.RGB565, first.pixelFormat)
        assertFalse(first.fullRefresh)
        assertTrue(first.tiles.isNotEmpty())
        assertNull(state.present())
    }

    @Test
    fun fullRefreshMarksWholeDisplay() {
        val state = DisplayState(displayId = 1, width = 17, height = 17, pixelFormat = DisplayPixelFormat.RGB565)

        val frame = assertNotNull(state.fullRefresh())

        assertTrue(frame.fullRefresh)
        assertEquals(4, frame.tiles.size)
        assertEquals(1L, frame.sequence)
    }
}
```

- [ ] **Step 2: Run the failing tests**

Run: `./gradlew :core:test --tests "*DisplayStateTest"`

Expected: `Compilation error` mentioning unresolved `DisplayState`.

- [ ] **Step 3: Add display state**

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile

class DisplayState(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val pixelFormat: DisplayPixelFormat,
) {
    private val back = PixelBuffer(width, height)
    private val front = PixelBuffer(width, height)
    private val dirty = TileDirtyTracker(width, height)
    private var sequence: Long = 0

    @Synchronized
    fun clear(rgb565: Int) {
        back.clear(rgb565)
        dirty.markAllDirty()
    }

    @Synchronized
    fun setPixel(
        x: Int,
        y: Int,
        rgb565: Int,
    ) {
        back.setPixel(x, y, rgb565)
        dirty.markRectDirty(x, y, 1, 1)
    }

    @Synchronized
    fun fillRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) {
        back.fillRect(x, y, width, height, rgb565)
        dirty.markRectDirty(x, y, width, height)
    }

    @Synchronized
    fun present(): DisplayFrameDelta? {
        val dirtyTiles = dirty.dirtyTiles()
        if (dirtyTiles.isEmpty()) return null
        sequence += 1
        val frame = buildFrame(dirtyTiles, fullRefresh = false)
        front.copyFrom(back)
        dirty.clear()
        return frame
    }

    @Synchronized
    fun fullRefresh(): DisplayFrameDelta {
        dirty.markAllDirty()
        sequence += 1
        val frame = buildFrame(dirty.dirtyTiles(), fullRefresh = true)
        front.copyFrom(back)
        dirty.clear()
        return frame
    }

    private fun buildFrame(
        tiles: List<DirtyTile>,
        fullRefresh: Boolean,
    ): DisplayFrameDelta =
        DisplayFrameDelta(
            displayId = displayId,
            sequence = sequence,
            width = width,
            height = height,
            pixelFormat = pixelFormat,
            fullRefresh = fullRefresh,
            tiles =
                tiles.map { tile ->
                    DisplayTile(
                        tileX = tile.tileX,
                        tileY = tile.tileY,
                        x = tile.x,
                        y = tile.y,
                        width = tile.width,
                        height = tile.height,
                        payload = back.copyTile(tile),
                    )
                },
        )
}
```

- [ ] **Step 4: Add display registry**

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.display

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayInfo
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class DisplayRegistry {
    private val displays = ConcurrentHashMap<Int, DisplayState>()
    private val pendingFrames = ConcurrentLinkedQueue<DisplayFrameDelta>()

    fun attach(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat = DisplayPixelFormat.RGB565,
    ): DisplayInfo {
        require(width in MIN_SIZE..MAX_SIZE) { "Display width out of range: $width" }
        require(height in MIN_SIZE..MAX_SIZE) { "Display height out of range: $height" }
        val state = DisplayState(displayId, width, height, pixelFormat)
        displays[displayId] = state
        pendingFrames.add(state.fullRefresh())
        return info(state)
    }

    fun resize(
        displayId: Int,
        width: Int,
        height: Int,
        pixelFormat: DisplayPixelFormat = DisplayPixelFormat.RGB565,
    ): DisplayInfo = attach(displayId, width, height, pixelFormat)

    fun detach(displayId: Int) {
        displays.remove(displayId)
    }

    fun firstDisplayId(): Int = displays.keys.minOrNull() ?: -1

    fun info(displayId: Int): DisplayInfo? = displays[displayId]?.let(::info)

    fun clear(
        displayId: Int,
        rgb565: Int,
    ) = displays[displayId]?.clear(rgb565)

    fun setPixel(
        displayId: Int,
        x: Int,
        y: Int,
        rgb565: Int,
    ) = displays[displayId]?.setPixel(x, y, rgb565)

    fun fillRect(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) = displays[displayId]?.fillRect(x, y, width, height, rgb565)

    fun present(displayId: Int) {
        displays[displayId]?.present()?.let(pendingFrames::add)
    }

    fun drainFrames(): List<DisplayFrameDelta> = buildList {
        while (true) {
            add(pendingFrames.poll() ?: break)
        }
    }

    private fun info(state: DisplayState): DisplayInfo =
        DisplayInfo(state.displayId, state.width, state.height, state.pixelFormat)

    companion object {
        const val MIN_SIZE = 1
        const val MAX_SIZE = 4096
    }
}
```

- [ ] **Step 5: Run display state tests**

Run: `./gradlew :core:test --tests "*DisplayStateTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayState.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayRegistry.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/display/DisplayStateTest.kt
git commit -m "feat: add display frame state"
```

---

### Task 3: Runtime display API and builtins

**Files:**
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt`
- Modify: `modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApiTest.kt`

- [ ] **Step 1: Write the failing API test**

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.api

import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VmDisplayApiTest {
    @Test
    fun exposesPrimaryDisplaySizeAndPublishesFrame() {
        val registry = DisplayRegistry()
        registry.attach(displayId = 3, width = 64, height = 32)
        val api = VmDisplayApi(registry)

        assertEquals(3, api.primary())
        assertEquals(true, api.isAttached(3))
        assertEquals(64, api.width(3))
        assertEquals(32, api.height(3))

        api.clear(3, 0x0000)
        api.fillRect(3, 4, 5, 6, 7, 0xF800)
        api.present(3)

        val frame = assertNotNull(registry.drainFrames().lastOrNull())
        assertEquals(3, frame.displayId)
        assertEquals(64, frame.width)
        assertEquals(32, frame.height)
    }
}
```

- [ ] **Step 2: Run the failing API test**

Run: `./gradlew :core:test --tests "*VmDisplayApiTest"`

Expected: `Compilation error` mentioning unresolved `VmDisplayApi`.

- [ ] **Step 3: Add compiler runtime interface**

In `DeviceRuntime.kt`, add `val display: DeviceDisplayApi` to `DeviceRuntime` and add this interface below `DeviceTerminalApi`:

```kotlin
interface DeviceDisplayApi {
    fun primary(): Int

    fun isAttached(displayId: Int): Boolean


    fun width(displayId: Int): Int


    fun height(displayId: Int): Int


    fun clear(
        displayId: Int,
        rgb565: Int,
    )

    fun setPixel(
        displayId: Int,
        x: Int,
        y: Int,
        rgb565: Int,
    )

    fun fillRect(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    )

    fun present(displayId: Int)
}
```

- [ ] **Step 4: Add display capability**

In `DeviceVmModels.kt`, add `DISPLAY` to `DeviceCapability` after `TERMINAL`:

```kotlin
enum class DeviceCapability {
    TERMINAL,
    DISPLAY,
    FILESYSTEM,
    EVENTS,
    SYSTEM,
    REDSTONE,
    PERIPHERALS,
    IDE,
}
```

- [ ] **Step 5: Add `display` builtin module**

In `LanguageBuiltins.kt`, add this `BuiltinModule` next to `terminal` and `stdout`:

```kotlin
BuiltinModule(
    name = "display",
    documentation = "Framebuffer display operations. The attached display endpoint supplies resolution.",
    origin = ModuleOrigin.BASE_VM,
    functions =
        listOf(
            BuiltinFunction("primary", emptyList(), "Int", "Returns the primary display id or -1 when no display is attached."),
            BuiltinFunction("isAttached", listOf("Int"), "Bool", "Returns true when the display id is attached."),
            BuiltinFunction("width", listOf("Int"), "Int", "Returns display width in pixels or 0 when missing."),
            BuiltinFunction("height", listOf("Int"), "Int", "Returns display height in pixels or 0 when missing."),
            BuiltinFunction("clear", listOf("Int", "Int"), "Unit", "Clears the display back buffer to an RGB565 color."),
            BuiltinFunction("setPixel", listOf("Int", "Int", "Int", "Int"), "Unit", "Writes one RGB565 pixel."),
            BuiltinFunction("fillRect", listOf("Int", "Int", "Int", "Int", "Int", "Int"), "Unit", "Fills a rectangle with an RGB565 color."),
            BuiltinFunction("present", listOf("Int"), "Unit", "Publishes changed pixels for a display."),
        ),
),
```

- [ ] **Step 6: Add VM display API implementation**

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm.api

import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayRegistry
import ru.lazyhat.compukterkraft.lang.runtime.DeviceDisplayApi

class VmDisplayApi(
    private val registry: DisplayRegistry,
) : DeviceDisplayApi {
    override fun primary(): Int = registry.firstDisplayId()

    override fun isAttached(displayId: Int): Boolean = registry.info(displayId) != null

    override fun width(displayId: Int): Int = registry.info(displayId)?.width ?: 0

    override fun height(displayId: Int): Int = registry.info(displayId)?.height ?: 0

    override fun clear(
        displayId: Int,
        rgb565: Int,
    ) = registry.clear(displayId, rgb565)

    override fun setPixel(
        displayId: Int,
        x: Int,
        y: Int,
        rgb565: Int,
    ) = registry.setPixel(displayId, x, y, rgb565)

    override fun fillRect(
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rgb565: Int,
    ) = registry.fillRect(displayId, x, y, width, height, rgb565)

    override fun present(displayId: Int) = registry.present(displayId)
}
```

- [ ] **Step 7: Wire `VmRuntime` and `RuntimeHostBridge`**

Add a `DeviceDisplayApi` constructor parameter and property in `VmRuntime`:

```kotlin
private val displayApi: DeviceDisplayApi,
```

```kotlin
override val display: DeviceDisplayApi = displayApi
```

In `RuntimeHostBridge.invoke`, add:

```kotlin
"display" -> invokeDisplay(functionName, arguments)
```

Add this method:

```kotlin
private fun invokeDisplay(
    functionName: String,
    arguments: List<VmValue>,
): VmValue =
    when (functionName) {
        "primary" -> VmValue.IntValue(runtime.display.primary())
        "isAttached" -> VmValue.BoolValue(runtime.display.isAttached(arguments[0].asInt()))
        "width" -> VmValue.IntValue(runtime.display.width(arguments[0].asInt()))
        "height" -> VmValue.IntValue(runtime.display.height(arguments[0].asInt()))
        "clear" -> {
            runtime.display.clear(arguments[0].asInt(), arguments[1].asInt())
            VmValue.UnitValue
        }
        "setPixel" -> {
            runtime.display.setPixel(arguments[0].asInt(), arguments[1].asInt(), arguments[2].asInt(), arguments[3].asInt())
            VmValue.UnitValue
        }
        "fillRect" -> {
            runtime.display.fillRect(
                arguments[0].asInt(),
                arguments[1].asInt(),
                arguments[2].asInt(),
                arguments[3].asInt(),
                arguments[4].asInt(),
                arguments[5].asInt(),
            )
            VmValue.UnitValue
        }
        "present" -> {
            runtime.display.present(arguments[0].asInt())
            VmValue.UnitValue
        }
        else -> error("Unknown display function $functionName")
    }
```

In `ensureCapability`, add:

```kotlin
"display" -> DeviceCapability.DISPLAY
```

- [ ] **Step 8: Run API tests**

Run: `./gradlew :core:test --tests "*VmDisplayApiTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

Run:

```bash
git add modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceRuntime.kt \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/DeviceVmModels.kt \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/frontend/LanguageBuiltins.kt \
  modules/compiler/src/main/kotlin/ru/lazyhat/compukterkraft/lang/runtime/RuntimeHostBridge.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/VmRuntime.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApi.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/api/VmDisplayApiTest.kt
git commit -m "feat: expose display api to vm runtime"
```

---

### Task 4: VM-owned display registry and frame drain

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt`
- Create: `modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmDisplayTest.kt`

- [ ] **Step 1: Write the failing VM display test**

```kotlin
package ru.lazyhat.compukterkraft.core.device.vm

import kotlinx.coroutines.Dispatchers
import ru.lazyhat.compukterkraft.core.device.runtime.FirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.LoadedFirmwareProgramSource
import ru.lazyhat.compukterkraft.core.device.runtime.test.runtimeProfile
import ru.lazyhat.compukterkraft.core.device.runtime.test.runtimeTestWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundDeviceVmDisplayTest {
    private class StaticFirmwareLoader(private val source: String) : FirmwareProgramLoader {
        override fun load(path: String): LoadedFirmwareProgramSource = LoadedFirmwareProgramSource(path, source)
    }

    @Test
    fun firmwareDrawsFrameForAttachedDisplay() {
        runtimeTestWorkspace("display-frame") { workspace ->
            val vm = BackgroundDeviceVm(
                deviceId = 1,
                profile = runtimeProfile().copy(allowedCapabilities = DeviceCapability.entries.toSet()),
                dispatcher = Dispatchers.Default,
                labelProvider = { null },
                logger = DeviceVmLogger { },
                workspace = workspace.host,
                firmwareLoader = StaticFirmwareLoader(
                    """
                    pub fun main() {
                        val id: Int = display::primary()
                        display::clear(id, 0)
                        display::fillRect(id, 0, 0, display::width(id), display::height(id), 2016)
                        display::present(id)
                        while true { sleep(20L) }
                    }
                    """.trimIndent(),
                ),
            )

            vm.attachDisplay(displayId = 5, width = 32, height = 16)
            vm.boot()
            repeat(8) { tick -> vm.requestSlice(tick.toLong()); Thread.sleep(10) }

            val frame = vm.drainDisplayFrames().last()
            assertEquals(5, frame.displayId)
            assertEquals(32, frame.width)
            assertEquals(16, frame.height)
            assertTrue(frame.tiles.isNotEmpty())
        }
    }
}
```

- [ ] **Step 2: Run the failing VM display test**

Run: `./gradlew :core:test --tests "*BackgroundDeviceVmDisplayTest"`

Expected: `Compilation error` mentioning unresolved `attachDisplay` and `drainDisplayFrames`.

- [ ] **Step 3: Add display registry to `BackgroundDeviceVm`**

Add imports:

```kotlin
import ru.lazyhat.compukterkraft.core.device.vm.api.VmDisplayApi
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayRegistry
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
```

Add field:

```kotlin
private val displayRegistry = DisplayRegistry()
```

Add public methods:

```kotlin
fun attachDisplay(
    displayId: Int,
    width: Int,
    height: Int,
    pixelFormat: DisplayPixelFormat = DisplayPixelFormat.RGB565,
) {
    displayRegistry.attach(displayId, width, height, pixelFormat)
    enqueueEvent(VmEvent("display_attach", listOf(displayId, width, height)))
}

fun resizeDisplay(
    displayId: Int,
    width: Int,
    height: Int,
    pixelFormat: DisplayPixelFormat = DisplayPixelFormat.RGB565,
) {
    displayRegistry.resize(displayId, width, height, pixelFormat)
    enqueueEvent(VmEvent("display_resize", listOf(displayId, width, height)))
}

fun detachDisplay(displayId: Int) {
    displayRegistry.detach(displayId)
    enqueueEvent(VmEvent("display_detach", listOf(displayId)))
}

fun drainDisplayFrames(): List<DisplayFrameDelta> = displayRegistry.drainFrames()
```

In `createRuntime`, construct and pass:

```kotlin
val displayApi = VmDisplayApi(displayRegistry)
```

- [ ] **Step 4: Include display module in runtime registry**

In `createRuntimeRegistryProfile`, add:

```kotlin
if (DeviceCapability.DISPLAY in profile.allowedCapabilities) {
    defaultRegistry.module("display")?.let(::add)
}
```

- [ ] **Step 5: Pass `displayApi` to `VmRuntime`**

In the `VmRuntime` constructor call, add:

```kotlin
displayApi = displayApi,
```

- [ ] **Step 6: Run VM display test**

Run: `./gradlew :core:test --tests "*BackgroundDeviceVmDisplayTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVm.kt \
  modules/core/src/test/kotlin/ru/lazyhat/compukterkraft/core/device/vm/BackgroundDeviceVmDisplayTest.kt
git commit -m "feat: connect display registry to background vm"
```

---

### Task 5: Runtime device display sessions and server flush

**Files:**
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt`
- Modify: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt`
- Create: `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/DisplayNetworkBridge.kt`

- [ ] **Step 1: Add display role to `RuntimeDevice.kt`**

```kotlin
interface RuntimeDeviceDisplaySessions {
    fun attachDisplaySession(
        playerUuid: UUID,
        containerId: Int,
        displayId: Int,
        width: Int,
        height: Int,
    )

    fun resizeDisplaySession(
        playerUuid: UUID,
        displayId: Int,
        width: Int,
        height: Int,
    )

    fun detachDisplaySession(
        playerUuid: UUID,
        displayId: Int,
    )
}
```

Add `RuntimeDeviceDisplaySessions` to the `RuntimeDevice` inheritance list.

- [ ] **Step 2: Add display network bridge port**

```kotlin
package ru.lazyhat.compukterkraft.core.device.runtime.ports

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.util.UUID

interface DisplayNetworkBridge {
    fun isDisplaySessionStillBound(
        playerUuid: UUID,
        containerId: Int,
        deviceId: Int,
        displayId: Int,
    ): Boolean

    fun sendDisplayFrame(
        playerUuid: UUID,
        containerId: Int,
        frame: DisplayFrameDelta,
    )
}
```

- [ ] **Step 3: Modify `RuntimeDeviceImpl` constructor**

Add constructor parameter after `terminalNetwork`:

```kotlin
private val displayNetwork: DisplayNetworkBridge,
```

Update every `RuntimeDeviceImpl(...)` call site to pass the host display network bridge.

- [ ] **Step 4: Add display sessions and flush code**

Add session model and map:

```kotlin
private data class DisplaySession(
    val playerUuid: UUID,
    var containerId: Int,
    val displayId: Int,
    var width: Int,
    var height: Int,
)

private val displaySessions = ConcurrentHashMap<Pair<UUID, Int>, DisplaySession>()
```

Add role methods:

```kotlin
override fun attachDisplaySession(
    playerUuid: UUID,
    containerId: Int,
    displayId: Int,
    width: Int,
    height: Int,
) {
    displaySessions[playerUuid to displayId] = DisplaySession(playerUuid, containerId, displayId, width, height)
    vmHandle?.attachDisplay(displayId, width, height)
}

override fun resizeDisplaySession(
    playerUuid: UUID,
    displayId: Int,
    width: Int,
    height: Int,
) {
    val session = displaySessions[playerUuid to displayId] ?: return
    session.width = width
    session.height = height
    vmHandle?.resizeDisplay(displayId, width, height)
}

override fun detachDisplaySession(
    playerUuid: UUID,
    displayId: Int,
) {
    displaySessions.remove(playerUuid to displayId)
    vmHandle?.detachDisplay(displayId)
}
```

In `turnOn`, after assigning `vmHandle`, re-attach existing sessions:

```kotlin
for (session in displaySessions.values) {
    handle.attachDisplay(session.displayId, session.width, session.height)
}
```

In `serverTick`, after host calls and before lifecycle checks, call:

```kotlin
flushDisplaySessions(handle)
```

Add flush method:

```kotlin
private fun flushDisplaySessions(handle: BackgroundDeviceVm) {
    if (displaySessions.isEmpty()) return
    val frames = handle.drainDisplayFrames()
    if (frames.isEmpty()) return

    val sessionsByDisplay = displaySessions.values.groupBy { it.displayId }
    for (frame in frames) {
        val sessions = sessionsByDisplay[frame.displayId].orEmpty()
        for (session in sessions) {
            if (!displayNetwork.isDisplaySessionStillBound(session.playerUuid, session.containerId, deviceId, session.displayId)) {
                detachDisplaySession(session.playerUuid, session.displayId)
                continue
            }
            displayNetwork.sendDisplayFrame(session.playerUuid, session.containerId, frame)
        }
    }
}
```

- [ ] **Step 5: Run core compile**

Run: `./gradlew :core:compileKotlin`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDevice.kt \
  modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/RuntimeDeviceImpl.kt \
    modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/ports/DisplayNetworkBridge.kt
git commit -m "feat: add runtime display sessions"
```

---

### Task 6: Display network packets and codec tests

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/FrameDeltaClientMessage.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayAttachServerMessage.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayResizeServerMessage.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayDetachServerMessage.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlockEntity.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/DisplayMessageCodecTest.kt`

- [ ] **Step 1: Write failing frame codec test**

```kotlin
package ru.lazyhat.compukterkraft.common.computer.network

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.computer.network.client.FrameDeltaClientMessage
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayMessageCodecTest {
    private fun freshBuf(): FriendlyByteBuf = FriendlyByteBuf(Unpooled.buffer())

    @Test
    fun frameDeltaClientMessageRoundTripsTiles() {
        val frame = DisplayFrameDelta(
            displayId = 9,
            sequence = 12L,
            width = 32,
            height = 16,
            pixelFormat = DisplayPixelFormat.RGB565,
            fullRefresh = false,
            tiles = listOf(DisplayTile(0, 0, 0, 0, 16, 16, byteArrayOf(1, 2, 3, 4))),
        )
        val message = FrameDeltaClientMessage(containerId = 4, frame = frame)
        val buf = freshBuf()

        message.write(buf)
        val restored = FrameDeltaClientMessage(buf)

        assertEquals(4, restored.containerId)
        assertEquals(9, restored.frame.displayId)
        assertEquals(12L, restored.frame.sequence)
        assertEquals(DisplayPixelFormat.RGB565, restored.frame.pixelFormat)
        assertTrue(restored.frame.tiles.single().payload.contentEquals(byteArrayOf(1, 2, 3, 4)))
    }
}
```

- [ ] **Step 2: Run failing codec test**

Run: `./gradlew :v1_21_1:v1_21_1-common:test --tests "*DisplayMessageCodecTest"`

Expected: `Compilation error` mentioning unresolved `FrameDeltaClientMessage`.

- [ ] **Step 3: Add frame message**

```kotlin
package ru.lazyhat.compukterkraft.common.computer.network.client

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.network.ClientNetworkContext
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessage
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile

class FrameDeltaClientMessage : NetworkMessage<ClientNetworkContext> {
    val containerId: Int
    val frame: DisplayFrameDelta

    constructor(containerId: Int, frame: DisplayFrameDelta) {
        this.containerId = containerId
        this.frame = frame
    }

    constructor(buf: FriendlyByteBuf) {
        containerId = buf.readVarInt()
        val displayId = buf.readVarInt()
        val sequence = buf.readLong()
        val width = buf.readVarInt()
        val height = buf.readVarInt()
        val format = DisplayPixelFormat.entries[buf.readVarInt()]
        val fullRefresh = buf.readBoolean()
        val tiles = List(buf.readVarInt()) {
            DisplayTile(
                tileX = buf.readVarInt(),
                tileY = buf.readVarInt(),
                x = buf.readVarInt(),
                y = buf.readVarInt(),
                width = buf.readVarInt(),
                height = buf.readVarInt(),
                payload = buf.readByteArray(),
            )
        }
        frame = DisplayFrameDelta(displayId, sequence, width, height, format, fullRefresh, tiles)
    }

    override fun write(buf: FriendlyByteBuf) {
        buf.writeVarInt(containerId)
        buf.writeVarInt(frame.displayId)
        buf.writeLong(frame.sequence)
        buf.writeVarInt(frame.width)
        buf.writeVarInt(frame.height)
        buf.writeVarInt(frame.pixelFormat.ordinal)
        buf.writeBoolean(frame.fullRefresh)
        buf.writeVarInt(frame.tiles.size)
        for (tile in frame.tiles) {
            buf.writeVarInt(tile.tileX)
            buf.writeVarInt(tile.tileY)
            buf.writeVarInt(tile.x)
            buf.writeVarInt(tile.y)
            buf.writeVarInt(tile.width)
            buf.writeVarInt(tile.height)
            buf.writeByteArray(tile.payload)
        }
    }

    override fun handle(context: ClientNetworkContext) {
        context.handleDisplayFrame(containerId, frame)
    }

    override fun type(): MessageType<FrameDeltaClientMessage> = NetworkMessages.FRAME_DELTA
}
```

- [ ] **Step 4: Add serverbound display messages**

Use this pattern for attach; resize and detach are the same class shape with their matching device calls:

```kotlin
package ru.lazyhat.compukterkraft.common.computer.network.server

import net.minecraft.network.FriendlyByteBuf
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenu
import ru.lazyhat.compukterkraft.common.network.MessageType
import ru.lazyhat.compukterkraft.common.network.NetworkMessages
import ru.lazyhat.compukterkraft.common.network.ServerNetworkContext

class DisplayAttachServerMessage : ComputerServerMessage {
    private val displayId: Int
    private val width: Int
    private val height: Int

    constructor(menu: ComputerMenu, displayId: Int, width: Int, height: Int) : super(menu) {
        this.displayId = displayId
        this.width = width
        this.height = height
    }

    constructor(buf: FriendlyByteBuf) : super(buf) {
        displayId = buf.readVarInt()
        width = buf.readVarInt()
        height = buf.readVarInt()
    }

    override fun write(buf: FriendlyByteBuf) {
        super.write(buf)
        buf.writeVarInt(displayId)
        buf.writeVarInt(width)
        buf.writeVarInt(height)
    }

    override fun handle(context: ServerNetworkContext, container: ComputerMenu) {
        container.serverSide.device.attachDisplaySession(context.sender().uuid, targetContainerId, displayId, width, height)
    }

    override fun type(): MessageType<DisplayAttachServerMessage> = NetworkMessages.DISPLAY_ATTACH
}
```

`DisplayResizeServerMessage.handle` must call `resizeDisplaySession(context.sender().uuid, displayId, width, height)`.

`DisplayDetachServerMessage.handle` must call `detachDisplaySession(context.sender().uuid, displayId)`.

- [ ] **Step 5: Register message IDs**

In `NetworkMessages.kt`, import the new classes and add:

```kotlin
val DISPLAY_ATTACH: MessageType<DisplayAttachServerMessage> =
    registerServerbound(22, "display_attach", { buf -> DisplayAttachServerMessage(buf) })
val DISPLAY_RESIZE: MessageType<DisplayResizeServerMessage> =
    registerServerbound(23, "display_resize", { buf -> DisplayResizeServerMessage(buf) })
val DISPLAY_DETACH: MessageType<DisplayDetachServerMessage> =
    registerServerbound(24, "display_detach", { buf -> DisplayDetachServerMessage(buf) })
val FRAME_DELTA: MessageType<FrameDeltaClientMessage> =
    registerClientbound(25, "frame_delta", { buf -> FrameDeltaClientMessage(buf) })
```

- [ ] **Step 6: Add client context handler**

In `ClientNetworkContext.kt`, add:

```kotlin
fun handleDisplayFrame(
    containerId: Int,
    frame: DisplayFrameDelta,
)
```

In `ClientNetworkContextImpl.kt`, add:

```kotlin
override fun handleDisplayFrame(
    containerId: Int,
    frame: DisplayFrameDelta,
) = withCheckedContainerMenu(containerId) {
    handleDisplayFrame(frame)
}
```

- [ ] **Step 7: Implement display network bridge in `BlockEntityRuntimeDeviceHost`**

Add imports for `FrameDeltaClientMessage`, `DisplayNetworkBridge`, and `DisplayFrameDelta`, then add this port beside `terminalNetwork`:

```kotlin
val displayNetwork: DisplayNetworkBridge =
    object : DisplayNetworkBridge {
        override fun isDisplaySessionStillBound(
            playerUuid: UUID,
            containerId: Int,
            deviceId: Int,
            displayId: Int,
        ): Boolean {
            val player = level.server.playerList.getPlayer(playerUuid) ?: return false
            val menu = player.containerMenu
            return menu is ComputerMenu &&
                menu.containerId == containerId &&
                menu.serverSide.device.deviceId == deviceId
        }

        override fun sendDisplayFrame(
            playerUuid: UUID,
            containerId: Int,
            frame: DisplayFrameDelta,
        ) {
            val player = level.server.playerList.getPlayer(playerUuid) ?: return
            ServerNetworking.sendToPlayer(FrameDeltaClientMessage(containerId, frame), player)
        }
    }
```

Then update both `RuntimeDeviceImpl(...)` call sites to pass `host.displayNetwork` after `host.terminalNetwork`:

```kotlin
terminalNetwork = host.terminalNetwork,
displayNetwork = host.displayNetwork,
stateSink = host.stateSink,
```

- [ ] **Step 8: Run codec test**

Run: `./gradlew :v1_21_1:v1_21_1-common:test --tests "*DisplayMessageCodecTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

Run:

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/client/FrameDeltaClientMessage.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayAttachServerMessage.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayResizeServerMessage.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/network/server/DisplayDetachServerMessage.kt \
    modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/context/BlockEntityRuntimeDeviceHost.kt \
    modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/block/ComputerBlockEntity.kt \
    modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/workbench/block/WorkbenchBlockEntity.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/NetworkMessages.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContext.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/network/ClientNetworkContextImpl.kt \
  modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/network/DisplayMessageCodecTest.kt
git commit -m "feat: add display frame network messages"
```

---

### Task 7: Client display double buffer and menu bridge

**Files:**
- Create: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBuffer.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt`
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt`
- Create: `modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBufferTest.kt`

- [ ] **Step 1: Write failing client buffer test**

```kotlin
package ru.lazyhat.compukterkraft.common.computer.client

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientDisplayBufferTest {
    @Test
    fun appliesRgb565TileToStagingAndSwapsToFront() {
        val buffer = ClientDisplayBuffer(displayId = 1, width = 2, height = 1)
        val red565 = byteArrayOf(0xF8.toByte(), 0x00)
        val green565 = byteArrayOf(0x07, 0xE0.toByte())
        val frame = DisplayFrameDelta(
            displayId = 1,
            sequence = 1,
            width = 2,
            height = 1,
            pixelFormat = DisplayPixelFormat.RGB565,
            fullRefresh = true,
            tiles = listOf(DisplayTile(0, 0, 0, 0, 2, 1, red565 + green565)),
        )

        assertTrue(buffer.apply(frame))
        buffer.swapIfDirty()

        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), buffer.frontArgb().toList())
    }
}
```

- [ ] **Step 2: Run failing client buffer test**

Run: `./gradlew :v1_21_1:v1_21_1-common:test --tests "*ClientDisplayBufferTest"`

Expected: `Compilation error` mentioning unresolved `ClientDisplayBuffer`.

- [ ] **Step 3: Add client display buffer**

```kotlin
package ru.lazyhat.compukterkraft.common.computer.client

import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat

class ClientDisplayBuffer(
    val displayId: Int,
    val width: Int,
    val height: Int,
) {
    private val front = IntArray(width * height) { OPAQUE_BLACK }
    private val staging = IntArray(width * height) { OPAQUE_BLACK }
    private var expectedSequence: Long = 1
    private var dirty = false

    fun apply(frame: DisplayFrameDelta): Boolean {
        if (frame.displayId != displayId || frame.width != width || frame.height != height) return false
        if (frame.pixelFormat != DisplayPixelFormat.RGB565) return false
        if (!frame.fullRefresh && frame.sequence != expectedSequence) return false
        if (frame.fullRefresh) staging.fill(OPAQUE_BLACK)
        for (tile in frame.tiles) {
            var offset = 0
            for (row in tile.y until tile.y + tile.height) {
                for (col in tile.x until tile.x + tile.width) {
                    val hi = tile.payload[offset++].toInt() and 0xFF
                    val lo = tile.payload[offset++].toInt() and 0xFF
                    staging[row * width + col] = rgb565ToArgb((hi shl 8) or lo)
                }
            }
        }
        expectedSequence = frame.sequence + 1
        dirty = true
        return true
    }

    fun swapIfDirty(): Boolean {
        if (!dirty) return false
        staging.copyInto(front)
        dirty = false
        return true
    }

    fun frontArgb(): IntArray = front.copyOf()

    private fun rgb565ToArgb(value: Int): Int {
        val r5 = (value ushr 11) and 0x1F
        val g6 = (value ushr 5) and 0x3F
        val b5 = value and 0x1F
        val r = (r5 shl 3) or (r5 ushr 2)
        val g = (g6 shl 2) or (g6 ushr 4)
        val b = (b5 shl 3) or (b5 ushr 2)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        private const val OPAQUE_BLACK = -0x1000000
    }
}
```

- [ ] **Step 4: Add menu bridge**

In `ComputerMenu.kt`, add:

```kotlin
fun handleDisplayFrame(frame: DisplayFrameDelta)
```

In `AbstractComputerMenu.MenuSide.Client`, add:

```kotlin
var displayBuffer: ClientDisplayBuffer? = null
    private set

fun attachDisplayBuffer(buffer: ClientDisplayBuffer) {
    displayBuffer = buffer
}

fun detachDisplayBuffer() {
    displayBuffer = null
}

fun applyDisplayFrame(frame: DisplayFrameDelta) {
    displayBuffer?.apply(frame)
}
```

In `AbstractComputerMenu`, add:

```kotlin
override fun handleDisplayFrame(frame: DisplayFrameDelta) {
    val client = side as? MenuSide.Client
        ?: throw UnsupportedOperationException("Cannot apply display frame on the server")
    client.applyDisplayFrame(frame)
}
```

- [ ] **Step 5: Run client buffer tests**

Run: `./gradlew :v1_21_1:v1_21_1-common:test --tests "*ClientDisplayBufferTest"`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBuffer.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/ComputerMenu.kt \
  modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/computer/menu/AbstractComputerMenu.kt \
  modules/v1_21_1/v1_21_1-common/src/test/kotlin/ru/lazyhat/compukterkraft/common/computer/client/ClientDisplayBufferTest.kt
git commit -m "feat: add client display buffering"
```

---

### Task 8: Screen endpoint lifecycle and minimal display demo

**Files:**
- Modify: `modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck`
- Modify: `modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt`

This task is Phase 1A. It proves frames reach `ClientDisplayBuffer`. On-screen ARGB texture rendering is intentionally left for the next UI task.

- [ ] **Step 1: Attach display buffer from screen init**

In `ComputerTerminalScreen.init` or constructor after menu client side is available, create a display buffer with client-derived dimensions:

```kotlin
private val displayId: Int = container.containerId

private fun currentDisplayWidth(): Int = ((width - 32).coerceAtLeast(64))

private fun currentDisplayHeight(): Int = ((height - 48).coerceAtLeast(48))
```

After screen dimensions are initialized, attach:

```kotlin
val displayWidth = currentDisplayWidth()
val displayHeight = currentDisplayHeight()
menu.clientSide.attachDisplayBuffer(ClientDisplayBuffer(displayId, displayWidth, displayHeight))
ClientNetworking.sendToServer(DisplayAttachServerMessage(menu, displayId, displayWidth, displayHeight))
```

- [ ] **Step 2: Send resize and detach lifecycle messages**

In `containerTick`, compare current dimensions to `menu.clientSide.displayBuffer?.width/height`. When changed, replace the buffer and send:

```kotlin
ClientNetworking.sendToServer(DisplayResizeServerMessage(menu, displayId, displayWidth, displayHeight))
```

In `removed`, before detaching the client buffer, send:

```kotlin
ClientNetworking.sendToServer(DisplayDetachServerMessage(menu, displayId))
menu.clientSide.detachDisplayBuffer()
```

- [ ] **Step 3: Swap client buffer during render tick**

At the start of `containerTick`, call:

```kotlin
menu.clientSide.displayBuffer?.swapIfDirty()
```

Keep rendering minimal in this task: the active display path is considered connected when frames reach `ClientDisplayBuffer`. Minecraft texture rendering for the ARGB buffer is the next UI-focused task after this plan.

- [ ] **Step 4: Add firmware display demo**

At the top of `firmware/bios.ck`, before running `boot.ck`, draw a simple display frame when a display is attached:

```ck
fun draw_boot_frame() {
    val id: Int = display::primary()
    if (id >= 0) {
        display::clear(id, 0)
        display::fillRect(id, 0, 0, display::width(id), display::height(id), 2016)
        display::fillRect(id, 8, 8, 48, 24, 63488)
        display::present(id)
    }
}
```

Call `draw_boot_frame()` from `pub fun main()` before user boot execution.

- [ ] **Step 5: Run ROM compile and full tests**

Run:

```bash
./gradlew :v1_21_1:v1_21_1-neoforge:test --tests "*RomScriptCompileTest"
./gradlew test
```

Expected: both commands finish with `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

Run:

```bash
git add modules/v1_21_1/v1_21_1-common/src/main/kotlin/ru/lazyhat/compukterkraft/common/terminal/screen/ComputerTerminalScreen.kt \
  modules/v1_21_1/v1_21_1-neoforge/src/main/resources/firmware/bios.ck \
  modules/v1_21_1/v1_21_1-neoforge/src/test/kotlin/ru/lazyhat/compukterkraft/impl/RomScriptCompileTest.kt
git commit -m "feat: attach gui display endpoint"
```

---

## Final verification

- [ ] Run compiler tests: `./gradlew :compiler:test`
- [ ] Run core display tests: `./gradlew :core:test --tests "*Display*"`
- [ ] Run common display/network tests: `./gradlew :v1_21_1:v1_21_1-common:test --tests "*Display*"`
- [ ] Run full suite: `./gradlew test`
- [ ] Confirm every command reports `BUILD SUCCESSFUL`.
- [ ] Run `git status --short` and confirm only intentional changes are present.

## Notes for executors

- Do not add new `Instruction` variants in this phase. The display API is dispatched through the existing runtime host bridge.
- If you decide to add bytecode instructions despite this plan, update `modules/core/src/main/kotlin/ru/lazyhat/compukterkraft/core/device/runtime/DeviceProgramSupport.kt` in the same commit.
- Do not keep the old terminal screen as a fallback requirement. It may remain temporarily as dead code only until the display path has a replacement UI renderer.
- User-facing translation keys generate Kotlin methods by key name. Do not bulk-rename `computerId`/translation-derived method calls while working on this branch.
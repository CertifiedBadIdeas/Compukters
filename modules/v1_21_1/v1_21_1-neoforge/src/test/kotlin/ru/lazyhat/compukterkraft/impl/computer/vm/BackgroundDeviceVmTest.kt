/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukterkraft.impl.computer.vm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.lazyhat.compukterkraft.core.device.runtime.FirmwareProgramLoader
import ru.lazyhat.compukterkraft.core.device.runtime.HostCallDispatcher
import ru.lazyhat.compukterkraft.core.device.runtime.LoadedFirmwareProgramSource
import ru.lazyhat.compukterkraft.core.device.runtime.NoOpRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.vm.BackgroundDeviceVm
import ru.lazyhat.compukterkraft.core.device.vm.DeviceVmLogger
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceHost
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceInitializer
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCapability
import ru.lazyhat.compukterkraft.lang.runtime.DeviceCpuResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceMemoryResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceProfile
import ru.lazyhat.compukterkraft.lang.runtime.DeviceQueueResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceResources
import ru.lazyhat.compukterkraft.lang.runtime.DeviceStorageResources
import ru.lazyhat.compukterkraft.lang.runtime.VmEvent
import ru.lazyhat.compukterkraft.lang.runtime.VmState
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackgroundDeviceVmTest {
    private class StaticFirmwareLoader(
        private val source: String,
    ) : FirmwareProgramLoader {
        override fun load(path: String): LoadedFirmwareProgramSource = LoadedFirmwareProgramSource(path, source)
    }

    private class ClasspathFirmwareLoader : FirmwareProgramLoader {
        override fun load(path: String): LoadedFirmwareProgramSource {
            val source =
                BackgroundDeviceVmTest::class.java.classLoader
                    .getResourceAsStream("firmware/$path")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("firmware/$path missing from classpath")
            return LoadedFirmwareProgramSource(path, source)
        }
    }

    private fun runVmTicks(
        vm: BackgroundDeviceVm,
        ticks: Int = 16,
        hostCallDispatcher: HostCallDispatcher? = null,
    ) = runBlocking {
        repeat(ticks) { tick ->
            hostCallDispatcher?.let { dispatcher ->
                serviceVmTickForTest(vm, tick.toLong(), dispatcher::dispatch, NoOpRuntimeMetricsCollector)
            } ?: run {
                vm.requestSlice(tick.toLong())
            }
            kotlinx.coroutines.delay(10)
        }
    }

    private fun firmwareTestProfile(): DeviceProfile =
        DeviceProfile(
            id = "rom-terminal-test",
            displayName = "ROM Terminal Test",
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

    private fun serviceVmTickForTest(
        vm: BackgroundDeviceVm,
        serverTick: Long,
        dispatchHostCall: (ru.lazyhat.compukterkraft.lang.runtime.HostCall) -> ru.lazyhat.compukterkraft.lang.runtime.HostResult,
        runtimeMetricsCollector: RuntimeMetricsCollector,
    ) {
        val (_, requestNanos) = measureNanos { vm.requestSlice(serverTick) }
        runtimeMetricsCollector.recordRequestSlice(requestNanos)

        val spinDeadline =
            System.nanoTime() +
                vm.profile.resources.cpu.wallTimeGuardNanosPerSlice
                    .coerceAtLeast(1L)
        var remainingIdlePolls: Int = 8
        var drainedCalls: Int = 0
        var dispatchedCalls: Int = 0
        var deliveredResults: Int = 0
        var totalDrainNanos: Long = 0L
        var totalDispatchNanos: Long = 0L
        var totalDeliverNanos: Long = 0L

        while (true) {
            val (calls, drainNanos) = measureNanos { vm.drainHostCalls() }
            totalDrainNanos += drainNanos
            drainedCalls += calls.size
            if (calls.isEmpty()) {
                if (remainingIdlePolls <= 0 || System.nanoTime() >= spinDeadline) {
                    break
                }
                remainingIdlePolls -= 1
                Thread.onSpinWait()
                continue
            }

            remainingIdlePolls = 8
            val (results, dispatchNanos) = measureNanos { calls.map(dispatchHostCall) }
            totalDispatchNanos += dispatchNanos
            dispatchedCalls += calls.size

            val (_, deliverNanos) =
                measureNanos {
                    if (results.isNotEmpty()) {
                        vm.deliverHostResults(results)
                    }
                }
            totalDeliverNanos += deliverNanos
            deliveredResults += results.size

            if (System.nanoTime() >= spinDeadline) {
                break
            }
        }

        runtimeMetricsCollector.recordHostCallDrain(drainedCalls, totalDrainNanos)
        runtimeMetricsCollector.recordHostCallDispatch(dispatchedCalls, totalDispatchNanos)
        runtimeMetricsCollector.recordHostResultDelivery(deliveredResults, totalDeliverNanos)
    }

    private inline fun <T> measureNanos(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - started)
    }

    @Test
    fun bundledFirmwareBootsRomTerminalAndRendersShellOutput() {
        val root = createTempDirectory("compukterkraft-rom-terminal")

        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val logs = mutableListOf<String>()
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger(logs::add),
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                )

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 80, hostCallDispatcher = HostCallDispatcher(1, workspace))

            val frames = vm.drainDisplayFrames()
            val rendered =
                assertNotNull(
                    frames.lastOrNull { frame ->
                        frame.tiles.any { tile ->
                            tile.payload.containsRgb565(0x0000) &&
                                tile.payload.containsRgb565(0x07E0)
                        }
                    },
                    "terminal frame missing; frames=${frames.size} state=${vm.snapshot().state} logs=$logs",
                )
            assertTrue(rendered.tiles.isNotEmpty(), "terminal frame missing; frames=${frames.size} state=${vm.snapshot().state} logs=$logs")
            assertTrue(frames.greenPixelCount() > 0, "terminal frame should contain rendered glyph pixels")
            assertTrue(frames.hasTextLikeGlyphCell(), "terminal text should render glyph shapes, not solid rectangles")
            assertTrue(vm.snapshot().state.isActive, vm.snapshot().state.toString())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun bundledFirmwareStatusRendersGlyphShapes() {
        val root = createTempDirectory("compukterkraft-bios-text")

        try {
            val workspace = DeviceWorkspaceHost(root)
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                )

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            runVmTicks(vm, ticks = 24, hostCallDispatcher = HostCallDispatcher(1, workspace))

            val frames = vm.drainDisplayFrames()
            assertTrue(frames.greenPixelCount() > 0, "firmware status should draw glyph pixels")
            assertTrue(
                frames.hasTextLikeGlyphCell(),
                "firmware status should render glyph shapes, not solid rectangles",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun bundledRomTerminalEchoesTypedInputAndSubmitsCommandsToShell() {
        val root = createTempDirectory("compukterkraft-rom-terminal-input")

        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                )

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            val dispatcher = HostCallDispatcher(1, workspace)
            runVmTicks(vm, ticks = 80, hostCallDispatcher = dispatcher)

            "help".forEach { ch -> vm.enqueueEvent(VmEvent("char", listOf(byteArrayOf(ch.code.toByte())))) }
            runVmTicks(vm, ticks = 20, hostCallDispatcher = dispatcher)
            val typedFrames = vm.drainDisplayFrames()
            assertTrue(typedFrames.isNotEmpty(), "typed input should update display frames")
            assertTrue(typedFrames.greenPixelCount() > 0, "typed input should draw glyph pixels")

            vm.enqueueEvent(VmEvent("key", listOf(KeyCodes.KEY_ENTER, false)))
            runVmTicks(vm, ticks = 40, hostCallDispatcher = dispatcher)
            val submittedFrames = vm.drainDisplayFrames()
            assertTrue(submittedFrames.isNotEmpty(), "submitted command should render shell output through display frames")
            assertTrue(submittedFrames.greenPixelCount() > 0, "shell output should draw glyph pixels")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun bundledRomTerminalKeepsPromptVisibleWhileTyping() {
        val root = createTempDirectory("compukterkraft-rom-terminal-prompt")

        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                )

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            val dispatcher = HostCallDispatcher(1, workspace)
            runVmTicks(vm, ticks = 80, hostCallDispatcher = dispatcher)
            val bootFrames = vm.drainDisplayFrames()

            vm.enqueueEvent(VmEvent("char", listOf(byteArrayOf('h'.code.toByte()))))
            runVmTicks(vm, ticks = 12, hostCallDispatcher = dispatcher)
            val typedFrames = vm.drainDisplayFrames()

            val inputRow = bootFrames.lastGreenRow()
            assertTrue(inputRow >= 0, "boot output should include a prompt row")
            assertTrue(
                (bootFrames + typedFrames).cellGreenPixelCount(column = 2, row = inputRow) > 0,
                "typing should preserve the prompt glyph before the input text",
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun bundledRomTerminalHandlesBackspaceWithoutFramebufferRedrawPerKeypress() {
        val root = createTempDirectory("compukterkraft-rom-terminal-backspace")

        try {
            DeviceWorkspaceInitializer(root).ensureInitialized(1)
            val workspace = DeviceWorkspaceHost(root)
            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = firmwareTestProfile(),
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = ClasspathFirmwareLoader(),
                )

            vm.attachDisplay(displayId = 9, width = 96, height = 48)
            assertTrue(vm.boot())
            val dispatcher = HostCallDispatcher(1, workspace)
            runVmTicks(vm, ticks = 80, hostCallDispatcher = dispatcher)
            vm.drainDisplayFrames()

            "helx".forEach { ch -> vm.enqueueEvent(VmEvent("char", listOf(byteArrayOf(ch.code.toByte())))) }
            vm.enqueueEvent(VmEvent("key", listOf(KeyCodes.KEY_BACKSPACE, false)))
            vm.enqueueEvent(VmEvent("char", listOf(byteArrayOf('p'.code.toByte()))))
            runVmTicks(vm, ticks = 30, hostCallDispatcher = dispatcher)

            val editFrames = vm.drainDisplayFrames()
            assertTrue(editFrames.isNotEmpty(), "typing should update the dirty input line")
            assertTrue(editFrames.none { it.fullRefresh }, "typing should not request full-refresh frames")
            assertTrue(
                editFrames.all { frame -> frame.dirtyPixelArea() < frame.width * frame.height },
                "typing should update dirty line tiles instead of the whole framebuffer: $editFrames",
            )

            vm.enqueueEvent(VmEvent("key", listOf(KeyCodes.KEY_ENTER, false)))
            runVmTicks(vm, ticks = 40, hostCallDispatcher = dispatcher)
            val submittedFrames = vm.drainDisplayFrames()
            assertTrue(submittedFrames.isNotEmpty(), "submitted command should render shell output")
            assertTrue(submittedFrames.greenPixelCount() > 0, "shell output should draw glyph pixels")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun ByteArray.containsRgb565(value: Int): Boolean {
        val hi = (value ushr 8).toByte()
        val lo = value.toByte()
        var index = 0
        while (index + 1 < size) {
            if (this[index] == hi && this[index + 1] == lo) return true
            index += 2
        }
        return false
    }

    private fun ByteArray.countRgb565(value: Int): Int {
        val hi = (value ushr 8).toByte()
        val lo = value.toByte()
        var count = 0
        var index = 0
        while (index + 1 < size) {
            if (this[index] == hi && this[index + 1] == lo) count += 1
            index += 2
        }
        return count
    }

    private fun List<DisplayFrameDelta>.greenPixelCount(): Int =
        sumOf { frame ->
            frame.tiles.sumOf { tile -> tile.payload.countRgb565(0x07E0) }
        }

    private fun List<DisplayFrameDelta>.hasTextLikeGlyphCell(): Boolean {
        val lastFrame = lastOrNull() ?: return false
        val pixels = composePixels()

        val columns = lastFrame.width / 6
        val rows = lastFrame.height / 9
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val green = cellGreenPixelCount(pixels, lastFrame.width, column, row)
                if (green in 1 until 35) return true
            }
        }
        return false
    }

    private fun List<DisplayFrameDelta>.cellGreenPixelCount(
        column: Int,
        row: Int,
    ): Int {
        val lastFrame = lastOrNull() ?: return 0
        return cellGreenPixelCount(composePixels(), lastFrame.width, column, row)
    }

    private fun List<DisplayFrameDelta>.lastGreenRow(): Int {
        val lastFrame = lastOrNull() ?: return -1
        val pixels = composePixels()
        var result = -1
        val rows = lastFrame.height / 9
        val columns = lastFrame.width / 6
        for (row in 0 until rows) {
            var green = 0
            for (column in 0 until columns) {
                green += cellGreenPixelCount(pixels, lastFrame.width, column, row)
            }
            if (green > 0) result = row
        }
        return result
    }

    private fun cellGreenPixelCount(
        pixels: IntArray,
        width: Int,
        column: Int,
        row: Int,
    ): Int {
        var green = 0
        for (y in 0 until 7) {
            for (x in 0 until 5) {
                if (pixels[(row * 9 + y) * width + column * 6 + x] == 0x07E0) {
                    green += 1
                }
            }
        }
        return green
    }

    private fun List<DisplayFrameDelta>.composePixels(): IntArray {
        val lastFrame = lastOrNull() ?: return IntArray(0)
        val pixels = IntArray(lastFrame.width * lastFrame.height)
        for (frame in this) {
            for (tile in frame.tiles) {
                var y = 0
                while (y < tile.height) {
                    var x = 0
                    while (x < tile.width) {
                        val payloadIndex = (y * tile.width + x) * 2
                        val value =
                            ((tile.payload[payloadIndex].toInt() and 0xFF) shl 8) or
                                (tile.payload[payloadIndex + 1].toInt() and 0xFF)
                        pixels[(tile.y + y) * frame.width + tile.x + x] = value
                        x += 1
                    }
                    y += 1
                }
            }
        }
        return pixels
    }

    private fun DisplayFrameDelta.dirtyPixelArea(): Int = tiles.sumOf { tile -> tile.width * tile.height }

    @Test
    fun surfacesRomLimitFailureAsCrashedState() {
        val root = createTempDirectory("compukterkraft-background-vm")

        try {
            val workspace = DeviceWorkspaceHost(root)

            val profile =
                DeviceProfile(
                    id = "tiny-rom",
                    displayName = "Tiny ROM",
                    cpuBudgetNanosPerSlice = 1_000_000,
                    maxEventQueueSize = 16,
                    allowedCapabilities = setOf(DeviceCapability.SYSTEM),
                    resources =
                        DeviceResources(
                            cpu = DeviceCpuResources(wallTimeGuardNanosPerSlice = 1_000_000),
                            memory = DeviceMemoryResources(),
                            storage = DeviceStorageResources(programRomBytes = 1, diskBytes = 1024),
                            queues = DeviceQueueResources(eventQueueSlots = 16, hostCallQueueSlots = 16),
                        ),
                )

            val vm =
                BackgroundDeviceVm(
                    deviceId = 1,
                    profile = profile,
                    dispatcher = Dispatchers.Default,
                    labelProvider = { null },
                    logger = DeviceVmLogger { },
                    workspace = workspace,
                    firmwareLoader = StaticFirmwareLoader("pub fun main() { }"),
                )

            vm.boot()

            val terminalState =
                runBlocking {
                    val terminalState =
                        async {
                            withTimeout(5_000) {
                                vm.terminalStates.first()
                            }
                        }

                    vm.requestSlice(0)
                    terminalState.await()
                }

            assertTrue(terminalState is VmState.Crashed)
            assertTrue(terminalState.errorMessage?.contains("ROM limit") == true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

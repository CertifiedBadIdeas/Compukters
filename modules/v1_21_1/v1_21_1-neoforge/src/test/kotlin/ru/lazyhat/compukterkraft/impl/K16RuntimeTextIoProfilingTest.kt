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

package ru.lazyhat.compukterkraft.impl

import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceEvents
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.input.PasteInputEvent
import ru.lazyhat.compukterkraft.core.device.runtime.K16RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.RecordingRuntimeMetricsCollector
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.device.vm.DeviceProfileRegistry
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16BiosFlashWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.blazing.K16ComputerRuntimeFactory
import ru.lazyhat.compukterkraft.lang.runtime.storage.K16SystemVolumeWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertTrue

class K16RuntimeTextIoProfilingTest {
    @Test
    fun printsK16TextIoRuntimeSummary() {
        val workspace = createTempDirectory("k16-runtime-text-io-profile-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        val profile = DeviceProfileRegistry.forFamily(DeviceFamily.NORMAL)
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 226,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "text-io-profiling"),
                endpointFactory = {
                    K16ComputerRuntimeFactory.createFromBiosFlash(
                        biosFlashPath = biosFlashPath,
                        storage0Path = storage0Path,
                        maxSteps = profile.resources.cpu.maxStepsPerSlice,
                        maxTurnsPerTick = profile.resources.cpu.maxTurnsPerTick,
                    )
                },
                stateSink = {},
                metricsCollector = metrics,
            )

        try {
            device.turnOn()
            waitForTerminal(device, "initial shell prompt") { terminal -> terminal.contains("K16> ") }
            val typedCommand = "ticks\n"
            dispatchText(device, typedCommand)
            waitForTerminal(device, "typed ticks output") { terminal -> terminal.contains("TICKS ") }
            val pastedCommand = "echo text-io-profile\n"
            dispatchPasteText(device, pastedCommand)
            waitForTerminal(device, "pasted echo output") { terminal -> terminal.contains("text-io-profile") }

            val snapshot = metrics.snapshot()
            val summary = snapshot.summary()
            println(summary)

            assertTrue(snapshot.vm.k16TextInputEvents >= typedCommand.encodeToByteArray().size + 1)
            val expectedInputBytes = typedCommand.encodeToByteArray().size + pastedCommand.encodeToByteArray().size
            assertTrue(snapshot.vm.k16TextInputBytes >= expectedInputBytes)
            assertTrue(snapshot.vm.k16TextInputNanos >= 0)
            assertTrue(snapshot.vm.k16SerialOutputSnapshots > 0)
            assertTrue(snapshot.vm.k16SerialOutputSnapshotBytes > 0)
            assertTrue(snapshot.k16.gpu.blitBufferCommands > 0)
            assertTrue(snapshot.k16.gpu.blitSourceBytes > 0)
            assertTrue(snapshot.k16.gpu.presentCommands > 0)
            assertTrue(snapshot.k16.gpu.frames > 0)
            assertTrue(snapshot.k16.gpu.framePayloadBytes > 0)
            assertTrue(summary.contains("k16TextOutput: snapshots="))
            assertTrue(summary.contains("k16Gpu: blits="))
            assertTrue(summary.contains("k16TextInput: events="))
        } finally {
            device.close()
        }
    }

    @Test
    fun printsK16KeyBurstRenderLatency() {
        val workspace = createTempDirectory("k16-runtime-key-burst-profile-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        val profile = DeviceProfileRegistry.forFamily(DeviceFamily.NORMAL)
        val metrics = RecordingRuntimeMetricsCollector()
        val displayNetwork = CapturingDisplayNetworkBridge()
        val playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000226")
        val containerId = 226
        val device =
            K16RuntimeDevice(
                deviceId = 226,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "key-burst-profiling"),
                endpointFactory = {
                    K16ComputerRuntimeFactory.createFromBiosFlash(
                        biosFlashPath = biosFlashPath,
                        storage0Path = storage0Path,
                        maxSteps = profile.resources.cpu.maxStepsPerSlice,
                        maxTurnsPerTick = profile.resources.cpu.maxTurnsPerTick,
                    )
                },
                stateSink = {},
                displayNetwork = displayNetwork,
                metricsCollector = metrics,
            )

        try {
            device.turnOn()
            waitForTerminal(device, "initial shell prompt") { terminal -> terminal.contains("K16> ") }
            device.attachDisplaySession(
                playerUuid = playerUuid,
                containerId = containerId,
                displayId = K16_DISPLAY_ID,
                width = K16_DISPLAY_WIDTH,
                height = K16_DISPLAY_HEIGHT,
            )
            tickAndSync(device)
            displayNetwork.clear()
            val before = metrics.snapshot()
            val burst = "abcdef"
            val startedAt = System.nanoTime()
            for (byte in burst.encodeToByteArray()) {
                DeviceEvents.dispatch(device, KeyInputEvent.Character(byte))
            }
            val inputQueuedNanos = System.nanoTime() - startedAt
            var ticks = 0
            var visibleNanos: Long? = null
            var framesSentNanos: Long? = null

            while (ticks < 80 && (visibleNanos == null || framesSentNanos == null)) {
                ticks += 1
                tickAndSync(device)
                val elapsed = System.nanoTime() - startedAt
                val terminal = device.snapshotRuntimeState()?.let(::terminalText) ?: ""
                if (visibleNanos == null && terminal.contains("K16> $burst")) {
                    visibleNanos = elapsed
                }
                if (framesSentNanos == null && displayNetwork.sentFrames().isNotEmpty()) {
                    framesSentNanos = elapsed
                }
                Thread.sleep(1)
            }

            val after = metrics.snapshot()
            val sentFrames = displayNetwork.sentFrames()
            val gpuBefore = before.k16.gpu
            val gpuAfter = after.k16.gpu
            println(
                "k16KeyBurst: chars=${burst.length}, inputQueued=${inputQueuedNanos} ns, " +
                    "visible=${visibleNanos ?: -1} ns, framesSent=${framesSentNanos ?: -1} ns, ticks=$ticks",
            )
            println(
                "k16KeyBurstVm: slices=${after.vm.k16RunSlices - before.vm.k16RunSlices}, " +
                    "runTime=${after.vm.k16RunNanos - before.vm.k16RunNanos} ns, " +
                    "yieldSignals=${after.vm.k16RunYieldSignals - before.vm.k16RunYieldSignals}, " +
                    "waitSignals=${after.vm.k16RunWaitSignals - before.vm.k16RunWaitSignals}, " +
                    "inputWakeups=${after.vm.k16WaitInputWakeups - before.vm.k16WaitInputWakeups}",
            )
            println(
                "k16KeyBurstGpu: blits=${gpuAfter.blitBufferCommands - gpuBefore.blitBufferCommands}, " +
                    "presents=${gpuAfter.presentCommands - gpuBefore.presentCommands}, " +
                    "frames=${gpuAfter.frames - gpuBefore.frames}, " +
                    "tiles=${gpuAfter.frameTiles - gpuBefore.frameTiles}, " +
                    "frameBytes=${gpuAfter.framePayloadBytes - gpuBefore.framePayloadBytes}, " +
                    "sentFrames=${sentFrames.size}, sentTiles=${sentFrames.sumOf { it.frame.tiles.size }}, " +
                    "sentPayloadBytes=${sentFrames.sumOf { frame -> frame.frame.tiles.sumOf { it.payload.size } }}",
            )

            assertTrue(visibleNanos != null, "Burst did not become visible in terminal snapshot")
            assertTrue(framesSentNanos != null, "Burst did not produce a sent display frame")
            assertTrue(sentFrames.isNotEmpty())
        } finally {
            device.close()
        }
    }

    @Test
    fun printsK16LsCommandRuntimeLatency() {
        val workspace = createTempDirectory("k16-runtime-ls-profile-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        val profile = DeviceProfileRegistry.forFamily(DeviceFamily.NORMAL)
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 226,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "ls-profiling"),
                endpointFactory = {
                    K16ComputerRuntimeFactory.createFromBiosFlash(
                        biosFlashPath = biosFlashPath,
                        storage0Path = storage0Path,
                        maxSteps = profile.resources.cpu.maxStepsPerSlice,
                        maxTurnsPerTick = profile.resources.cpu.maxTurnsPerTick,
                    )
                },
                stateSink = {},
                metricsCollector = metrics,
            )

        try {
            device.turnOn()
            waitForTerminal(device, "initial shell prompt") { terminal -> terminal.contains("K16> ") }
            val before = metrics.snapshot()
            val command = "ls /bin\n"
            val startedAt = System.nanoTime()
            DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap(command.encodeToByteArray())))
            val inputQueuedNanos = System.nanoTime() - startedAt
            var ticks = 0
            var visibleNanos: Long? = null

            while (ticks < 200 && visibleNanos == null) {
                ticks += 1
                tickAndSync(device)
                val elapsed = System.nanoTime() - startedAt
                val terminal = device.snapshotRuntimeState()?.let(::terminalText) ?: ""
                val commandIndex = terminal.indexOf("K16> ls /bin")
                val outputIndex =
                    if (commandIndex >= 0) {
                        terminal.indexOf("ls.kx", startIndex = commandIndex + "K16> ls /bin".length)
                    } else {
                        -1
                    }
                val returnedPromptIndex =
                    if (outputIndex >= 0) {
                        terminal.indexOf("K16> ", startIndex = outputIndex + "ls.kx".length)
                    } else {
                        -1
                    }
                if (returnedPromptIndex > outputIndex) {
                    visibleNanos = elapsed
                }
                Thread.sleep(1)
            }

            val after = metrics.snapshot()
            val gpuBefore = before.k16.gpu
            val gpuAfter = after.k16.gpu
            val storageBefore = before.k16.storage0
            val storageAfter = after.k16.storage0
            println(
                "k16LsCommand: command=ls /bin, inputQueued=${inputQueuedNanos} ns, " +
                    "visible=${visibleNanos ?: -1} ns, ticks=$ticks",
            )
            println(
                "k16LsCommandVm: slices=${after.vm.k16RunSlices - before.vm.k16RunSlices}, " +
                    "runTime=${after.vm.k16RunNanos - before.vm.k16RunNanos} ns, " +
                    "yieldSignals=${after.vm.k16RunYieldSignals - before.vm.k16RunYieldSignals}, " +
                    "waitSignals=${after.vm.k16RunWaitSignals - before.vm.k16RunWaitSignals}, " +
                    "pauseSignals=${after.vm.k16RunPauseSignals - before.vm.k16RunPauseSignals}, " +
                    "inputWakeups=${after.vm.k16WaitInputWakeups - before.vm.k16WaitInputWakeups}",
            )
            println(
                "k16LsCommandStorage: reads=${storageAfter.readCommands - storageBefore.readCommands}, " +
                    "writes=${storageAfter.writeCommands - storageBefore.writeCommands}, " +
                    "flushes=${storageAfter.flushCommands - storageBefore.flushCommands}, " +
                    "bytesRead=${storageAfter.bytesRead - storageBefore.bytesRead}, " +
                    "bytesWritten=${storageAfter.bytesWritten - storageBefore.bytesWritten}",
            )
            println(
                "k16LsCommandGpu: blits=${gpuAfter.blitBufferCommands - gpuBefore.blitBufferCommands}, " +
                    "presents=${gpuAfter.presentCommands - gpuBefore.presentCommands}, " +
                    "frames=${gpuAfter.frames - gpuBefore.frames}, " +
                    "tiles=${gpuAfter.frameTiles - gpuBefore.frameTiles}, " +
                    "frameBytes=${gpuAfter.framePayloadBytes - gpuBefore.framePayloadBytes}",
            )

            assertTrue(visibleNanos != null, "ls /bin did not finish and return to the prompt")
        } finally {
            device.close()
        }
    }

    private fun dispatchText(
        device: K16RuntimeDevice,
        text: String,
    ) {
        for (byte in text.encodeToByteArray()) {
            DeviceEvents.dispatch(device, KeyInputEvent.Character(byte))
        }
        tickAndSync(device)
    }

    private fun dispatchPasteText(
        device: K16RuntimeDevice,
        text: String,
    ) {
        DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap(text.encodeToByteArray())))
        tickAndSync(device)
    }

    private fun waitForTerminal(
        device: K16RuntimeDevice,
        description: String,
        predicate: (String) -> Boolean,
    ) {
        repeat(400) {
            tickAndSync(device)
            val snapshot = device.snapshotRuntimeState()
            if (snapshot != null && predicate(terminalText(snapshot))) return
            Thread.sleep(10)
        }
        val snapshot = device.snapshotRuntimeState()
        val terminal = snapshot?.let(::terminalText) ?: "<no snapshot>"
        error("K16 text IO profiling did not observe $description; terminal: $terminal")
    }

    private fun tickAndSync(device: K16RuntimeDevice) {
        device.serverTick()
        device.snapshotRuntimeState()
    }

    private fun terminalText(snapshot: ByteArray): String =
        snapshotRamBytes(snapshot, start = K16_TERMINAL_CELLS_ADDR, size = K16_TERMINAL_ROWS * K16_TERMINAL_COLUMNS)
            .map { byte -> if (byte in 0x20..0x7e) byte.toInt().toChar() else ' ' }
            .joinToString(separator = "")

    private fun snapshotRamBytes(
        snapshot: ByteArray,
        start: Int,
        size: Int,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        require(snapshot.copyOfRange(0, 8).contentEquals("K16SNAP\u0000".encodeToByteArray()))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        require(start >= 0 && size >= 0 && start + size <= ramSize)
        return snapshot.copyOfRange(headerSize + start, headerSize + start + size)
    }
}

private const val K16_TERMINAL_CELLS_ADDR = 0x3000
private const val K16_TERMINAL_COLUMNS = 53
private const val K16_TERMINAL_ROWS = 25
private const val K16_DISPLAY_ID = 1
private const val K16_DISPLAY_WIDTH = 320
private const val K16_DISPLAY_HEIGHT = 200

private data class TimedDisplayFrame(
    val nanos: Long,
    val frame: DisplayFrameDelta,
)

private class CapturingDisplayNetworkBridge : DisplayNetworkBridge {
    private val sentFrames = CopyOnWriteArrayList<TimedDisplayFrame>()

    override fun isDisplaySessionStillBound(
        playerUuid: UUID,
        containerId: Int,
        deviceId: Int,
        displayId: Int,
    ): Boolean = true

    override fun sendDisplayFrame(
        playerUuid: UUID,
        containerId: Int,
        frame: DisplayFrameDelta,
    ) {
        sentFrames += TimedDisplayFrame(System.nanoTime(), frame)
    }

    fun clear() {
        sentFrames.clear()
    }

    fun sentFrames(): List<TimedDisplayFrame> = sentFrames.toList()
}

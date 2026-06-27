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
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeK16BusTrafficMetrics
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeK16GpuMetrics
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeK16MmioDeviceMetrics
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeK16OsMetrics
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeK16StatsMetrics
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeK16StorageMetrics
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingSnapshot
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeVmMetrics
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
    fun formatsK16RuntimePhaseBreakdownWithStorageAndDisplayDeltas() {
        val before =
            RuntimeProfilingSnapshot(
                vm =
                    RuntimeVmMetrics(
                        k16RunSlices = 1,
                        k16RunNanos = 10,
                        k16RunWaitSignals = 1,
                        k16GpuFrameBytes = 100,
                        k16TextInputBytes = 2,
                    ),
                k16 =
                    RuntimeK16StatsMetrics(
                        os =
                            RuntimeK16OsMetrics(
                                pathLookups = 10,
                                inodeLoads = 11,
                                dirEntryScans = 12,
                                fileOpens = 13,
                                fileReads = 14,
                                statCalls = 15,
                                processSpawns = 16,
                                programLoads = 17,
                                dynamicImportLoads = 18,
                                libraryLoads = 19,
                                readDirCalls = 20,
                            ),
                        devices =
                            listOf(
                                RuntimeK16MmioDeviceMetrics(
                                    deviceId = 1,
                                    base = 0,
                                    size = 1,
                                    traffic = RuntimeK16BusTrafficMetrics(),
                                    storage = RuntimeK16StorageMetrics(readCommands = 3, bytesRead = 1024),
                                    gpu = RuntimeK16GpuMetrics(frames = 4, framePayloadBytes = 128),
                                ),
                            ),
                    ),
            )
        val after =
            RuntimeProfilingSnapshot(
                vm =
                    RuntimeVmMetrics(
                        k16RunSlices = 6,
                        k16RunNanos = 60,
                        k16RunWaitSignals = 2,
                        k16GpuFrameBytes = 180,
                        k16TextInputBytes = 7,
                    ),
                k16 =
                    RuntimeK16StatsMetrics(
                        os =
                            RuntimeK16OsMetrics(
                                pathLookups = 17,
                                inodeLoads = 19,
                                dirEntryScans = 23,
                                fileOpens = 29,
                                fileReads = 31,
                                statCalls = 37,
                                processSpawns = 41,
                                programLoads = 43,
                                dynamicImportLoads = 47,
                                libraryLoads = 53,
                                readDirCalls = 59,
                            ),
                        devices =
                            listOf(
                                RuntimeK16MmioDeviceMetrics(
                                    deviceId = 1,
                                    base = 0,
                                    size = 1,
                                    traffic = RuntimeK16BusTrafficMetrics(),
                                    storage = RuntimeK16StorageMetrics(readCommands = 5, bytesRead = 2048),
                                    gpu = RuntimeK16GpuMetrics(frames = 9, framePayloadBytes = 384),
                                ),
                            ),
                    ),
            )

        val line = formatK16RuntimePhase("ls:/bin.visible", elapsedNanos = 123, before = before, after = after)

        assertTrue(line.startsWith("k16Phase: name=ls:/bin.visible, elapsed=123 ns"))
        assertTrue(line.contains("slices=5"))
        assertTrue(line.contains("runTime=50 ns"))
        assertTrue(line.contains("waitSignals=1"))
        assertTrue(line.contains("inputBytes=5"))
        assertTrue(line.contains("gpuFrameBytes=80"))
        assertTrue(line.contains("displayFrames=5"))
        assertTrue(line.contains("displayBytes=256"))
        assertTrue(line.contains("storageReads=2"))
        assertTrue(line.contains("storageBytesRead=1024"))
        assertTrue(line.contains("pathLookups=7"))
        assertTrue(line.contains("inodeLoads=8"))
        assertTrue(line.contains("dirEntryScans=11"))
        assertTrue(line.contains("fileOpens=16"))
        assertTrue(line.contains("fileReads=17"))
        assertTrue(line.contains("statCalls=22"))
        assertTrue(line.contains("processSpawns=25"))
        assertTrue(line.contains("programLoads=26"))
        assertTrue(line.contains("dynamicImportLoads=29"))
        assertTrue(line.contains("libraryLoads=34"))
        assertTrue(line.contains("readDirCalls=39"))
    }

    @Test
    fun formatsK16CoreutilsCommandProfileWithPathPhaseDeltas() {
        val before =
            RuntimeProfilingSnapshot(
                k16 =
                    RuntimeK16StatsMetrics(
                        os =
                            RuntimeK16OsMetrics(
                                pathLookups = 1,
                                inodeLoads = 2,
                                dirEntryScans = 3,
                                fileOpens = 4,
                                fileReads = 5,
                                statCalls = 6,
                                processSpawns = 7,
                                programLoads = 8,
                                dynamicImportLoads = 9,
                                libraryLoads = 10,
                                readDirCalls = 11,
                            ),
                        devices =
                            listOf(
                                RuntimeK16MmioDeviceMetrics(
                                    deviceId = 1,
                                    base = 0,
                                    size = 1,
                                    traffic = RuntimeK16BusTrafficMetrics(),
                                    storage = RuntimeK16StorageMetrics(readCommands = 12, bytesRead = 6144),
                                    gpu = RuntimeK16GpuMetrics(),
                                ),
                            ),
                    ),
            )
        val after =
            RuntimeProfilingSnapshot(
                vm = RuntimeVmMetrics(k16RunSlices = 13, k16RunNanos = 1400),
                k16 =
                    RuntimeK16StatsMetrics(
                        os =
                            RuntimeK16OsMetrics(
                                pathLookups = 3,
                                inodeLoads = 5,
                                dirEntryScans = 7,
                                fileOpens = 11,
                                fileReads = 13,
                                statCalls = 17,
                                processSpawns = 19,
                                programLoads = 23,
                                dynamicImportLoads = 29,
                                libraryLoads = 31,
                                readDirCalls = 37,
                            ),
                        devices =
                            listOf(
                                RuntimeK16MmioDeviceMetrics(
                                    deviceId = 1,
                                    base = 0,
                                    size = 1,
                                    traffic = RuntimeK16BusTrafficMetrics(),
                                    storage = RuntimeK16StorageMetrics(readCommands = 41, bytesRead = 20992),
                                    gpu = RuntimeK16GpuMetrics(),
                                ),
                            ),
                    ),
            )

        val line = formatK16CoreutilsCommandProfile("ls", "ls /bin", ticks = 4, before = before, after = after)

        assertTrue(line.startsWith("k16CoreutilsCommand: name=ls, command=ls /bin, ticks=4"))
        assertTrue(line.contains("slices=13"))
        assertTrue(line.contains("runTime=1400 ns"))
        assertTrue(line.contains("storageReads=29"))
        assertTrue(line.contains("storageBytesRead=14848"))
        assertTrue(line.contains("pathLookups=2"))
        assertTrue(line.contains("inodeLoads=3"))
        assertTrue(line.contains("dirEntryScans=4"))
        assertTrue(line.contains("fileOpens=7"))
        assertTrue(line.contains("fileReads=8"))
        assertTrue(line.contains("statCalls=11"))
        assertTrue(line.contains("processSpawns=12"))
        assertTrue(line.contains("programLoads=15"))
        assertTrue(line.contains("dynamicImportLoads=20"))
        assertTrue(line.contains("libraryLoads=21"))
        assertTrue(line.contains("readDirCalls=26"))
    }

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
            val phases = K16RuntimePhaseProfiler(metrics)
            waitForTerminal(device, "initial shell prompt") { terminal -> terminal.contains("K16> ") }
            val bootPhase = phases.mark("boot.prompt")
            val typedCommand = "ticks\n"
            dispatchText(device, typedCommand)
            val typedInputPhase = phases.mark("ticks.input")
            waitForTerminal(device, "typed ticks output") { terminal -> terminal.contains("TICKS ") }
            val typedVisiblePhase = phases.mark("ticks.visible")
            repeat(2) { tickAndSync(device) }
            val typedIdlePhase = phases.mark("ticks.idle")
            val pastedCommand = "echo text-io-profile\n"
            dispatchPasteText(device, pastedCommand)
            val pastedInputPhase = phases.mark("echo:text-io-profile.input")
            waitForTerminal(device, "pasted echo output") { terminal -> terminal.contains("text-io-profile") }
            val pastedVisiblePhase = phases.mark("echo:text-io-profile.visible")

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
            assertTrue(bootPhase.contains("name=boot.prompt"))
            assertTrue(typedInputPhase.contains("name=ticks.input"))
            assertTrue(typedVisiblePhase.contains("name=ticks.visible"))
            assertTrue(typedIdlePhase.contains("name=ticks.idle"))
            assertTrue(pastedInputPhase.contains("name=echo:text-io-profile.input"))
            assertTrue(pastedVisiblePhase.contains("name=echo:text-io-profile.visible"))
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
            val phases = K16RuntimePhaseProfiler(metrics)
            val before = metrics.snapshot()
            val command = "ls /bin\n"
            val startedAt = System.nanoTime()
            DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap(command.encodeToByteArray())))
            val inputQueuedNanos = System.nanoTime() - startedAt
            val inputPhase = phases.mark("ls:/bin.input")
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

            val visiblePhase = phases.mark("ls:/bin.visible")
            repeat(2) { tickAndSync(device) }
            val idlePhase = phases.mark("ls:/bin.idle")
            val after = metrics.snapshot()
            val gpuBefore = before.k16.gpu
            val gpuAfter = after.k16.gpu
            val storageBefore = before.k16.storage0
            val storageAfter = after.k16.storage0
            val storageReads = storageAfter.readCommands - storageBefore.readCommands
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
                "k16LsCommandStorage: reads=$storageReads, " +
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
            assertTrue(storageReads < 60, "ls /bin should reuse cached storage0 backend blocks")
            assertTrue(inputPhase.contains("name=ls:/bin.input"))
            assertTrue(visiblePhase.contains("storageReads="))
            assertTrue(visiblePhase.contains("pathLookups="))
            assertTrue(visiblePhase.contains("dirEntryScans="))
            assertTrue(visiblePhase.contains("statCalls="))
            assertTrue(idlePhase.contains("name=ls:/bin.idle"))
        } finally {
            device.close()
        }
    }

    @Test
    fun printsK16LsCommandRuntimeLatencyNearTerminalScroll() {
        val workspace = createTempDirectory("k16-runtime-ls-scroll-profile-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        val profile = DeviceProfileRegistry.forFamily(DeviceFamily.NORMAL)
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 227,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "ls-scroll-profiling"),
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
            fillTerminalUntilPromptIsNearBottom(device)
            val phases = K16RuntimePhaseProfiler(metrics)
            val before = metrics.snapshot()
            val command = "ls /bin\n"
            val startedAt = System.nanoTime()
            DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap(command.encodeToByteArray())))
            val inputQueuedNanos = System.nanoTime() - startedAt
            val inputPhase = phases.mark("ls:/bin:scroll.input")
            var ticks = 0
            var visibleNanos: Long? = null

            while (ticks < 200 && visibleNanos == null) {
                ticks += 1
                tickAndSync(device)
                val elapsed = System.nanoTime() - startedAt
                val terminal = device.snapshotRuntimeState()?.let(::terminalText) ?: ""
                val outputIndex = terminal.indexOf("ls.kx")
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

            val visiblePhase = phases.mark("ls:/bin:scroll.visible")
            repeat(2) { tickAndSync(device) }
            val idlePhase = phases.mark("ls:/bin:scroll.idle")
            val after = metrics.snapshot()
            val gpuBefore = before.k16.gpu
            val gpuAfter = after.k16.gpu
            val storageBefore = before.k16.storage0
            val storageAfter = after.k16.storage0
            val storageReads = storageAfter.readCommands - storageBefore.readCommands
            val scrollFrameBytes = gpuAfter.framePayloadBytes - gpuBefore.framePayloadBytes
            println(
                "k16LsScrollCommand: command=ls /bin, inputQueued=${inputQueuedNanos} ns, " +
                    "visible=${visibleNanos ?: -1} ns, ticks=$ticks",
            )
            println(
                "k16LsScrollCommandVm: slices=${after.vm.k16RunSlices - before.vm.k16RunSlices}, " +
                    "runTime=${after.vm.k16RunNanos - before.vm.k16RunNanos} ns, " +
                    "yieldSignals=${after.vm.k16RunYieldSignals - before.vm.k16RunYieldSignals}, " +
                    "waitSignals=${after.vm.k16RunWaitSignals - before.vm.k16RunWaitSignals}, " +
                    "pauseSignals=${after.vm.k16RunPauseSignals - before.vm.k16RunPauseSignals}, " +
                    "inputWakeups=${after.vm.k16WaitInputWakeups - before.vm.k16WaitInputWakeups}",
            )
            println(
                "k16LsScrollCommandStorage: reads=$storageReads, " +
                    "writes=${storageAfter.writeCommands - storageBefore.writeCommands}, " +
                    "flushes=${storageAfter.flushCommands - storageBefore.flushCommands}, " +
                    "bytesRead=${storageAfter.bytesRead - storageBefore.bytesRead}, " +
                    "bytesWritten=${storageAfter.bytesWritten - storageBefore.bytesWritten}",
            )
            println(
                "k16LsScrollCommandGpu: blits=${gpuAfter.blitBufferCommands - gpuBefore.blitBufferCommands}, " +
                    "presents=${gpuAfter.presentCommands - gpuBefore.presentCommands}, " +
                    "frames=${gpuAfter.frames - gpuBefore.frames}, " +
                    "tiles=${gpuAfter.frameTiles - gpuBefore.frameTiles}, " +
                    "frameBytes=$scrollFrameBytes",
            )

            assertTrue(visibleNanos != null, "scroll-positioned ls /bin did not finish and return to the prompt")
            assertTrue(
                scrollFrameBytes < 100_000,
                "scroll-positioned ls /bin should use display ops instead of serializing full-screen tile payloads",
            )
            assertTrue(storageReads < 60, "scroll-positioned ls /bin should reuse cached storage0 backend blocks")
            assertTrue(inputPhase.contains("name=ls:/bin:scroll.input"))
            assertTrue(visiblePhase.contains("storageReads="))
            assertTrue(visiblePhase.contains("pathLookups="))
            assertTrue(visiblePhase.contains("dirEntryScans="))
            assertTrue(visiblePhase.contains("statCalls="))
            assertTrue(idlePhase.contains("name=ls:/bin:scroll.idle"))
        } finally {
            device.close()
        }
    }

    @Test
    fun printsK16CoreutilsCommandRuntimeProfile() {
        val workspace = createTempDirectory("k16-runtime-coreutils-profile-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        val profile = DeviceProfileRegistry.forFamily(DeviceFamily.NORMAL)
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 228,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "coreutils-profiling"),
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

        val commands =
            listOf(
                ProfiledCoreutilsCommand("uname", "uname", "K16"),
                ProfiledCoreutilsCommand("stat", "stat /bin/ls.kx", "FILE "),
                ProfiledCoreutilsCommand("ls", "ls /bin", "ls.kx"),
                ProfiledCoreutilsCommand("write", "write /profile.txt hello", "WROTE 5 /profile.txt"),
                ProfiledCoreutilsCommand("cat", "cat /profile.txt", "hello"),
                ProfiledCoreutilsCommand("cp", "cp /profile.txt /profile-copy.txt", "COPIED /profile.txt /profile-copy.txt"),
                ProfiledCoreutilsCommand("mv", "mv /profile-copy.txt /profile-moved.txt", "MOVED /profile-copy.txt /profile-moved.txt"),
                ProfiledCoreutilsCommand("mkdir", "mkdir /profile-dir", "CREATED /profile-dir"),
                ProfiledCoreutilsCommand("rmdir", "rmdir /profile-dir", "REMOVED /profile-dir"),
                ProfiledCoreutilsCommand("rm", "rm /profile-moved.txt", "REMOVED /profile-moved.txt"),
            )

        try {
            device.turnOn()
            waitForTerminal(device, "initial shell prompt") { terminal -> terminal.contains("K16> ") }
            val lines =
                commands.map { command ->
                    runProfiledCoreutilsCommand(device, metrics, command)
                }

            assertTrue(lines.any { it.contains("name=ls") && it.contains("readDirCalls=1") })
            assertTrue(lines.any { it.contains("name=stat") && it.contains("statCalls=1") })
            assertTrue(lines.any { it.contains("name=mv") && it.contains("statCalls=1") })
            assertTrue(lines.any { it.contains("name=cat") && it.contains("fileReads=") })
            assertTrue(lines.all { it.contains("processSpawns=1") })
            assertTrue(lines.all { it.contains("programLoads=1") })
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

    private fun fillTerminalUntilPromptIsNearBottom(device: K16RuntimeDevice) {
        repeat(K16_TERMINAL_ROWS + 3) { index ->
            val line = index.toString().padStart(2, '0')
            dispatchPasteText(device, "echo scroll-line-$line\n")
            waitForTerminal(device, "scroll filler line $line") { terminal -> terminal.contains("scroll-line-$line") }
        }
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

    private fun runProfiledCoreutilsCommand(
        device: K16RuntimeDevice,
        metrics: RecordingRuntimeMetricsCollector,
        command: ProfiledCoreutilsCommand,
    ): String {
        val before = metrics.snapshot()
        val startedAt = System.nanoTime()
        DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap("${command.command}\n".encodeToByteArray())))
        var ticks = 0
        var visible = false
        while (ticks < 240 && !visible) {
            ticks += 1
            tickAndSync(device)
            val terminal = device.snapshotRuntimeState()?.let(::terminalText) ?: ""
            visible = terminalContainsCommandResult(terminal, command.command, command.expectedText)
            Thread.sleep(1)
        }
        assertTrue(visible, "coreutils command did not finish: ${command.command}")
        val after = metrics.snapshot()
        val line = formatK16CoreutilsCommandProfile(command.name, command.command, ticks, before, after)
        println(line)
        return line
    }

    private fun terminalContainsCommandResult(
        terminal: String,
        command: String,
        expectedText: String,
    ): Boolean {
        val commandIndex = terminal.lastIndexOf("K16> $command")
        if (commandIndex < 0) {
            return false
        }
        val outputIndex = terminal.indexOf(expectedText, startIndex = commandIndex + command.length)
        if (outputIndex < 0) {
            return false
        }
        val promptIndex = terminal.indexOf("K16> ", startIndex = outputIndex + expectedText.length)
        return promptIndex > outputIndex
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

private data class ProfiledCoreutilsCommand(
    val name: String,
    val command: String,
    val expectedText: String,
)

private class K16RuntimePhaseProfiler(
    private val metrics: RecordingRuntimeMetricsCollector,
) {
    private var phaseStartedAt = System.nanoTime()
    private var snapshot = metrics.snapshot()

    fun mark(name: String): String {
        val now = System.nanoTime()
        val after = metrics.snapshot()
        val line = formatK16RuntimePhase(name, now - phaseStartedAt, snapshot, after)
        println(line)
        phaseStartedAt = now
        snapshot = after
        return line
    }
}

private fun formatK16RuntimePhase(
    name: String,
    elapsedNanos: Long,
    before: RuntimeProfilingSnapshot,
    after: RuntimeProfilingSnapshot,
): String {
    val vmBefore = before.vm
    val vmAfter = after.vm
    val storageBefore = before.k16.storage0
    val storageAfter = after.k16.storage0
    val gpuBefore = before.k16.gpu
    val gpuAfter = after.k16.gpu
    val osBefore = before.k16.os
    val osAfter = after.k16.os
    return "k16Phase: name=$name, elapsed=$elapsedNanos ns, " +
        "slices=${vmAfter.k16RunSlices - vmBefore.k16RunSlices}, " +
        "runTime=${vmAfter.k16RunNanos - vmBefore.k16RunNanos} ns, " +
        "yieldSignals=${vmAfter.k16RunYieldSignals - vmBefore.k16RunYieldSignals}, " +
        "waitSignals=${vmAfter.k16RunWaitSignals - vmBefore.k16RunWaitSignals}, " +
        "pauseSignals=${vmAfter.k16RunPauseSignals - vmBefore.k16RunPauseSignals}, " +
        "inputWakeups=${vmAfter.k16WaitInputWakeups - vmBefore.k16WaitInputWakeups}, " +
        "inputBytes=${vmAfter.k16TextInputBytes - vmBefore.k16TextInputBytes}, " +
        "gpuFrameBatches=${vmAfter.k16GpuFrameBatches - vmBefore.k16GpuFrameBatches}, " +
        "gpuFrameBytes=${vmAfter.k16GpuFrameBytes - vmBefore.k16GpuFrameBytes}, " +
        "displayFrames=${gpuAfter.frames - gpuBefore.frames}, " +
        "displayTiles=${gpuAfter.frameTiles - gpuBefore.frameTiles}, " +
        "displayBytes=${gpuAfter.framePayloadBytes - gpuBefore.framePayloadBytes}, " +
        "storageReads=${storageAfter.readCommands - storageBefore.readCommands}, " +
        "storageWrites=${storageAfter.writeCommands - storageBefore.writeCommands}, " +
        "storageFlushes=${storageAfter.flushCommands - storageBefore.flushCommands}, " +
        "storageBytesRead=${storageAfter.bytesRead - storageBefore.bytesRead}, " +
        "storageBytesWritten=${storageAfter.bytesWritten - storageBefore.bytesWritten}, " +
        "pathLookups=${osAfter.pathLookups - osBefore.pathLookups}, " +
        "inodeLoads=${osAfter.inodeLoads - osBefore.inodeLoads}, " +
        "dirEntryScans=${osAfter.dirEntryScans - osBefore.dirEntryScans}, " +
        "fileOpens=${osAfter.fileOpens - osBefore.fileOpens}, " +
        "fileReads=${osAfter.fileReads - osBefore.fileReads}, " +
        "statCalls=${osAfter.statCalls - osBefore.statCalls}, " +
        "processSpawns=${osAfter.processSpawns - osBefore.processSpawns}, " +
        "programLoads=${osAfter.programLoads - osBefore.programLoads}, " +
        "dynamicImportLoads=${osAfter.dynamicImportLoads - osBefore.dynamicImportLoads}, " +
        "libraryLoads=${osAfter.libraryLoads - osBefore.libraryLoads}, " +
        "readDirCalls=${osAfter.readDirCalls - osBefore.readDirCalls}"
}

private fun formatK16CoreutilsCommandProfile(
    name: String,
    command: String,
    ticks: Int,
    before: RuntimeProfilingSnapshot,
    after: RuntimeProfilingSnapshot,
): String {
    val vmBefore = before.vm
    val vmAfter = after.vm
    val storageBefore = before.k16.storage0
    val storageAfter = after.k16.storage0
    val osBefore = before.k16.os
    val osAfter = after.k16.os
    return "k16CoreutilsCommand: name=$name, command=$command, ticks=$ticks, " +
        "slices=${vmAfter.k16RunSlices - vmBefore.k16RunSlices}, " +
        "runTime=${vmAfter.k16RunNanos - vmBefore.k16RunNanos} ns, " +
        "storageReads=${storageAfter.readCommands - storageBefore.readCommands}, " +
        "storageBytesRead=${storageAfter.bytesRead - storageBefore.bytesRead}, " +
        "pathLookups=${osAfter.pathLookups - osBefore.pathLookups}, " +
        "inodeLoads=${osAfter.inodeLoads - osBefore.inodeLoads}, " +
        "dirEntryScans=${osAfter.dirEntryScans - osBefore.dirEntryScans}, " +
        "fileOpens=${osAfter.fileOpens - osBefore.fileOpens}, " +
        "fileReads=${osAfter.fileReads - osBefore.fileReads}, " +
        "statCalls=${osAfter.statCalls - osBefore.statCalls}, " +
        "processSpawns=${osAfter.processSpawns - osBefore.processSpawns}, " +
        "programLoads=${osAfter.programLoads - osBefore.programLoads}, " +
        "dynamicImportLoads=${osAfter.dynamicImportLoads - osBefore.dynamicImportLoads}, " +
        "libraryLoads=${osAfter.libraryLoads - osBefore.libraryLoads}, " +
        "readDirCalls=${osAfter.readDirCalls - osBefore.readDirCalls}"
}

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

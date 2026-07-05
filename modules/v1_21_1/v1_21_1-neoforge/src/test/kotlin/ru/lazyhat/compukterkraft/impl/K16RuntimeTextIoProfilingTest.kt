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
                                programLoadBytes = 21,
                                dynamicImportBytes = 22,
                                libraryLoadBytes = 23,
                                genericFileDataReadBlocks = 24,
                                genericFileDataReadBytes = 25,
                                readDirDataReadBlocks = 26,
                                readDirDataReadBytes = 27,
                                programDataReadBlocks = 28,
                                programDataReadBytes = 29,
                                dynamicImportDataReadBlocks = 30,
                                dynamicImportDataReadBytes = 31,
                                libraryDataReadBlocks = 32,
                                libraryDataReadBytes = 33,
                                initProgramFileDataReadBlocks = 34,
                                initProgramFileDataReadBytes = 35,
                                shellProgramFileDataReadBlocks = 36,
                                shellProgramFileDataReadBytes = 37,
                                otherProgramFileDataReadBlocks = 38,
                                otherProgramFileDataReadBytes = 39,
                                libkraftLibraryFileDataReadBlocks = 40,
                                libkraftLibraryFileDataReadBytes = 41,
                                otherLibraryFileDataReadBlocks = 42,
                                otherLibraryFileDataReadBytes = 43,
                            ),
                        devices =
                            listOf(
                                RuntimeK16MmioDeviceMetrics(
                                    deviceId = 1,
                                    base = 0,
                                    size = 1,
                                    traffic = RuntimeK16BusTrafficMetrics(),
                                    storage =
                                        RuntimeK16StorageMetrics(
                                            readCommands = 3,
                                            bytesRead = 1024,
                                            uniqueReadBlocks = 4,
                                            repeatedReadBlocks = 1,
                                            partitionTableReadBlocks = 1,
                                            bootMetadataReadBlocks = 2,
                                            bootDataReadBlocks = 3,
                                            rootMetadataReadBlocks = 4,
                                            rootDataReadBlocks = 5,
                                            unknownReadBlocks = 6,
                                            requestedReadBlocks = 7,
                                            requestedReadBytes = 3584,
                                        ),
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
                                programLoadBytes = 61,
                                dynamicImportBytes = 67,
                                libraryLoadBytes = 71,
                                genericFileDataReadBlocks = 73,
                                genericFileDataReadBytes = 79,
                                readDirDataReadBlocks = 83,
                                readDirDataReadBytes = 89,
                                programDataReadBlocks = 97,
                                programDataReadBytes = 101,
                                dynamicImportDataReadBlocks = 103,
                                dynamicImportDataReadBytes = 107,
                                libraryDataReadBlocks = 109,
                                libraryDataReadBytes = 113,
                                initProgramFileDataReadBlocks = 127,
                                initProgramFileDataReadBytes = 131,
                                shellProgramFileDataReadBlocks = 137,
                                shellProgramFileDataReadBytes = 139,
                                otherProgramFileDataReadBlocks = 149,
                                otherProgramFileDataReadBytes = 151,
                                libkraftLibraryFileDataReadBlocks = 157,
                                libkraftLibraryFileDataReadBytes = 163,
                                otherLibraryFileDataReadBlocks = 167,
                                otherLibraryFileDataReadBytes = 173,
                            ),
                        devices =
                            listOf(
                                RuntimeK16MmioDeviceMetrics(
                                    deviceId = 1,
                                    base = 0,
                                    size = 1,
                                    traffic = RuntimeK16BusTrafficMetrics(),
                                    storage =
                                        RuntimeK16StorageMetrics(
                                            readCommands = 5,
                                            bytesRead = 2048,
                                            uniqueReadBlocks = 9,
                                            repeatedReadBlocks = 3,
                                            partitionTableReadBlocks = 2,
                                            bootMetadataReadBlocks = 4,
                                            bootDataReadBlocks = 6,
                                            rootMetadataReadBlocks = 8,
                                            rootDataReadBlocks = 10,
                                            unknownReadBlocks = 12,
                                            requestedReadBlocks = 17,
                                            requestedReadBytes = 8704,
                                        ),
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
        assertTrue(line.contains("storageReadCommands=2"))
        assertTrue(line.contains("storageRequestedReadBlocks=10"))
        assertTrue(line.contains("storageRequestedReadBytes=5120"))
        assertTrue(line.contains("storageMediaReadBlocks="))
        assertTrue(line.contains("storageUniqueReadBlocks=5"))
        assertTrue(line.contains("storageRepeatedReadBlocks=2"))
        assertTrue(line.contains("storagePartitionTableReadBlocks=1"))
        assertTrue(line.contains("storageBootMetadataReadBlocks=2"))
        assertTrue(line.contains("storageBootDataReadBlocks=3"))
        assertTrue(line.contains("storageRootMetadataReadBlocks=4"))
        assertTrue(line.contains("storageRootDataReadBlocks=5"))
        assertTrue(line.contains("storageUnknownReadBlocks=6"))
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
        assertTrue(line.contains("programLoadBytes=40"))
        assertTrue(line.contains("dynamicImportBytes=45"))
        assertTrue(line.contains("libraryLoadBytes=48"))
        assertTrue(line.contains("genericFileDataReadBlocks=49"))
        assertTrue(line.contains("genericFileDataReadBytes=54"))
        assertTrue(line.contains("readDirDataReadBlocks=57"))
        assertTrue(line.contains("readDirDataReadBytes=62"))
        assertTrue(line.contains("programDataReadBlocks=69"))
        assertTrue(line.contains("programDataReadBytes=72"))
        assertTrue(line.contains("dynamicImportDataReadBlocks=73"))
        assertTrue(line.contains("dynamicImportDataReadBytes=76"))
        assertTrue(line.contains("libraryDataReadBlocks=77"))
        assertTrue(line.contains("libraryDataReadBytes=80"))
        assertTrue(line.contains("initProgramFileDataReadBlocks=93"))
        assertTrue(line.contains("initProgramFileDataReadBytes=96"))
        assertTrue(line.contains("shellProgramFileDataReadBlocks=101"))
        assertTrue(line.contains("shellProgramFileDataReadBytes=102"))
        assertTrue(line.contains("otherProgramFileDataReadBlocks=111"))
        assertTrue(line.contains("otherProgramFileDataReadBytes=112"))
        assertTrue(line.contains("libkraftLibraryFileDataReadBlocks=117"))
        assertTrue(line.contains("libkraftLibraryFileDataReadBytes=122"))
        assertTrue(line.contains("otherLibraryFileDataReadBlocks=125"))
        assertTrue(line.contains("otherLibraryFileDataReadBytes=130"))
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
                                genericFileDataReadBlocks = 12,
                                genericFileDataReadBytes = 13,
                                readDirDataReadBlocks = 14,
                                readDirDataReadBytes = 15,
                                programDataReadBlocks = 16,
                                programDataReadBytes = 17,
                                dynamicImportDataReadBlocks = 18,
                                dynamicImportDataReadBytes = 19,
                                libraryDataReadBlocks = 20,
                                libraryDataReadBytes = 21,
                            ),
                        devices =
                            listOf(
                                RuntimeK16MmioDeviceMetrics(
                                    deviceId = 1,
                                    base = 0,
                                    size = 1,
                                    traffic = RuntimeK16BusTrafficMetrics(),
                                    storage =
                                        RuntimeK16StorageMetrics(
                                            readCommands = 12,
                                            bytesRead = 6144,
                                            uniqueReadBlocks = 10,
                                            repeatedReadBlocks = 2,
                                            partitionTableReadBlocks = 2,
                                            bootMetadataReadBlocks = 4,
                                            bootDataReadBlocks = 6,
                                            rootMetadataReadBlocks = 8,
                                            rootDataReadBlocks = 10,
                                            unknownReadBlocks = 12,
                                            requestedReadBlocks = 14,
                                            requestedReadBytes = 7168,
                                        ),
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
                                genericFileDataReadBlocks = 41,
                                genericFileDataReadBytes = 43,
                                readDirDataReadBlocks = 47,
                                readDirDataReadBytes = 53,
                                programDataReadBlocks = 59,
                                programDataReadBytes = 61,
                                dynamicImportDataReadBlocks = 67,
                                dynamicImportDataReadBytes = 71,
                                libraryDataReadBlocks = 73,
                                libraryDataReadBytes = 79,
                            ),
                        devices =
                            listOf(
                                RuntimeK16MmioDeviceMetrics(
                                    deviceId = 1,
                                    base = 0,
                                    size = 1,
                                    traffic = RuntimeK16BusTrafficMetrics(),
                                    storage =
                                        RuntimeK16StorageMetrics(
                                            readCommands = 41,
                                            bytesRead = 20992,
                                            uniqueReadBlocks = 34,
                                            repeatedReadBlocks = 7,
                                            partitionTableReadBlocks = 5,
                                            bootMetadataReadBlocks = 9,
                                            bootDataReadBlocks = 13,
                                            rootMetadataReadBlocks = 17,
                                            rootDataReadBlocks = 21,
                                            unknownReadBlocks = 25,
                                            requestedReadBlocks = 45,
                                            requestedReadBytes = 23040,
                                        ),
                                    gpu = RuntimeK16GpuMetrics(),
                                ),
                            ),
                    ),
            )

        val line = formatK16CoreutilsCommandProfile("ls", "ls /bin", ticks = 4, before = before, after = after)

        assertTrue(line.startsWith("k16CoreutilsCommand: name=ls, command=ls /bin, ticks=4"))
        assertTrue(line.contains("slices=13"))
        assertTrue(line.contains("runTime=1400 ns"))
        assertTrue(line.contains("storageReadCommands=29"))
        assertTrue(line.contains("storageRequestedReadBlocks=31"))
        assertTrue(line.contains("storageRequestedReadBytes=15872"))
        assertTrue(line.contains("storageMediaReadBlocks="))
        assertTrue(line.contains("storageUniqueReadBlocks=24"))
        assertTrue(line.contains("storageRepeatedReadBlocks=5"))
        assertTrue(line.contains("storagePartitionTableReadBlocks=3"))
        assertTrue(line.contains("storageBootMetadataReadBlocks=5"))
        assertTrue(line.contains("storageBootDataReadBlocks=7"))
        assertTrue(line.contains("storageRootMetadataReadBlocks=9"))
        assertTrue(line.contains("storageRootDataReadBlocks=11"))
        assertTrue(line.contains("storageUnknownReadBlocks=13"))
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
        assertTrue(line.contains("genericFileDataReadBlocks=29"))
        assertTrue(line.contains("genericFileDataReadBytes=30"))
        assertTrue(line.contains("readDirDataReadBlocks=33"))
        assertTrue(line.contains("readDirDataReadBytes=38"))
        assertTrue(line.contains("programDataReadBlocks=43"))
        assertTrue(line.contains("programDataReadBytes=44"))
        assertTrue(line.contains("dynamicImportDataReadBlocks=49"))
        assertTrue(line.contains("dynamicImportDataReadBytes=52"))
        assertTrue(line.contains("libraryDataReadBlocks=53"))
        assertTrue(line.contains("libraryDataReadBytes=58"))
    }

    @Test
    fun formatsKfsHotspotSummarySortedByMetadataDataTransfersAndMediaBlocks() {
        val baseline = RuntimeProfilingSnapshot()
        val statAfter =
            RuntimeProfilingSnapshot(
                k16 =
                    RuntimeK16StatsMetrics(
                        os =
                            RuntimeK16OsMetrics(
                                pathLookups = 3,
                                inodeLoads = 5,
                                dirEntryScans = 8,
                                statCalls = 2,
                                genericFileDataReadBlocks = 1,
                            ),
                        devices =
                            listOf(
                                RuntimeK16MmioDeviceMetrics(
                                    deviceId = 1,
                                    base = 0,
                                    size = 1,
                                    traffic = RuntimeK16BusTrafficMetrics(),
                                    storage = RuntimeK16StorageMetrics(readCommands = 9, mediaReadBlocks = 3),
                                ),
                            ),
                    ),
            )
        val lsAfter =
            RuntimeProfilingSnapshot(
                k16 =
                    RuntimeK16StatsMetrics(
                        os =
                            RuntimeK16OsMetrics(
                                pathLookups = 6,
                                inodeLoads = 9,
                                dirEntryScans = 28,
                                statCalls = 1,
                                readDirDataReadBlocks = 7,
                            ),
                        devices =
                            listOf(
                                RuntimeK16MmioDeviceMetrics(
                                    deviceId = 1,
                                    base = 0,
                                    size = 1,
                                    traffic = RuntimeK16BusTrafficMetrics(),
                                    storage = RuntimeK16StorageMetrics(readCommands = 36, mediaReadBlocks = 14),
                                ),
                            ),
                    ),
            )
        val catAfter =
            RuntimeProfilingSnapshot(
                k16 =
                    RuntimeK16StatsMetrics(
                        os =
                            RuntimeK16OsMetrics(
                                pathLookups = 2,
                                inodeLoads = 3,
                                fileReads = 5,
                                genericFileDataReadBlocks = 18,
                            ),
                        devices =
                            listOf(
                                RuntimeK16MmioDeviceMetrics(
                                    deviceId = 1,
                                    base = 0,
                                    size = 1,
                                    traffic = RuntimeK16BusTrafficMetrics(),
                                    storage = RuntimeK16StorageMetrics(readCommands = 12, mediaReadBlocks = 20),
                                ),
                            ),
                    ),
            )

        val line =
            formatKfsHotspotSummary(
                listOf(
                    K16ProfiledCommandSample("stat", baseline, statAfter),
                    K16ProfiledCommandSample("ls", baseline, lsAfter),
                    K16ProfiledCommandSample("cat", baseline, catAfter),
                ),
            )

        assertTrue(line.startsWith("k16FsHotspots: "))
        assertTrue(line.contains("metadataOps=[ls:44, stat:18, cat:5]"), line)
        assertTrue(line.contains("dataReadBlocks=[cat:18, ls:7, stat:1]"), line)
        assertTrue(line.contains("readCommands=[ls:36, cat:12, stat:9]"), line)
        assertTrue(line.contains("mediaReadBlocks=[cat:20, ls:14, stat:3]"), line)
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
            tickAndSync(device)
            val splashVisiblePhase = phases.mark("bios.splash.visible")
            repeat(K16_BIOS_SPLASH_WAIT_PROFILE_TICKS) { tickAndSync(device) }
            val splashWaitPhase = phases.mark("bios.splash.wait")
            waitForTerminal(device, "initial shell prompt") { terminal -> terminal.contains("K16> ") }
            val promptVisiblePhase = phases.mark("shell.prompt.after_splash_to_prompt_visible")
            val promptInputReadyPhase = phases.mark("shell.prompt.prompt_visible_to_input_ready")
            val promptAfterSplashPhase = promptVisiblePhase
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
            assertTrue(splashVisiblePhase.contains("name=bios.splash.visible"))
            assertTrue(splashWaitPhase.contains("name=bios.splash.wait"))
            assertTrue(promptAfterSplashPhase.contains("name=shell.prompt.after_splash_to_prompt_visible"))
            assertTrue(promptInputReadyPhase.contains("name=shell.prompt.prompt_visible_to_input_ready"))
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
            val storageReadCommands = storageAfter.readCommands - storageBefore.readCommands
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
                "k16LsCommandStorage: readCommands=$storageReadCommands, " +
                    "writeCommands=${storageAfter.writeCommands - storageBefore.writeCommands}, " +
                    "mediaReadBlocks=${storageAfter.mediaReadBlocks - storageBefore.mediaReadBlocks}, " +
                    "mediaWriteBlocks=${storageAfter.mediaWriteBlocks - storageBefore.mediaWriteBlocks}, " +
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
            assertTrue(storageReadCommands < 60, "ls /bin should keep storage0 transfer commands bounded")
            assertTrue(inputPhase.contains("name=ls:/bin.input"))
            assertTrue(visiblePhase.contains("storageReadCommands="))
            assertTrue(visiblePhase.contains("storageMediaReadBlocks="))
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
            val storageReadCommands = storageAfter.readCommands - storageBefore.readCommands
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
                "k16LsScrollCommandStorage: readCommands=$storageReadCommands, " +
                    "writeCommands=${storageAfter.writeCommands - storageBefore.writeCommands}, " +
                    "mediaReadBlocks=${storageAfter.mediaReadBlocks - storageBefore.mediaReadBlocks}, " +
                    "mediaWriteBlocks=${storageAfter.mediaWriteBlocks - storageBefore.mediaWriteBlocks}, " +
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
            assertTrue(storageReadCommands < 60, "scroll-positioned ls /bin should keep storage0 transfer commands bounded")
            assertTrue(inputPhase.contains("name=ls:/bin:scroll.input"))
            assertTrue(visiblePhase.contains("storageReadCommands="))
            assertTrue(visiblePhase.contains("storageMediaReadBlocks="))
            assertTrue(visiblePhase.contains("pathLookups="))
            assertTrue(visiblePhase.contains("dirEntryScans="))
            assertTrue(visiblePhase.contains("statCalls="))
            assertTrue(idlePhase.contains("name=ls:/bin:scroll.idle"))
        } finally {
            device.close()
        }
    }

    @Test
    fun printsK16YesMassTextOutputRuntimeLatency() {
        val workspace = createTempDirectory("k16-runtime-yes-profile-")
        val biosFlashPath = workspace.resolve("bios.kflash")
        val storage0Path = workspace.resolve("storage0.kv")
        biosFlashPath.writeBytes(K16BiosFlashWorkspace.loadBiosFlashResource(classLoader = javaClass.classLoader))
        storage0Path.writeBytes(K16SystemVolumeWorkspace.loadStorage0VolumeResource(classLoader = javaClass.classLoader))
        val profile = DeviceProfileRegistry.forFamily(DeviceFamily.NORMAL)
        val metrics = RecordingRuntimeMetricsCollector()
        val device =
            K16RuntimeDevice(
                deviceId = 229,
                properties = DeviceProperties(DeviceFamily.NORMAL, label = "yes-profiling"),
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
            val samples =
                listOf(
                    ProfiledYesLineWidth(width = 1, payload = "A"),
                    ProfiledYesLineWidth(width = 8, payload = "B".repeat(8)),
                    ProfiledYesLineWidth(width = 32, payload = "C".repeat(32)),
                    ProfiledYesLineWidth(width = 64, payload = "D".repeat(64)),
                )

            samples.forEach { sample ->
                val result =
                    runProfiledYesLineWidthCommand(
                        device = device,
                        metrics = metrics,
                        sample = sample,
                        lines = 128,
                    )
                println(
                    "k16YesLineWidth: chars=${sample.width}, lines=${result.lines}, " +
                        "bytesPerLine=${sample.width + 1}, scroll=immediate, command=${result.command}, " +
                        "inputQueued=${result.inputQueuedNanos} ns, visible=${result.visibleNanos} ns, " +
                        "ticks=${result.ticks}, slices=${result.slices}, runTime=${result.runNanos} ns, " +
                        "yieldSignals=${result.yieldSignals}, waitSignals=${result.waitSignals}, " +
                        "pauseSignals=${result.pauseSignals}, inputWakeups=${result.inputWakeups}, " +
                        "blits=${result.blitCommands}, presents=${result.presentCommands}, " +
                        "frames=${result.frames}, tiles=${result.frameTiles}, frameBytes=${result.frameBytes}",
                )
                assertTrue(result.blitCommands > 0, "yes ${sample.width}-char lines should exercise terminal GPU blits")
                assertTrue(
                    result.presentCommands > 0,
                    "yes ${sample.width}-char lines should exercise terminal GPU presents",
                )
                assertTrue(
                    result.blitCommands <= result.lines * 4L,
                    "yes ${sample.width}-char lines should batch terminal glyph blits by printable runs; " +
                        "blits=${result.blitCommands}, lines=${result.lines}",
                )
            }
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
                ProfiledCoreutilsCommand("uname-warm", "uname", "K16"),
                ProfiledCoreutilsCommand("stat", "stat /bin/ls.kx", "FILE "),
                ProfiledCoreutilsCommand("ls", "ls /bin", "ls.kx"),
                ProfiledCoreutilsCommand("ls-warm", "ls /bin", "ls.kx"),
                ProfiledCoreutilsCommand("motd", "cat /etc/motd", "K16 FS OK"),
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
            val samples = mutableListOf<K16ProfiledCommandSample>()
            val lines =
                commands.map { command ->
                    runProfiledCoreutilsCommand(device, metrics, command, samples)
                }
            val hotspotSummary = formatKfsHotspotSummary(samples)
            println(hotspotSummary)

            val unameLine = lines.singleCommandLine("uname")
            assertTrue(
                metricValue(unameLine, "storageMediaReadBlocks") <= 9,
                "uname should not reopen the dynamic executable after reading imports: $unameLine",
            )
            val lsLine = lines.singleCommandLine("ls")
            assertTrue(lsLine.contains("readDirCalls=1"))
            assertTrue(
                metricValue(lsLine, "inodeLoads") <= 22,
                "ls /bin should reuse cached inode metadata while listing directory entries: $lsLine",
            )
            val writeLine = lines.singleCommandLine("write")
            assertTrue(
                metricValue(writeLine, "storageReadCommands") <= 80,
                "write should not scan KFS allocation bitmap through hundreds of storage transfers: $writeLine",
            )
            assertTrue(lines.any { it.contains("name=stat") && it.contains("statCalls=1") })
            assertTrue(lines.any { it.contains("name=mv") && it.contains("statCalls=1") })
            assertTrue(lines.any { it.contains("name=cat") && it.contains("fileReads=") })
            assertTrue(lines.any { it.contains("name=motd") && it.contains("fileReads=") })
            assertTrue(lines.any { it.contains("name=ls-warm") && it.contains("readDirCalls=1") })
            assertTrue(lines.any { it.contains("name=uname-warm") && it.contains("processSpawns=1") })
            assertTrue(lines.all { it.contains("processSpawns=1") })
            assertTrue(lines.all { it.contains("programLoads=1") })
            assertTrue(hotspotSummary.contains("k16FsHotspots: "))
            assertTrue(hotspotSummary.contains("metadataOps=["))
            assertTrue(hotspotSummary.contains("dataReadBlocks=["))
            assertTrue(hotspotSummary.contains("readCommands=["))
            assertTrue(hotspotSummary.contains("mediaReadBlocks=["))
            assertTrue(hotspotSummary.contains("pathLookups=["))
            assertTrue(hotspotSummary.contains("dirEntryScans=["))
            assertTrue(hotspotSummary.contains("fileReads=["))
            assertTrue(hotspotSummary.contains("readDirDataReadBlocks=["))
            assertTrue(hotspotSummary.contains("programDataReadBlocks=["))
            assertTrue(hotspotSummary.contains("storageWriteCommands=["))
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
        samples: MutableList<K16ProfiledCommandSample>,
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
        samples += K16ProfiledCommandSample(command.name, before, after)
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

    private fun countOccurrences(
        text: String,
        needle: String,
    ): Int {
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = text.indexOf(needle, startIndex = index + needle.length)
        }
        return count
    }

    private fun runProfiledYesLineWidthCommand(
        device: K16RuntimeDevice,
        metrics: RecordingRuntimeMetricsCollector,
        sample: ProfiledYesLineWidth,
        lines: Int,
    ): ProfiledYesLineWidthResult {
        val before = metrics.snapshot()
        val command = "yes -n $lines ${sample.payload}"
        val startedAt = System.nanoTime()
        DeviceEvents.dispatch(device, PasteInputEvent(ByteBuffer.wrap("$command\n".encodeToByteArray())))
        val inputQueuedNanos = System.nanoTime() - startedAt
        var ticks = 0
        var visibleNanos: Long? = null

        while (ticks < 520 && visibleNanos == null) {
            ticks += 1
            tickAndSync(device)
            val elapsed = System.nanoTime() - startedAt
            val terminal = device.snapshotRuntimeState()?.let(::terminalText) ?: ""
            val repeatedOutputVisible = countOccurrences(terminal, sample.payload) >= 8
            val lastOutputIndex = terminal.lastIndexOf(sample.payload)
            val promptReturned =
                lastOutputIndex >= 0 &&
                    terminal.indexOf("K16> ", startIndex = lastOutputIndex + sample.payload.length) > lastOutputIndex
            if (repeatedOutputVisible && promptReturned) {
                visibleNanos = elapsed
            }
            Thread.sleep(1)
        }

        val visible =
            visibleNanos
                ?: error("yes ${sample.width}-char mass text output did not finish and return to the prompt")
        val after = metrics.snapshot()
        val gpuBefore = before.k16.gpu
        val gpuAfter = after.k16.gpu
        return ProfiledYesLineWidthResult(
            command = command,
            lines = lines,
            inputQueuedNanos = inputQueuedNanos,
            visibleNanos = visible,
            ticks = ticks,
            slices = after.vm.k16RunSlices - before.vm.k16RunSlices,
            runNanos = after.vm.k16RunNanos - before.vm.k16RunNanos,
            yieldSignals = after.vm.k16RunYieldSignals - before.vm.k16RunYieldSignals,
            waitSignals = after.vm.k16RunWaitSignals - before.vm.k16RunWaitSignals,
            pauseSignals = after.vm.k16RunPauseSignals - before.vm.k16RunPauseSignals,
            inputWakeups = after.vm.k16WaitInputWakeups - before.vm.k16WaitInputWakeups,
            blitCommands = gpuAfter.blitBufferCommands - gpuBefore.blitBufferCommands,
            presentCommands = gpuAfter.presentCommands - gpuBefore.presentCommands,
            frames = gpuAfter.frames - gpuBefore.frames,
            frameTiles = gpuAfter.frameTiles - gpuBefore.frameTiles,
            frameBytes = gpuAfter.framePayloadBytes - gpuBefore.framePayloadBytes,
        )
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
private const val K16_TERMINAL_COLUMNS = 64
private const val K16_TERMINAL_ROWS = 25
private const val K16_BIOS_SPLASH_TICKS = 20
private const val K16_BIOS_SPLASH_WAIT_PROFILE_TICKS = K16_BIOS_SPLASH_TICKS - 1
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

private data class ProfiledYesLineWidth(
    val width: Int,
    val payload: String,
)

private data class ProfiledYesLineWidthResult(
    val command: String,
    val lines: Int,
    val inputQueuedNanos: Long,
    val visibleNanos: Long,
    val ticks: Int,
    val slices: Long,
    val runNanos: Long,
    val yieldSignals: Long,
    val waitSignals: Long,
    val pauseSignals: Long,
    val inputWakeups: Long,
    val blitCommands: Long,
    val presentCommands: Long,
    val frames: Long,
    val frameTiles: Long,
    val frameBytes: Long,
)

private data class K16ProfiledCommandSample(
    val name: String,
    val before: RuntimeProfilingSnapshot,
    val after: RuntimeProfilingSnapshot,
)

private data class KfsHotspotDelta(
    val name: String,
    val metadataOps: Long,
    val dataReadBlocks: Long,
    val readCommands: Long,
    val mediaReadBlocks: Long,
    val pathLookups: Long,
    val inodeLoads: Long,
    val dirEntryScans: Long,
    val fileOpens: Long,
    val fileReads: Long,
    val statCalls: Long,
    val readDirCalls: Long,
    val genericFileDataReadBlocks: Long,
    val readDirDataReadBlocks: Long,
    val programDataReadBlocks: Long,
    val dynamicImportDataReadBlocks: Long,
    val libraryDataReadBlocks: Long,
    val storageWriteCommands: Long,
    val mediaWriteBlocks: Long,
    val blockCacheHits: Long,
    val blockCacheMisses: Long,
    val blockCacheBatchReads: Long,
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
        "storageReadCommands=${storageAfter.readCommands - storageBefore.readCommands}, " +
        "storageRequestedReadBlocks=${storageAfter.requestedReadBlocks - storageBefore.requestedReadBlocks}, " +
        "storageRequestedReadBytes=${storageAfter.requestedReadBytes - storageBefore.requestedReadBytes}, " +
        "storageMediaReadBlocks=${storageAfter.mediaReadBlocks - storageBefore.mediaReadBlocks}, " +
        "storageMediaWriteBlocks=${storageAfter.mediaWriteBlocks - storageBefore.mediaWriteBlocks}, " +
        "storageUniqueReadBlocks=${storageAfter.uniqueReadBlocks - storageBefore.uniqueReadBlocks}, " +
        "storageRepeatedReadBlocks=${storageAfter.repeatedReadBlocks - storageBefore.repeatedReadBlocks}, " +
        "storagePartitionTableReadBlocks=${storageAfter.partitionTableReadBlocks - storageBefore.partitionTableReadBlocks}, " +
        "storageBootMetadataReadBlocks=${storageAfter.bootMetadataReadBlocks - storageBefore.bootMetadataReadBlocks}, " +
        "storageBootDataReadBlocks=${storageAfter.bootDataReadBlocks - storageBefore.bootDataReadBlocks}, " +
        "storageRootMetadataReadBlocks=${storageAfter.rootMetadataReadBlocks - storageBefore.rootMetadataReadBlocks}, " +
        "storageRootDataReadBlocks=${storageAfter.rootDataReadBlocks - storageBefore.rootDataReadBlocks}, " +
        "storageUnknownReadBlocks=${storageAfter.unknownReadBlocks - storageBefore.unknownReadBlocks}, " +
        "storageWriteCommands=${storageAfter.writeCommands - storageBefore.writeCommands}, " +
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
        "readDirCalls=${osAfter.readDirCalls - osBefore.readDirCalls}, " +
        "programLoadBytes=${osAfter.programLoadBytes - osBefore.programLoadBytes}, " +
        "dynamicImportBytes=${osAfter.dynamicImportBytes - osBefore.dynamicImportBytes}, " +
        "libraryLoadBytes=${osAfter.libraryLoadBytes - osBefore.libraryLoadBytes}, " +
        "genericFileDataReadBlocks=${osAfter.genericFileDataReadBlocks - osBefore.genericFileDataReadBlocks}, " +
        "genericFileDataReadBytes=${osAfter.genericFileDataReadBytes - osBefore.genericFileDataReadBytes}, " +
        "readDirDataReadBlocks=${osAfter.readDirDataReadBlocks - osBefore.readDirDataReadBlocks}, " +
        "readDirDataReadBytes=${osAfter.readDirDataReadBytes - osBefore.readDirDataReadBytes}, " +
        "programDataReadBlocks=${osAfter.programDataReadBlocks - osBefore.programDataReadBlocks}, " +
        "programDataReadBytes=${osAfter.programDataReadBytes - osBefore.programDataReadBytes}, " +
        "dynamicImportDataReadBlocks=${osAfter.dynamicImportDataReadBlocks - osBefore.dynamicImportDataReadBlocks}, " +
        "dynamicImportDataReadBytes=${osAfter.dynamicImportDataReadBytes - osBefore.dynamicImportDataReadBytes}, " +
        "libraryDataReadBlocks=${osAfter.libraryDataReadBlocks - osBefore.libraryDataReadBlocks}, " +
        "libraryDataReadBytes=${osAfter.libraryDataReadBytes - osBefore.libraryDataReadBytes}, " +
        "blockCacheHits=${osAfter.blockCacheHits - osBefore.blockCacheHits}, " +
        "blockCacheMisses=${osAfter.blockCacheMisses - osBefore.blockCacheMisses}, " +
        "blockCacheBatchReads=${osAfter.blockCacheBatchReads - osBefore.blockCacheBatchReads}, " +
        "initProgramFileDataReadBlocks=${osAfter.initProgramFileDataReadBlocks - osBefore.initProgramFileDataReadBlocks}, " +
        "initProgramFileDataReadBytes=${osAfter.initProgramFileDataReadBytes - osBefore.initProgramFileDataReadBytes}, " +
        "shellProgramFileDataReadBlocks=${osAfter.shellProgramFileDataReadBlocks - osBefore.shellProgramFileDataReadBlocks}, " +
        "shellProgramFileDataReadBytes=${osAfter.shellProgramFileDataReadBytes - osBefore.shellProgramFileDataReadBytes}, " +
        "otherProgramFileDataReadBlocks=${osAfter.otherProgramFileDataReadBlocks - osBefore.otherProgramFileDataReadBlocks}, " +
        "otherProgramFileDataReadBytes=${osAfter.otherProgramFileDataReadBytes - osBefore.otherProgramFileDataReadBytes}, " +
        "libkraftLibraryFileDataReadBlocks=${osAfter.libkraftLibraryFileDataReadBlocks - osBefore.libkraftLibraryFileDataReadBlocks}, " +
        "libkraftLibraryFileDataReadBytes=${osAfter.libkraftLibraryFileDataReadBytes - osBefore.libkraftLibraryFileDataReadBytes}, " +
        "otherLibraryFileDataReadBlocks=${osAfter.otherLibraryFileDataReadBlocks - osBefore.otherLibraryFileDataReadBlocks}, " +
        "otherLibraryFileDataReadBytes=${osAfter.otherLibraryFileDataReadBytes - osBefore.otherLibraryFileDataReadBytes}"
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
        "storageReadCommands=${storageAfter.readCommands - storageBefore.readCommands}, " +
        "storageRequestedReadBlocks=${storageAfter.requestedReadBlocks - storageBefore.requestedReadBlocks}, " +
        "storageRequestedReadBytes=${storageAfter.requestedReadBytes - storageBefore.requestedReadBytes}, " +
        "storageMediaReadBlocks=${storageAfter.mediaReadBlocks - storageBefore.mediaReadBlocks}, " +
        "storageMediaWriteBlocks=${storageAfter.mediaWriteBlocks - storageBefore.mediaWriteBlocks}, " +
        "storageUniqueReadBlocks=${storageAfter.uniqueReadBlocks - storageBefore.uniqueReadBlocks}, " +
        "storageRepeatedReadBlocks=${storageAfter.repeatedReadBlocks - storageBefore.repeatedReadBlocks}, " +
        "storagePartitionTableReadBlocks=${storageAfter.partitionTableReadBlocks - storageBefore.partitionTableReadBlocks}, " +
        "storageBootMetadataReadBlocks=${storageAfter.bootMetadataReadBlocks - storageBefore.bootMetadataReadBlocks}, " +
        "storageBootDataReadBlocks=${storageAfter.bootDataReadBlocks - storageBefore.bootDataReadBlocks}, " +
        "storageRootMetadataReadBlocks=${storageAfter.rootMetadataReadBlocks - storageBefore.rootMetadataReadBlocks}, " +
        "storageRootDataReadBlocks=${storageAfter.rootDataReadBlocks - storageBefore.rootDataReadBlocks}, " +
        "storageUnknownReadBlocks=${storageAfter.unknownReadBlocks - storageBefore.unknownReadBlocks}, " +
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
        "readDirCalls=${osAfter.readDirCalls - osBefore.readDirCalls}, " +
        "programLoadBytes=${osAfter.programLoadBytes - osBefore.programLoadBytes}, " +
        "dynamicImportBytes=${osAfter.dynamicImportBytes - osBefore.dynamicImportBytes}, " +
        "libraryLoadBytes=${osAfter.libraryLoadBytes - osBefore.libraryLoadBytes}, " +
        "genericFileDataReadBlocks=${osAfter.genericFileDataReadBlocks - osBefore.genericFileDataReadBlocks}, " +
        "genericFileDataReadBytes=${osAfter.genericFileDataReadBytes - osBefore.genericFileDataReadBytes}, " +
        "readDirDataReadBlocks=${osAfter.readDirDataReadBlocks - osBefore.readDirDataReadBlocks}, " +
        "readDirDataReadBytes=${osAfter.readDirDataReadBytes - osBefore.readDirDataReadBytes}, " +
        "programDataReadBlocks=${osAfter.programDataReadBlocks - osBefore.programDataReadBlocks}, " +
        "programDataReadBytes=${osAfter.programDataReadBytes - osBefore.programDataReadBytes}, " +
        "dynamicImportDataReadBlocks=${osAfter.dynamicImportDataReadBlocks - osBefore.dynamicImportDataReadBlocks}, " +
        "dynamicImportDataReadBytes=${osAfter.dynamicImportDataReadBytes - osBefore.dynamicImportDataReadBytes}, " +
        "libraryDataReadBlocks=${osAfter.libraryDataReadBlocks - osBefore.libraryDataReadBlocks}, " +
        "libraryDataReadBytes=${osAfter.libraryDataReadBytes - osBefore.libraryDataReadBytes}, " +
        "blockCacheHits=${osAfter.blockCacheHits - osBefore.blockCacheHits}, " +
        "blockCacheMisses=${osAfter.blockCacheMisses - osBefore.blockCacheMisses}, " +
        "blockCacheBatchReads=${osAfter.blockCacheBatchReads - osBefore.blockCacheBatchReads}, " +
        "initProgramFileDataReadBlocks=${osAfter.initProgramFileDataReadBlocks - osBefore.initProgramFileDataReadBlocks}, " +
        "initProgramFileDataReadBytes=${osAfter.initProgramFileDataReadBytes - osBefore.initProgramFileDataReadBytes}, " +
        "shellProgramFileDataReadBlocks=${osAfter.shellProgramFileDataReadBlocks - osBefore.shellProgramFileDataReadBlocks}, " +
        "shellProgramFileDataReadBytes=${osAfter.shellProgramFileDataReadBytes - osBefore.shellProgramFileDataReadBytes}, " +
        "otherProgramFileDataReadBlocks=${osAfter.otherProgramFileDataReadBlocks - osBefore.otherProgramFileDataReadBlocks}, " +
        "otherProgramFileDataReadBytes=${osAfter.otherProgramFileDataReadBytes - osBefore.otherProgramFileDataReadBytes}, " +
        "libkraftLibraryFileDataReadBlocks=${osAfter.libkraftLibraryFileDataReadBlocks - osBefore.libkraftLibraryFileDataReadBlocks}, " +
        "libkraftLibraryFileDataReadBytes=${osAfter.libkraftLibraryFileDataReadBytes - osBefore.libkraftLibraryFileDataReadBytes}, " +
        "otherLibraryFileDataReadBlocks=${osAfter.otherLibraryFileDataReadBlocks - osBefore.otherLibraryFileDataReadBlocks}, " +
        "otherLibraryFileDataReadBytes=${osAfter.otherLibraryFileDataReadBytes - osBefore.otherLibraryFileDataReadBytes}"
}

private fun formatKfsHotspotSummary(samples: List<K16ProfiledCommandSample>): String {
    val deltas =
        samples.map { sample ->
            val osBefore = sample.before.k16.os
            val osAfter = sample.after.k16.os
            val storageBefore = sample.before.k16.storage0
            val storageAfter = sample.after.k16.storage0
            val metadataOps =
                osAfter.pathLookups - osBefore.pathLookups +
                    osAfter.inodeLoads - osBefore.inodeLoads +
                    osAfter.dirEntryScans - osBefore.dirEntryScans +
                    osAfter.statCalls - osBefore.statCalls
            val dataReadBlocks =
                osAfter.genericFileDataReadBlocks - osBefore.genericFileDataReadBlocks +
                    osAfter.readDirDataReadBlocks - osBefore.readDirDataReadBlocks +
                    osAfter.programDataReadBlocks - osBefore.programDataReadBlocks +
                    osAfter.dynamicImportDataReadBlocks - osBefore.dynamicImportDataReadBlocks +
                    osAfter.libraryDataReadBlocks - osBefore.libraryDataReadBlocks
            KfsHotspotDelta(
                name = sample.name,
                metadataOps = metadataOps,
                dataReadBlocks = dataReadBlocks,
                readCommands = storageAfter.readCommands - storageBefore.readCommands,
                mediaReadBlocks = storageAfter.mediaReadBlocks - storageBefore.mediaReadBlocks,
                pathLookups = osAfter.pathLookups - osBefore.pathLookups,
                inodeLoads = osAfter.inodeLoads - osBefore.inodeLoads,
                dirEntryScans = osAfter.dirEntryScans - osBefore.dirEntryScans,
                fileOpens = osAfter.fileOpens - osBefore.fileOpens,
                fileReads = osAfter.fileReads - osBefore.fileReads,
                statCalls = osAfter.statCalls - osBefore.statCalls,
                readDirCalls = osAfter.readDirCalls - osBefore.readDirCalls,
                genericFileDataReadBlocks = osAfter.genericFileDataReadBlocks - osBefore.genericFileDataReadBlocks,
                readDirDataReadBlocks = osAfter.readDirDataReadBlocks - osBefore.readDirDataReadBlocks,
                programDataReadBlocks = osAfter.programDataReadBlocks - osBefore.programDataReadBlocks,
                dynamicImportDataReadBlocks = osAfter.dynamicImportDataReadBlocks - osBefore.dynamicImportDataReadBlocks,
                libraryDataReadBlocks = osAfter.libraryDataReadBlocks - osBefore.libraryDataReadBlocks,
                storageWriteCommands = storageAfter.writeCommands - storageBefore.writeCommands,
                mediaWriteBlocks = storageAfter.mediaWriteBlocks - storageBefore.mediaWriteBlocks,
                blockCacheHits = osAfter.blockCacheHits - osBefore.blockCacheHits,
                blockCacheMisses = osAfter.blockCacheMisses - osBefore.blockCacheMisses,
                blockCacheBatchReads = osAfter.blockCacheBatchReads - osBefore.blockCacheBatchReads,
            )
        }

    fun ranked(selector: (KfsHotspotDelta) -> Long): String =
        deltas
            .sortedWith(compareByDescending<KfsHotspotDelta> { selector(it) }.thenBy { it.name })
            .joinToString(prefix = "[", postfix = "]") { delta -> "${delta.name}:${selector(delta)}" }

    return "k16FsHotspots: " +
        "metadataOps=${ranked { it.metadataOps }}, " +
        "dataReadBlocks=${ranked { it.dataReadBlocks }}, " +
        "readCommands=${ranked { it.readCommands }}, " +
        "mediaReadBlocks=${ranked { it.mediaReadBlocks }}, " +
        "pathLookups=${ranked { it.pathLookups }}, " +
        "inodeLoads=${ranked { it.inodeLoads }}, " +
        "dirEntryScans=${ranked { it.dirEntryScans }}, " +
        "fileOpens=${ranked { it.fileOpens }}, " +
        "fileReads=${ranked { it.fileReads }}, " +
        "statCalls=${ranked { it.statCalls }}, " +
        "readDirCalls=${ranked { it.readDirCalls }}, " +
        "genericFileDataReadBlocks=${ranked { it.genericFileDataReadBlocks }}, " +
        "readDirDataReadBlocks=${ranked { it.readDirDataReadBlocks }}, " +
        "programDataReadBlocks=${ranked { it.programDataReadBlocks }}, " +
        "dynamicImportDataReadBlocks=${ranked { it.dynamicImportDataReadBlocks }}, " +
        "libraryDataReadBlocks=${ranked { it.libraryDataReadBlocks }}, " +
        "storageWriteCommands=${ranked { it.storageWriteCommands }}, " +
        "mediaWriteBlocks=${ranked { it.mediaWriteBlocks }}, " +
        "blockCacheHits=${ranked { it.blockCacheHits }}, " +
        "blockCacheMisses=${ranked { it.blockCacheMisses }}, " +
        "blockCacheBatchReads=${ranked { it.blockCacheBatchReads }}"
}

private fun metricValue(line: String, name: String): Long {
    val match = Regex("""(?:^|, )$name=(\d+)""").find(line)
    require(match != null) { "missing metric `$name` in line: $line" }
    return match.groupValues[1].toLong()
}

private fun List<String>.singleCommandLine(name: String): String = single { it.contains("name=$name,") }

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

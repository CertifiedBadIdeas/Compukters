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

package ru.lazyhat.compukterkraft.core.device.runtime

import ru.lazyhat.compukterkraft.lang.runtime.VmInstructionKind
import ru.lazyhat.compukterkraft.lang.runtime.VmSignalKind
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16BusTraffic
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16ComputerStatsSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16DecodeCacheStats
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16GpuStats
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16MmioDeviceStats
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16OsStats
import ru.lazyhat.compukterkraft.lang.runtime.blazing.NativeK16StorageStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeProfilingTest {
    @Test
    fun runtimeProfilingDoesNotExposeRemovedNativeProcessBridgeMetrics() {
        val collectorMethodNames =
            RuntimeMetricsCollector::class.java.methods
                .map { it.name }
                .toSet()
        val vmMetricFields =
            RuntimeVmMetrics::class.java.declaredFields
                .map { it.name }
                .toSet()

        assertTrue(collectorMethodNames.none { it.contains("NativeProcess") }, collectorMethodNames.toString())
        assertTrue(vmMetricFields.none { it.startsWith("nativeProcess") }, vmMetricFields.toString())
    }

    @Test
    fun runtimeProfilingDoesNotExposeKotlinSchedulerOrHostQueueMetrics() {
        val forbiddenNames =
            listOf(
                "recordHostCallDrain",
                "recordHostCallDispatch",
                "recordHostResultDelivery",
                "recordExecutionQuotaRefill",
                "recordExecutionQuotaPermitConsumed",
                "recordNativeSchedulerDryRun",
                "recordProcessSchedulerTick",
                "recordSlicePermitReceived",
                "recordSchedulingPoint",
                "recordVmExecutionWindow",
                "hostCallDrainCalls",
                "hostCallsDrained",
                "hostCallDrainNanos",
                "hostCallDispatchCalls",
                "hostCallsDispatched",
                "hostCallDispatchNanos",
                "hostResultDeliveryCalls",
                "hostResultsDelivered",
                "hostResultDeliveryNanos",
                "slicePermitsSent",
                "sleepGatedSliceRequests",
                "executionQuotaRefills",
                "executionQuotaAcceptedRefills",
                "executionQuotaUnavailableRefills",
                "executionQuotaPermitsConsumed",
                "nativeSchedulerDryRuns",
                "nativeSchedulerDryRunTurns",
                "nativeSchedulerDryRunSelectedPids",
                "nativeSchedulerDryRunRemainingInstructions",
                "nativeSchedulerDryRunFirstSelectionMatches",
                "nativeSchedulerDryRunFirstSelectionMismatches",
                "processSchedulerTicks",
                "processSchedulerSelectedTicks",
                "processSchedulerIdleTicks",
                "processSchedulerWokenProcesses",
                "slicePermitsReceived",
                "schedulingPoints",
                "yieldSchedulingPoints",
                "waitForSliceSchedulingPoints",
                "executionWindows",
                "executionWindowNanos",
                "averageExecutionWindowNanos",
            )
        val collectorMethodNames =
            RuntimeMetricsCollector::class.java.methods
                .map { it.name }
        val tickMetricFields =
            RuntimeTickMetrics::class.java.declaredFields
                .map { it.name }
        val vmMetricFields =
            RuntimeVmMetrics::class.java.declaredFields
                .map { it.name }
        val exposedStaleNames =
            (collectorMethodNames + tickMetricFields + vmMetricFields)
                .filter { name -> forbiddenNames.any { staleName -> name.equals(staleName, ignoreCase = true) } }

        assertEquals(emptyList(), exposedStaleNames)
    }

    @Test
    fun recordingCollectorAccumulatesRuntimeAndVmMetrics() {
        val collector = RecordingRuntimeMetricsCollector()

        collector.recordServerTick(nanos = 100)
        collector.recordRequestSlice(nanos = 10)
        collector.recordDisplayFrameDrain(frameCount = 3, nanos = 50)
        collector.recordDisplayFlush(frameCount = 3, nanos = 60)
        collector.recordSliceRequest()
        collector.recordSliceRequest()
        collector.recordNativeExecutionQuotaRefill(wallNanos = 250, serverTick = 12)
        collector.recordNativeExecutionQuotaRefill(wallNanos = 125, serverTick = 13)
        collector.recordVmSignal(VmSignalKind.PAUSE)
        collector.recordVmSignal(VmSignalKind.YIELD)
        collector.recordVmSignal(VmSignalKind.SLEEP)
        collector.recordVmSignal(VmSignalKind.WAIT_EVENT)
        collector.recordVmSignal(VmSignalKind.WAIT_POLL)
        collector.recordVmSignal(VmSignalKind.HOST_CALL)
        collector.recordVmSignal(VmSignalKind.HALT)
        collector.recordVmHostCallWait("display", "blitMono5x7Packed", nanos = 90)
        collector.recordVmHostCallWait("display", "blitMono5x7Packed", nanos = 10)
        collector.recordVmHostCall("display", "blitMono5x7Packed", nanos = 100)
        collector.recordVmHostCall("display", "blitMono5x7Packed", nanos = 50)
        collector.recordVmHostCall("events", "tryPull", nanos = 30)
        collector.recordNativeWait("runtime.poll", nanos = 100)
        collector.recordNativeWait("runtime.poll", nanos = 50, woke = false)
        collector.recordNativeDisplayPumpWait(nanos = 100)
        collector.recordNativeDisplayPumpWait(nanos = 50, woke = false)
        collector.recordNativeDisplayFrameBytes(bytes = 128)
        collector.recordNativeDaemonTick(activeNanos = 100, turns = 2, halted = 1, hostRequests = 3, idle = false)
        collector.recordNativeDaemonTick(activeNanos = 50, turns = 0, halted = 0, hostRequests = 0, idle = true)
        collector.recordK16RunSlice(K16RuntimeSignal.WAIT, nanos = 1000)
        collector.recordK16RunSlice(K16RuntimeSignal.WAIT, nanos = 500)
        collector.recordK16RunSlice(K16RuntimeSignal.YIELD, nanos = 250)
        collector.recordK16RunSlice(K16RuntimeSignal.PAUSE, nanos = 125)
        collector.recordK16RunSlice(K16RuntimeSignal.HALT, nanos = 75)
        collector.recordK16OutputRefresh(serialOutputBytes = 4, gpuFrameBytes = 64, gpuFrameCount = 2, nanos = 100)
        collector.recordK16OutputRefresh(serialOutputBytes = 8, gpuFrameBytes = 0, gpuFrameCount = 0, nanos = 50)
        collector.recordK16TextInput(byteCount = 1, nanos = 7)
        collector.recordK16TextInput(byteCount = 3, nanos = 11)
        collector.recordK16StatsSnapshot(
            NativeK16ComputerStatsSnapshot(
                ram = NativeK16BusTraffic(loads = 10, stores = 11, bytesRead = 12, bytesWritten = 13),
                mmio = NativeK16BusTraffic(loads = 20, stores = 21, bytesRead = 22, bytesWritten = 23),
                decodeCache = NativeK16DecodeCacheStats(entries = 42, hits = 43, misses = 44),
                os =
                    NativeK16OsStats(
                        pathLookups = 31,
                        inodeLoads = 32,
                        dirEntryScans = 33,
                        fileOpens = 34,
                        fileReads = 35,
                        statCalls = 36,
                        processSpawns = 37,
                        programLoads = 38,
                        dynamicImportLoads = 39,
                        libraryLoads = 40,
                        readDirCalls = 41,
                        programLoadBytes = 42,
                        dynamicImportBytes = 43,
                        libraryLoadBytes = 44,
                        genericFileDataReadBlocks = 45,
                        genericFileDataReadBytes = 46,
                        readDirDataReadBlocks = 47,
                        readDirDataReadBytes = 48,
                        programDataReadBlocks = 49,
                        programDataReadBytes = 50,
                        dynamicImportDataReadBlocks = 51,
                        dynamicImportDataReadBytes = 52,
                        libraryDataReadBlocks = 53,
                        libraryDataReadBytes = 54,
                        blockCacheHits = 55,
                        blockCacheMisses = 56,
                        blockCacheBatchReads = 57,
                    ),
                devices =
                    listOf(
                        NativeK16MmioDeviceStats(
                            deviceId = 3,
                            base = 0x2000,
                            size = 64,
                            traffic = NativeK16BusTraffic(loads = 4, stores = 5, bytesRead = 6, bytesWritten = 7),
                            storage =
                                NativeK16StorageStats(
                                    readCommands = 8,
                                    writeCommands = 9,
                                    flushCommands = 10,
                                    bytesRead = 11,
                                    bytesWritten = 12,
                                    failedCommands = 13,
                                    mediaReadBlocks = 14,
                                    mediaWriteBlocks = 15,
                                    uniqueReadBlocks = 16,
                                    repeatedReadBlocks = 17,
                                    partitionTableReadBlocks = 18,
                                    bootMetadataReadBlocks = 19,
                                    bootDataReadBlocks = 20,
                                    rootMetadataReadBlocks = 21,
                                    rootDataReadBlocks = 22,
                                    unknownReadBlocks = 23,
                                    requestedReadBlocks = 24,
                                    requestedReadBytes = 12288,
                                ),
                            gpu =
                                NativeK16GpuStats(
                                    blitBufferCommands = 22,
                                    blitPixels = 23,
                                    blitSourceBytes = 24,
                                    presentCommands = 25,
                                    frames = 26,
                                    frameTiles = 27,
                                    framePayloadBytes = 28,
                                ),
                        ),
                    ),
            ),
        )
        collector.recordK16WaitEnter()
        collector.recordK16WaitEnter()
        collector.recordK16WaitTimerWakeup()
        collector.recordK16WaitInputWakeup()
        collector.recordK16WaitIdleSkip()
        collector.recordK16WaitIdleSkip()
        collector.recordK16WaitIdleSkip()
        collector.recordVmInstruction(VmInstructionKind.CALL_BUILTIN, nanos = 40)
        collector.recordVmInstruction(VmInstructionKind.CALL_BUILTIN, nanos = 60)
        collector.recordVmInstruction(VmInstructionKind.PUSH_INT, nanos = 10)

        val snapshot = collector.snapshot()

        assertEquals(1, snapshot.tick.serverTickCalls)
        assertEquals(100, snapshot.tick.serverTickNanos)
        assertEquals(1, snapshot.tick.requestSliceCalls)
        assertEquals(10, snapshot.tick.requestSliceNanos)
        assertEquals(1, snapshot.tick.displayFrameDrainCalls)
        assertEquals(3, snapshot.tick.displayFramesDrained)
        assertEquals(50, snapshot.tick.displayFrameDrainNanos)
        assertEquals(1, snapshot.tick.displayFlushCalls)
        assertEquals(3, snapshot.tick.displayFramesFlushed)
        assertEquals(60, snapshot.tick.displayFlushNanos)
        assertEquals(2, snapshot.vm.sliceRequests)
        assertEquals(2, snapshot.vm.nativeExecutionQuotaRefills)
        assertEquals(375, snapshot.vm.nativeExecutionQuotaWallNanos)
        assertEquals(13, snapshot.vm.nativeExecutionQuotaLastServerTick)
        assertEquals(1, snapshot.vm.pauseSignals)
        assertEquals(1, snapshot.vm.yieldSignals)
        assertEquals(1, snapshot.vm.sleepSignals)
        assertEquals(1, snapshot.vm.waitEventSignals)
        assertEquals(1, snapshot.vm.waitPollSignals)
        assertEquals(1, snapshot.vm.hostCallSignals)
        assertEquals(1, snapshot.vm.haltSignals)
        assertEquals(2, snapshot.vm.nativeWaitCalls)
        assertEquals(150, snapshot.vm.nativeWaitNanos)
        assertEquals(1, snapshot.vm.nativeWaitWakeups)
        assertEquals(1, snapshot.vm.nativeWaitTimeouts)
        assertEquals(2, snapshot.vm.nativeDisplayPumpWaitCalls)
        assertEquals(150, snapshot.vm.nativeDisplayPumpWaitNanos)
        assertEquals(1, snapshot.vm.nativeDisplayPumpWakeups)
        assertEquals(1, snapshot.vm.nativeDisplayPumpTimeouts)
        assertEquals(1, snapshot.vm.nativeDisplayFrameByteBatches)
        assertEquals(128, snapshot.vm.nativeDisplayFrameBytes)
        assertEquals(2, snapshot.vm.nativeDaemonTicks)
        assertEquals(150, snapshot.vm.nativeDaemonActiveNanos)
        assertEquals(1, snapshot.vm.nativeDaemonIdleTicks)
        assertEquals(2, snapshot.vm.nativeDaemonTurns)
        assertEquals(1, snapshot.vm.nativeDaemonHaltedProcesses)
        assertEquals(3, snapshot.vm.nativeDaemonHostRequests)
        assertEquals(5, snapshot.vm.k16RunSlices)
        assertEquals(1950, snapshot.vm.k16RunNanos)
        assertEquals(1, snapshot.vm.k16RunHaltSignals)
        assertEquals(2, snapshot.vm.k16RunWaitSignals)
        assertEquals(1, snapshot.vm.k16RunYieldSignals)
        assertEquals(1, snapshot.vm.k16RunPauseSignals)
        assertEquals(2, snapshot.vm.k16OutputRefreshes)
        assertEquals(150, snapshot.vm.k16OutputRefreshNanos)
        assertEquals(2, snapshot.vm.k16SerialOutputSnapshots)
        assertEquals(12, snapshot.vm.k16SerialOutputSnapshotBytes)
        assertEquals(1, snapshot.vm.k16GpuFrameBatches)
        assertEquals(64, snapshot.vm.k16GpuFrameBytes)
        assertEquals(2, snapshot.vm.k16GpuFramesDecoded)
        assertEquals(2, snapshot.vm.k16TextInputEvents)
        assertEquals(4, snapshot.vm.k16TextInputBytes)
        assertEquals(31, snapshot.k16.os.pathLookups)
        assertEquals(32, snapshot.k16.os.inodeLoads)
        assertEquals(33, snapshot.k16.os.dirEntryScans)
        assertEquals(34, snapshot.k16.os.fileOpens)
        assertEquals(35, snapshot.k16.os.fileReads)
        assertEquals(36, snapshot.k16.os.statCalls)
        assertEquals(37, snapshot.k16.os.processSpawns)
        assertEquals(38, snapshot.k16.os.programLoads)
        assertEquals(39, snapshot.k16.os.dynamicImportLoads)
        assertEquals(40, snapshot.k16.os.libraryLoads)
        assertEquals(41, snapshot.k16.os.readDirCalls)
        assertEquals(42, snapshot.k16.os.programLoadBytes)
        assertEquals(43, snapshot.k16.os.dynamicImportBytes)
        assertEquals(44, snapshot.k16.os.libraryLoadBytes)
        assertEquals(55, snapshot.k16.os.blockCacheHits)
        assertEquals(56, snapshot.k16.os.blockCacheMisses)
        assertEquals(57, snapshot.k16.os.blockCacheBatchReads)
        assertEquals(18, snapshot.vm.k16TextInputNanos)
        assertEquals(RuntimeK16BusTrafficMetrics(loads = 10, stores = 11, bytesRead = 12, bytesWritten = 13), snapshot.k16.ram)
        assertEquals(RuntimeK16BusTrafficMetrics(loads = 20, stores = 21, bytesRead = 22, bytesWritten = 23), snapshot.k16.mmio)
        assertEquals(1, snapshot.k16.devices.size)
        assertEquals(3, snapshot.k16.devices.single().deviceId)
        assertEquals(0x2000, snapshot.k16.devices.single().base)
        assertEquals(64, snapshot.k16.devices.single().size)
        assertEquals(RuntimeK16BusTrafficMetrics(loads = 4, stores = 5, bytesRead = 6, bytesWritten = 7), snapshot.k16.devices.single().traffic)
        assertEquals(24, snapshot.k16.storage0.requestedReadBlocks)
        assertEquals(12288, snapshot.k16.storage0.requestedReadBytes)
        assertEquals(
            RuntimeK16GpuMetrics(
                blitBufferCommands = 22,
                blitPixels = 23,
                blitSourceBytes = 24,
                presentCommands = 25,
                frames = 26,
                frameTiles = 27,
                framePayloadBytes = 28,
            ),
            snapshot.k16.gpu,
        )
        assertEquals(2, snapshot.vm.k16WaitEntries)
        assertEquals(1, snapshot.vm.k16WaitTimerWakeups)
        assertEquals(1, snapshot.vm.k16WaitInputWakeups)
        assertEquals(3, snapshot.vm.k16WaitIdleSkips)
        val blitCall = snapshot.hostCalls.first { it.moduleName == "display" && it.functionName == "blitMono5x7Packed" }
        assertEquals(2, blitCall.calls)
        assertEquals(150, blitCall.nanos)
        assertEquals(100, blitCall.waitNanos)
        assertEquals(50, blitCall.activeNanos)
        assertEquals(75, blitCall.averageNanos)
        assertEquals(25, blitCall.averageActiveNanos)
        val tryPullCall = snapshot.hostCalls.first { it.moduleName == "events" && it.functionName == "tryPull" }
        assertEquals(1, tryPullCall.calls)
        assertEquals(30, tryPullCall.nanos)
        assertEquals(30, tryPullCall.activeNanos)
        assertEquals(VmInstructionKind.CALL_BUILTIN, snapshot.instructions.first().kind)
        assertEquals(2, snapshot.instructions.first().count)
        assertEquals(100, snapshot.instructions.first().nanos)
        assertEquals(50, snapshot.instructions.first().averageNanos)
        val summary = snapshot.summary()
        assertTrue(summary.startsWith("runtime:\n"), summary)
        assertTrue(summary.contains("  vm:\n"), summary)
        assertTrue(
            summary.contains("  signals: halt=1, pause=1, yield=1, sleep=1, waitEvent=1, waitPoll=1, waitProcess=0, hostCall=1"),
            summary,
        )
        assertTrue(
            summary.contains("    nativeDisplayPump: waits=2, waitTime=150 ns, wakeups=1, timeouts=1, byteBatches=1, bytes=128"),
            summary,
        )
        assertTrue(
            summary.contains("    nativeDaemon: ticks=2, active=150 ns, idle=1, turns=2, halted=1, hostRequests=3"),
            summary,
        )
        assertTrue(
            summary.contains("    k16Execution: slices=5, time=1950 ns, haltSignals=1, waitSignals=2, yieldSignals=1, pauseSignals=1"),
            summary,
        )
        assertTrue(
            summary.contains("    k16Output: refreshes=2, time=150 ns"),
            summary,
        )
        assertTrue(
            summary.contains("    k16TextOutput: snapshots=2, snapshotBytes=12"),
            summary,
        )
        assertTrue(
            summary.contains("    k16DisplayFrames: batches=1, bytes=64, frames=2"),
            summary,
        )
        assertTrue(
            summary.contains(
                "    k16Gpu: blits=22, blitPixels=23, blitBytes=24, presents=25, frames=26, tiles=27, frameBytes=28",
            ),
            summary,
        )
        assertTrue(
            summary.contains("    k16TextInput: events=2, bytes=4, time=18 ns"),
            summary,
        )
        assertTrue(
            summary.contains("    k16Bus: ramLoads=10, ramStores=11, ramBytesRead=12, ramBytesWritten=13, mmioLoads=20, mmioStores=21, mmioBytesRead=22, mmioBytesWritten=23"),
            summary,
        )
        assertTrue(
            summary.contains("    k16DecodeCache: entries=42, hits=43, misses=44"),
            summary,
        )
        assertTrue(
            summary.contains("    k16Devices: mapped=1, loads=4, stores=5, bytesRead=6, bytesWritten=7"),
            summary,
        )
        assertTrue(
            summary.contains("    k16Storage0: readCommands=8, writeCommands=9, flushes=10, bytesRead=11, bytesWritten=12, failed=13, mediaReadBlocks=14, mediaWriteBlocks=15, uniqueReadBlocks=16, repeatedReadBlocks=17, partitionTableReadBlocks=18, bootMetadataReadBlocks=19, bootDataReadBlocks=20, rootMetadataReadBlocks=21, rootDataReadBlocks=22, unknownReadBlocks=23, requestedReadBlocks=24, requestedReadBytes=12288"),
            summary,
        )
        assertTrue(
            summary.contains("    k16Os: pathLookups=31, inodeLoads=32, dirEntryScans=33, fileOpens=34, fileReads=35, statCalls=36, processSpawns=37, programLoads=38, dynamicImportLoads=39, libraryLoads=40, readDirCalls=41, programLoadBytes=42, dynamicImportBytes=43, libraryLoadBytes=44, genericFileDataReadBlocks=45, genericFileDataReadBytes=46, readDirDataReadBlocks=47, readDirDataReadBytes=48, programDataReadBlocks=49, programDataReadBytes=50, dynamicImportDataReadBlocks=51, dynamicImportDataReadBytes=52, libraryDataReadBlocks=53, libraryDataReadBytes=54, blockCacheHits=55, blockCacheMisses=56, blockCacheBatchReads=57"),
            summary,
        )
        assertTrue(
            summary.contains("      device[3]: base=8192, size=64, loads=4, stores=5, bytesRead=6, bytesWritten=7"),
            summary,
        )
        assertTrue(
            summary.contains("    k16Wait: entries=2, timerWakeups=1, inputWakeups=1, idleSkips=3"),
            summary,
        )
        assertTrue(summary.contains("  host-calls: calls="), summary)
        assertTrue(
            summary.contains("    display.blitMono5x7Packed: count=2, total=150 ns, wait=100 ns, active=50 ns, avgActive=25 ns"),
            summary,
        )
        assertTrue(summary.contains("  instructions: count="), summary)
        assertTrue(summary.contains("    CALL_BUILTIN: count=2, time=100 ns, avg=50 ns"), summary)
    }

    @Test
    fun noopCollectorKeepsEmptySnapshot() {
        val collector = NoOpRuntimeMetricsCollector

        collector.recordServerTick(nanos = 100)
        collector.recordRequestSlice(nanos = 10)
        collector.recordDisplayFrameDrain(frameCount = 3, nanos = 50)
        collector.recordDisplayFlush(frameCount = 3, nanos = 60)
        collector.recordSliceRequest()
        collector.recordVmSignal(VmSignalKind.PAUSE)
        collector.recordVmHostCallWait("display", "present", nanos = 50)
        collector.recordVmHostCall("display", "present", nanos = 80)
        collector.recordNativeWait("runtime.poll", nanos = 100)
        collector.recordNativeDisplayPumpWait(nanos = 100)
        collector.recordNativeDisplayFrameBytes(bytes = 128)
        collector.recordNativeDaemonTick(activeNanos = 100, turns = 2, halted = 1, hostRequests = 3, idle = false)
        collector.recordK16RunSlice(K16RuntimeSignal.WAIT, nanos = 100)
        collector.recordK16OutputRefresh(serialOutputBytes = 4, gpuFrameBytes = 8, gpuFrameCount = 1, nanos = 10)
        collector.recordK16StatsSnapshot(
            NativeK16ComputerStatsSnapshot(ram = NativeK16BusTraffic(loads = 1), devices = emptyList()),
        )
        collector.recordK16WaitEnter()
        collector.recordK16WaitTimerWakeup()
        collector.recordK16WaitInputWakeup()
        collector.recordK16WaitIdleSkip()
        collector.recordVmInstruction(VmInstructionKind.CALL_BUILTIN, nanos = 90)

        assertEquals(RuntimeProfilingSnapshot(), collector.snapshot())
    }
}

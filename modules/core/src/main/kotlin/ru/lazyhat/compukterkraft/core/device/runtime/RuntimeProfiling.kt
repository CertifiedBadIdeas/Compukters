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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface RuntimeMetricsCollector {
    val recordsK16StatsSnapshots: Boolean
        get() = false

    fun recordServerTick(nanos: Long)

    fun recordRequestSlice(nanos: Long)

    fun recordSliceRequest()

    fun recordNativeExecutionQuotaRefill(
        wallNanos: Long,
        serverTick: Long,
    )

    fun recordVmSignal(kind: VmSignalKind)

    fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    )

    fun recordVmHostCallWait(
        moduleName: String,
        functionName: String,
        nanos: Long,
    )

    fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    )

    fun recordNativeWait(
        kind: String,
        nanos: Long,
        woke: Boolean = true,
    )

    fun recordNativeDaemonTick(
        activeNanos: Long,
        turns: Long,
        halted: Long,
        hostRequests: Long,
        idle: Boolean,
    )

    fun recordK16RunSlice(
        signal: K16RuntimeSignal,
        nanos: Long,
        yieldSignals: Long = 0,
    )

    fun recordK16OutputRefresh(
        serialOutputBytes: Int,
        nanos: Long,
    )

    fun recordK16RetainedPayloadSent(bytes: Int)

    fun recordK16TextInput(
        byteCount: Int,
        nanos: Long,
    )

    fun recordK16StatsSnapshot(snapshot: NativeK16ComputerStatsSnapshot)

    fun recordK16WaitEnter()

    fun recordK16WaitTimerWakeup()

    fun recordK16WaitInputWakeup()

    fun recordK16WaitIdleSkip()

    fun snapshot(): RuntimeProfilingSnapshot
}

enum class K16RuntimeSignal {
    HALT,
    WAIT,
    YIELD,
    PAUSE,
}

data class RuntimeTickMetrics(
    val serverTickCalls: Long = 0,
    val serverTickNanos: Long = 0,
    val requestSliceCalls: Long = 0,
    val requestSliceNanos: Long = 0,
) {
    val allCalls = serverTickCalls + requestSliceCalls
    val allNanos = serverTickNanos + requestSliceNanos
    val tickCalls = serverTickCalls + requestSliceCalls
    val tickNanos = serverTickNanos + requestSliceNanos
}

data class RuntimeVmMetrics(
    val sliceRequests: Long = 0,
    val nativeExecutionQuotaRefills: Long = 0,
    val nativeExecutionQuotaWallNanos: Long = 0,
    val nativeExecutionQuotaLastServerTick: Long = 0,
    val haltSignals: Long = 0,
    val pauseSignals: Long = 0,
    val yieldSignals: Long = 0,
    val sleepSignals: Long = 0,
    val waitEventSignals: Long = 0,
    val waitPollSignals: Long = 0,
    val waitProcessSignals: Long = 0,
    val hostCallSignals: Long = 0,
    val nativeFastPathCalls: Long = 0,
    val nativeWaitCalls: Long = 0,
    val nativeWaitNanos: Long = 0,
    val nativeWaitWakeups: Long = 0,
    val nativeWaitTimeouts: Long = 0,
    val nativeDaemonTicks: Long = 0,
    val nativeDaemonActiveNanos: Long = 0,
    val nativeDaemonIdleTicks: Long = 0,
    val nativeDaemonTurns: Long = 0,
    val nativeDaemonHaltedProcesses: Long = 0,
    val nativeDaemonHostRequests: Long = 0,
    val k16RunSlices: Long = 0,
    val k16RunNanos: Long = 0,
    val k16RunHaltSignals: Long = 0,
    val k16RunWaitSignals: Long = 0,
    val k16RunYieldSignals: Long = 0,
    val k16RunPauseSignals: Long = 0,
    val k16OutputRefreshes: Long = 0,
    val k16OutputRefreshNanos: Long = 0,
    val k16SerialOutputSnapshots: Long = 0,
    val k16SerialOutputSnapshotBytes: Long = 0,
    val k16RetainedPayloadsSent: Long = 0,
    val k16RetainedPayloadBytesSent: Long = 0,
    val k16TextInputEvents: Long = 0,
    val k16TextInputBytes: Long = 0,
    val k16TextInputNanos: Long = 0,
    val k16WaitEntries: Long = 0,
    val k16WaitTimerWakeups: Long = 0,
    val k16WaitInputWakeups: Long = 0,
    val k16WaitIdleSkips: Long = 0,
) {
    val nativeWaitSignals: Long get() = waitPollSignals
}

data class RuntimeK16BusTrafficMetrics(
    val loads: Long = 0,
    val stores: Long = 0,
    val bytesRead: Long = 0,
    val bytesWritten: Long = 0,
)

data class RuntimeK16MmioDeviceMetrics(
    val deviceId: Long,
    val base: Long,
    val size: Long,
    val traffic: RuntimeK16BusTrafficMetrics,
    val storage: RuntimeK16StorageMetrics = RuntimeK16StorageMetrics(),
    val gpu: RuntimeK16GpuMetrics = RuntimeK16GpuMetrics(),
)

data class RuntimeK16StorageMetrics(
    val readCommands: Long = 0,
    val writeCommands: Long = 0,
    val flushCommands: Long = 0,
    val bytesRead: Long = 0,
    val bytesWritten: Long = 0,
    val failedCommands: Long = 0,
    val mediaReadBlocks: Long = 0,
    val mediaWriteBlocks: Long = 0,
    val uniqueReadBlocks: Long = 0,
    val repeatedReadBlocks: Long = 0,
    val partitionTableReadBlocks: Long = 0,
    val bootMetadataReadBlocks: Long = 0,
    val bootDataReadBlocks: Long = 0,
    val rootMetadataReadBlocks: Long = 0,
    val rootDataReadBlocks: Long = 0,
    val unknownReadBlocks: Long = 0,
    val requestedReadBlocks: Long = 0,
    val requestedReadBytes: Long = 0,
)

data class RuntimeK16GpuMetrics(
    val submissionAttempts: Long = 0,
    val committedSubmissions: Long = 0,
    val rejectedSubmissions: Long = 0,
    val submittedBytes: Long = 0,
    val resourceCount: Long = 0,
    val authoritativePayloadBytes: Long = 0,
    val viewerCount: Long = 0,
    val snapshotPayloads: Long = 0,
    val deltaPayloads: Long = 0,
    val networkPayloadBytes: Long = 0,
    val resyncRequests: Long = 0,
)

data class RuntimeK16OsMetrics(
    val pathLookups: Long = 0,
    val inodeLoads: Long = 0,
    val dirEntryScans: Long = 0,
    val fileOpens: Long = 0,
    val fileReads: Long = 0,
    val statCalls: Long = 0,
    val processSpawns: Long = 0,
    val programLoads: Long = 0,
    val dynamicImportLoads: Long = 0,
    val libraryLoads: Long = 0,
    val readDirCalls: Long = 0,
    val programLoadBytes: Long = 0,
    val dynamicImportBytes: Long = 0,
    val libraryLoadBytes: Long = 0,
    val genericFileDataReadBlocks: Long = 0,
    val genericFileDataReadBytes: Long = 0,
    val readDirDataReadBlocks: Long = 0,
    val readDirDataReadBytes: Long = 0,
    val programDataReadBlocks: Long = 0,
    val programDataReadBytes: Long = 0,
    val dynamicImportDataReadBlocks: Long = 0,
    val dynamicImportDataReadBytes: Long = 0,
    val libraryDataReadBlocks: Long = 0,
    val libraryDataReadBytes: Long = 0,
    val blockCacheHits: Long = 0,
    val blockCacheMisses: Long = 0,
    val blockCacheBatchReads: Long = 0,
    val initProgramFileDataReadBlocks: Long = 0,
    val initProgramFileDataReadBytes: Long = 0,
    val shellProgramFileDataReadBlocks: Long = 0,
    val shellProgramFileDataReadBytes: Long = 0,
    val otherProgramFileDataReadBlocks: Long = 0,
    val otherProgramFileDataReadBytes: Long = 0,
    val libkraftLibraryFileDataReadBlocks: Long = 0,
    val libkraftLibraryFileDataReadBytes: Long = 0,
    val otherLibraryFileDataReadBlocks: Long = 0,
    val otherLibraryFileDataReadBytes: Long = 0,
)

data class RuntimeK16DecodeCacheMetrics(
    val entries: Long = 0,
    val hits: Long = 0,
    val misses: Long = 0,
)

data class RuntimeK16StatsMetrics(
    val ram: RuntimeK16BusTrafficMetrics = RuntimeK16BusTrafficMetrics(),
    val mmio: RuntimeK16BusTrafficMetrics = RuntimeK16BusTrafficMetrics(),
    val os: RuntimeK16OsMetrics = RuntimeK16OsMetrics(),
    val decodeCache: RuntimeK16DecodeCacheMetrics = RuntimeK16DecodeCacheMetrics(),
    val devices: List<RuntimeK16MmioDeviceMetrics> = emptyList(),
) {
    val deviceTraffic: RuntimeK16BusTrafficMetrics =
        RuntimeK16BusTrafficMetrics(
            loads = devices.sumOf { it.traffic.loads },
            stores = devices.sumOf { it.traffic.stores },
            bytesRead = devices.sumOf { it.traffic.bytesRead },
            bytesWritten = devices.sumOf { it.traffic.bytesWritten },
        )
    val storage0: RuntimeK16StorageMetrics = storageMetricsAt(K16_STORAGE0_MMIO_BASE)
    val storage1: RuntimeK16StorageMetrics = storageMetricsAt(K16_STORAGE1_MMIO_BASE)
    val gpu: RuntimeK16GpuMetrics =
        RuntimeK16GpuMetrics(
            submissionAttempts = devices.sumOf { it.gpu.submissionAttempts },
            committedSubmissions = devices.sumOf { it.gpu.committedSubmissions },
            rejectedSubmissions = devices.sumOf { it.gpu.rejectedSubmissions },
            submittedBytes = devices.sumOf { it.gpu.submittedBytes },
            resourceCount = devices.sumOf { it.gpu.resourceCount },
            authoritativePayloadBytes = devices.sumOf { it.gpu.authoritativePayloadBytes },
            viewerCount = devices.sumOf { it.gpu.viewerCount },
            snapshotPayloads = devices.sumOf { it.gpu.snapshotPayloads },
            deltaPayloads = devices.sumOf { it.gpu.deltaPayloads },
            networkPayloadBytes = devices.sumOf { it.gpu.networkPayloadBytes },
            resyncRequests = devices.sumOf { it.gpu.resyncRequests },
        )

    private fun storageMetricsAt(base: Long): RuntimeK16StorageMetrics =
        devices.firstOrNull { it.base == base }?.storage ?: RuntimeK16StorageMetrics()

    private companion object {
        const val K16_STORAGE0_MMIO_BASE: Long = 0x1000_0400
        const val K16_STORAGE1_MMIO_BASE: Long = 0x1000_0900
    }
}

data class RuntimeHostCallMetrics(
    val moduleName: String,
    val functionName: String,
    val calls: Long,
    val nanos: Long,
    val waitNanos: Long = 0,
) {
    val averageNanos: Long get() = if (calls <= 0) 0 else nanos / calls
    val activeNanos: Long get() = (nanos - waitNanos).coerceAtLeast(0)
    val averageActiveNanos: Long get() = if (calls <= 0) 0 else activeNanos / calls
}

data class RuntimeInstructionMetrics(
    val kind: VmInstructionKind,
    val count: Long,
    val nanos: Long,
) {
    val averageNanos: Long get() = if (count <= 0) 0 else nanos / count
}

data class RuntimeProfilingSnapshot(
    val tick: RuntimeTickMetrics = RuntimeTickMetrics(),
    val vm: RuntimeVmMetrics = RuntimeVmMetrics(),
    val k16: RuntimeK16StatsMetrics = RuntimeK16StatsMetrics(),
    val hostCalls: List<RuntimeHostCallMetrics> = emptyList(),
    val instructions: List<RuntimeInstructionMetrics> = emptyList(),
) {
    fun summary(): String =
        buildString {
            appendLine("runtime:")
            appendLine("  allTicks: calls=${tick.allCalls}, time=${tick.allNanos.nanos()}")
            appendLine("  tick: calls=${tick.tickCalls}, time=${tick.tickNanos.nanos()}")
            appendLine("    server: calls=${tick.serverTickCalls}, time=${tick.serverTickNanos.nanos()}")
            appendLine("    requestSlice: calls=${tick.requestSliceCalls}, time=${tick.requestSliceNanos.nanos()}")
            appendLine("  vm:")
            appendLine("    slices: requests=${vm.sliceRequests}")
            appendLine(
                "    nativeQuota: refills=${vm.nativeExecutionQuotaRefills}, wallNanos=${vm.nativeExecutionQuotaWallNanos}, lastTick=${vm.nativeExecutionQuotaLastServerTick}",
            )
            appendLine(
                "    nativeDaemon: ticks=${vm.nativeDaemonTicks}, active=${vm.nativeDaemonActiveNanos.nanos()}, idle=${vm.nativeDaemonIdleTicks}, turns=${vm.nativeDaemonTurns}, halted=${vm.nativeDaemonHaltedProcesses}, hostRequests=${vm.nativeDaemonHostRequests}",
            )
            appendLine(
                "    k16Execution: slices=${vm.k16RunSlices}, time=${vm.k16RunNanos.nanos()}, haltSignals=${vm.k16RunHaltSignals}, waitSignals=${vm.k16RunWaitSignals}, yieldSignals=${vm.k16RunYieldSignals}, pauseSignals=${vm.k16RunPauseSignals}",
            )
            appendLine(
                "    k16Output: refreshes=${vm.k16OutputRefreshes}, time=${vm.k16OutputRefreshNanos.nanos()}",
            )
            appendLine(
                "    k16TextOutput: snapshots=${vm.k16SerialOutputSnapshots}, snapshotBytes=${vm.k16SerialOutputSnapshotBytes}",
            )
            appendLine(
                "    k16RetainedDisplaySent: payloads=${vm.k16RetainedPayloadsSent}, " +
                    "bytes=${vm.k16RetainedPayloadBytesSent}",
            )
            appendLine(
                "    k16Gpu: submissions=${k16.gpu.submissionAttempts}, committed=${k16.gpu.committedSubmissions}, " +
                    "rejected=${k16.gpu.rejectedSubmissions}, submittedBytes=${k16.gpu.submittedBytes}, " +
                    "resources=${k16.gpu.resourceCount}, authoritativeBytes=${k16.gpu.authoritativePayloadBytes}, " +
                    "viewers=${k16.gpu.viewerCount}, snapshots=${k16.gpu.snapshotPayloads}, " +
                    "deltas=${k16.gpu.deltaPayloads}, networkBytes=${k16.gpu.networkPayloadBytes}, " +
                    "resyncs=${k16.gpu.resyncRequests}",
            )
            appendLine(
                "    k16TextInput: events=${vm.k16TextInputEvents}, bytes=${vm.k16TextInputBytes}, time=${vm.k16TextInputNanos.nanos()}",
            )
            appendLine(
                "    k16Bus: ramLoads=${k16.ram.loads}, ramStores=${k16.ram.stores}, ramBytesRead=${k16.ram.bytesRead}, ramBytesWritten=${k16.ram.bytesWritten}, mmioLoads=${k16.mmio.loads}, mmioStores=${k16.mmio.stores}, mmioBytesRead=${k16.mmio.bytesRead}, mmioBytesWritten=${k16.mmio.bytesWritten}",
            )
            appendLine(
                "    k16DecodeCache: entries=${k16.decodeCache.entries}, hits=${k16.decodeCache.hits}, misses=${k16.decodeCache.misses}",
            )
            appendLine(
                "    k16Devices: mapped=${k16.devices.size}, loads=${k16.deviceTraffic.loads}, stores=${k16.deviceTraffic.stores}, bytesRead=${k16.deviceTraffic.bytesRead}, bytesWritten=${k16.deviceTraffic.bytesWritten}",
            )
            appendK16StorageSummary("k16Storage0", k16.storage0)
            appendK16StorageSummary("k16Storage1", k16.storage1)
            appendLine(
                "    k16Os: pathLookups=${k16.os.pathLookups}, inodeLoads=${k16.os.inodeLoads}, dirEntryScans=${k16.os.dirEntryScans}, fileOpens=${k16.os.fileOpens}, fileReads=${k16.os.fileReads}, statCalls=${k16.os.statCalls}, processSpawns=${k16.os.processSpawns}, programLoads=${k16.os.programLoads}, dynamicImportLoads=${k16.os.dynamicImportLoads}, libraryLoads=${k16.os.libraryLoads}, readDirCalls=${k16.os.readDirCalls}, programLoadBytes=${k16.os.programLoadBytes}, dynamicImportBytes=${k16.os.dynamicImportBytes}, libraryLoadBytes=${k16.os.libraryLoadBytes}, genericFileDataReadBlocks=${k16.os.genericFileDataReadBlocks}, genericFileDataReadBytes=${k16.os.genericFileDataReadBytes}, readDirDataReadBlocks=${k16.os.readDirDataReadBlocks}, readDirDataReadBytes=${k16.os.readDirDataReadBytes}, programDataReadBlocks=${k16.os.programDataReadBlocks}, programDataReadBytes=${k16.os.programDataReadBytes}, dynamicImportDataReadBlocks=${k16.os.dynamicImportDataReadBlocks}, dynamicImportDataReadBytes=${k16.os.dynamicImportDataReadBytes}, libraryDataReadBlocks=${k16.os.libraryDataReadBlocks}, libraryDataReadBytes=${k16.os.libraryDataReadBytes}, blockCacheHits=${k16.os.blockCacheHits}, blockCacheMisses=${k16.os.blockCacheMisses}, blockCacheBatchReads=${k16.os.blockCacheBatchReads}, initProgramFileDataReadBlocks=${k16.os.initProgramFileDataReadBlocks}, initProgramFileDataReadBytes=${k16.os.initProgramFileDataReadBytes}, shellProgramFileDataReadBlocks=${k16.os.shellProgramFileDataReadBlocks}, shellProgramFileDataReadBytes=${k16.os.shellProgramFileDataReadBytes}, otherProgramFileDataReadBlocks=${k16.os.otherProgramFileDataReadBlocks}, otherProgramFileDataReadBytes=${k16.os.otherProgramFileDataReadBytes}, libkraftLibraryFileDataReadBlocks=${k16.os.libkraftLibraryFileDataReadBlocks}, libkraftLibraryFileDataReadBytes=${k16.os.libkraftLibraryFileDataReadBytes}, otherLibraryFileDataReadBlocks=${k16.os.otherLibraryFileDataReadBlocks}, otherLibraryFileDataReadBytes=${k16.os.otherLibraryFileDataReadBytes}",
            )
            appendK16DeviceSummary()
            appendLine(
                "    k16Wait: entries=${vm.k16WaitEntries}, timerWakeups=${vm.k16WaitTimerWakeups}, inputWakeups=${vm.k16WaitInputWakeups}, idleSkips=${vm.k16WaitIdleSkips}",
            )
            appendLine(
                "  signals: halt=${vm.haltSignals}, pause=${vm.pauseSignals}, yield=${vm.yieldSignals}, sleep=${vm.sleepSignals}, waitEvent=${vm.waitEventSignals}, waitPoll=${vm.waitPollSignals}, waitProcess=${vm.waitProcessSignals}, hostCall=${vm.hostCallSignals}",
            )
            appendHostCallSummary()
            appendInstructionSummary()
        }

    private fun StringBuilder.appendK16DeviceSummary() {
        k16.devices.sortedBy { it.deviceId }.forEach { device ->
            appendLine(
                "      device[${device.deviceId}]: base=${device.base}, size=${device.size}, loads=${device.traffic.loads}, stores=${device.traffic.stores}, bytesRead=${device.traffic.bytesRead}, bytesWritten=${device.traffic.bytesWritten}",
            )
        }
    }

    private fun StringBuilder.appendK16StorageSummary(
        label: String,
        storage: RuntimeK16StorageMetrics,
    ) {
        appendLine(
            "    $label: readCommands=${storage.readCommands}, writeCommands=${storage.writeCommands}, flushes=${storage.flushCommands}, bytesRead=${storage.bytesRead}, bytesWritten=${storage.bytesWritten}, failed=${storage.failedCommands}, mediaReadBlocks=${storage.mediaReadBlocks}, mediaWriteBlocks=${storage.mediaWriteBlocks}, uniqueReadBlocks=${storage.uniqueReadBlocks}, repeatedReadBlocks=${storage.repeatedReadBlocks}, partitionTableReadBlocks=${storage.partitionTableReadBlocks}, bootMetadataReadBlocks=${storage.bootMetadataReadBlocks}, bootDataReadBlocks=${storage.bootDataReadBlocks}, rootMetadataReadBlocks=${storage.rootMetadataReadBlocks}, rootDataReadBlocks=${storage.rootDataReadBlocks}, unknownReadBlocks=${storage.unknownReadBlocks}, requestedReadBlocks=${storage.requestedReadBlocks}, requestedReadBytes=${storage.requestedReadBytes}",
        )
    }

    private fun StringBuilder.appendHostCallSummary() {
        if (hostCalls.isEmpty()) {
            appendLine("  host-calls: none")
            return
        }
        appendLine(
            "  host-calls: calls=${
                hostCalls.sumOf {
                    it.calls
                }
            }, total=${hostCalls.sumOf { it.nanos }.nanos()}, wait=${
                hostCalls.sumOf { it.waitNanos }.nanos()
            }, active=${hostCalls.sumOf { it.activeNanos }.nanos()}",
        )
        hostCalls.sortedBy { it.moduleName + it.functionName }.forEach { call ->
            appendLine(
                "    ${call.moduleName}.${call.functionName}: count=${call.calls}, total=${call.nanos.nanos()}, wait=${call.waitNanos.nanos()}, active=${call.activeNanos.nanos()}, avgActive=${call.averageActiveNanos.nanos()}",
            )
        }
    }

    private fun StringBuilder.appendInstructionSummary() {
        if (instructions.isEmpty()) {
            append("  instructions: none")
            return
        }
        appendLine(
            "  instructions: count=${
                instructions.sumOf {
                    it.count
                }
            }, time=${instructions.sumOf { it.nanos }.nanos()}, avg=${instructions.sumOf { it.averageNanos }.nanos()}",
        )
        instructions.sortedBy { it.kind }.forEachIndexed { index, instruction ->
            val line =
                "    ${instruction.kind}: count=${instruction.count}," +
                    " time=${instruction.nanos.nanos()}," +
                    " avg=${instruction.averageNanos.nanos()}"
            if (index == instructions.lastIndex) append(line) else appendLine(line)
        }
    }
}

private fun Long.nanos(): String = "$this ns"

object NoOpRuntimeMetricsCollector : RuntimeMetricsCollector {
    override fun recordServerTick(nanos: Long) = Unit

    override fun recordRequestSlice(nanos: Long) = Unit

    override fun recordSliceRequest() = Unit

    override fun recordNativeExecutionQuotaRefill(
        wallNanos: Long,
        serverTick: Long,
    ) = Unit

    override fun recordVmSignal(kind: VmSignalKind) = Unit

    override fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) = Unit

    override fun recordVmHostCallWait(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) = Unit

    override fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    ) = Unit

    override fun recordNativeWait(
        kind: String,
        nanos: Long,
        woke: Boolean,
    ) = Unit

    override fun recordNativeDaemonTick(
        activeNanos: Long,
        turns: Long,
        halted: Long,
        hostRequests: Long,
        idle: Boolean,
    ) = Unit

    override fun recordK16RunSlice(
        signal: K16RuntimeSignal,
        nanos: Long,
        yieldSignals: Long,
    ) = Unit

    override fun recordK16OutputRefresh(
        serialOutputBytes: Int,
        nanos: Long,
    ) = Unit

    override fun recordK16RetainedPayloadSent(bytes: Int) = Unit

    override fun recordK16TextInput(
        byteCount: Int,
        nanos: Long,
    ) = Unit

    override fun recordK16StatsSnapshot(snapshot: NativeK16ComputerStatsSnapshot) = Unit

    override fun recordK16WaitEnter() = Unit

    override fun recordK16WaitTimerWakeup() = Unit

    override fun recordK16WaitInputWakeup() = Unit

    override fun recordK16WaitIdleSkip() = Unit

    override fun snapshot(): RuntimeProfilingSnapshot = RuntimeProfilingSnapshot()
}

private class RuntimeCounter {
    val count = AtomicLong()
    val nanos = AtomicLong()

    fun record(nanos: Long) {
        count.incrementAndGet()
        this.nanos.addAndGet(nanos.coerceAtLeast(0))
    }

    fun recordWait(nanos: Long) {
        waitNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    val waitNanos = AtomicLong()
}

class RecordingRuntimeMetricsCollector : RuntimeMetricsCollector {
    override val recordsK16StatsSnapshots: Boolean = true

    private val serverTickCalls = AtomicLong()
    private val serverTickNanos = AtomicLong()
    private val requestSliceCalls = AtomicLong()
    private val requestSliceNanos = AtomicLong()
    private val sliceRequests = AtomicLong()
    private val nativeExecutionQuotaRefills = AtomicLong()
    private val nativeExecutionQuotaWallNanos = AtomicLong()
    private val nativeExecutionQuotaLastServerTick = AtomicLong()
    private val haltSignals = AtomicLong()
    private val pauseSignals = AtomicLong()
    private val yieldSignals = AtomicLong()
    private val sleepSignals = AtomicLong()
    private val waitEventSignals = AtomicLong()
    private val waitPollSignals = AtomicLong()
    private val waitProcessSignals = AtomicLong()
    private val hostCallSignals = AtomicLong()
    private val nativeWaitCalls = AtomicLong()
    private val nativeWaitNanos = AtomicLong()
    private val nativeWaitWakeups = AtomicLong()
    private val nativeWaitTimeouts = AtomicLong()
    private val nativeDaemonTicks = AtomicLong()
    private val nativeDaemonActiveNanos = AtomicLong()
    private val nativeDaemonIdleTicks = AtomicLong()
    private val nativeDaemonTurns = AtomicLong()
    private val nativeDaemonHaltedProcesses = AtomicLong()
    private val nativeDaemonHostRequests = AtomicLong()
    private val k16RunSlices = AtomicLong()
    private val k16RunNanos = AtomicLong()
    private val k16RunHaltSignals = AtomicLong()
    private val k16RunWaitSignals = AtomicLong()
    private val k16RunYieldSignals = AtomicLong()
    private val k16RunPauseSignals = AtomicLong()
    private val k16OutputRefreshes = AtomicLong()
    private val k16OutputRefreshNanos = AtomicLong()
    private val k16SerialOutputSnapshots = AtomicLong()
    private val k16SerialOutputSnapshotBytes = AtomicLong()
    private val k16RetainedPayloadsSent = AtomicLong()
    private val k16RetainedPayloadBytesSent = AtomicLong()
    private val k16TextInputEvents = AtomicLong()
    private val k16TextInputBytes = AtomicLong()
    private val k16TextInputNanos = AtomicLong()
    private val k16Stats = AtomicReference(RuntimeK16StatsMetrics())
    private val k16WaitEntries = AtomicLong()
    private val k16WaitTimerWakeups = AtomicLong()
    private val k16WaitInputWakeups = AtomicLong()
    private val k16WaitIdleSkips = AtomicLong()
    private val hostCalls = ConcurrentHashMap<Pair<String, String>, RuntimeCounter>()
    private val instructions = ConcurrentHashMap<VmInstructionKind, RuntimeCounter>()

    override fun recordServerTick(nanos: Long) {
        serverTickCalls.incrementAndGet()
        serverTickNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordRequestSlice(nanos: Long) {
        requestSliceCalls.incrementAndGet()
        requestSliceNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordSliceRequest() {
        sliceRequests.incrementAndGet()
    }

    override fun recordNativeExecutionQuotaRefill(
        wallNanos: Long,
        serverTick: Long,
    ) {
        nativeExecutionQuotaRefills.incrementAndGet()
        nativeExecutionQuotaWallNanos.addAndGet(wallNanos.coerceAtLeast(0))
        nativeExecutionQuotaLastServerTick.set(serverTick)
    }

    override fun recordVmSignal(kind: VmSignalKind) {
        when (kind) {
            VmSignalKind.HALT -> haltSignals.incrementAndGet()
            VmSignalKind.PAUSE -> pauseSignals.incrementAndGet()
            VmSignalKind.YIELD -> yieldSignals.incrementAndGet()
            VmSignalKind.SLEEP -> sleepSignals.incrementAndGet()
            VmSignalKind.WAIT_EVENT -> waitEventSignals.incrementAndGet()
            VmSignalKind.WAIT_POLL -> waitPollSignals.incrementAndGet()
            VmSignalKind.WAIT_PROCESS -> waitProcessSignals.incrementAndGet()
            VmSignalKind.HOST_CALL -> hostCallSignals.incrementAndGet()
        }
    }

    override fun recordVmHostCall(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) {
        hostCalls.computeIfAbsent(moduleName to functionName) { RuntimeCounter() }.record(nanos)
    }

    override fun recordVmHostCallWait(
        moduleName: String,
        functionName: String,
        nanos: Long,
    ) {
        hostCalls.computeIfAbsent(moduleName to functionName) { RuntimeCounter() }.recordWait(nanos)
    }

    override fun recordVmInstruction(
        kind: VmInstructionKind,
        nanos: Long,
    ) {
        instructions.computeIfAbsent(kind) { RuntimeCounter() }.record(nanos)
    }

    override fun recordNativeWait(
        kind: String,
        nanos: Long,
        woke: Boolean,
    ) {
        nativeWaitCalls.incrementAndGet()
        nativeWaitNanos.addAndGet(nanos.coerceAtLeast(0))
        if (woke) {
            nativeWaitWakeups.incrementAndGet()
        } else {
            nativeWaitTimeouts.incrementAndGet()
        }
    }

    override fun recordNativeDaemonTick(
        activeNanos: Long,
        turns: Long,
        halted: Long,
        hostRequests: Long,
        idle: Boolean,
    ) {
        nativeDaemonTicks.incrementAndGet()
        nativeDaemonActiveNanos.addAndGet(activeNanos.coerceAtLeast(0))
        nativeDaemonTurns.addAndGet(turns.coerceAtLeast(0))
        nativeDaemonHaltedProcesses.addAndGet(halted.coerceAtLeast(0))
        nativeDaemonHostRequests.addAndGet(hostRequests.coerceAtLeast(0))
        if (idle) nativeDaemonIdleTicks.incrementAndGet()
    }

    override fun recordK16RunSlice(
        signal: K16RuntimeSignal,
        nanos: Long,
        yieldSignals: Long,
    ) {
        k16RunSlices.incrementAndGet()
        k16RunNanos.addAndGet(nanos.coerceAtLeast(0))
        k16RunYieldSignals.addAndGet(yieldSignals.coerceAtLeast(0))
        when (signal) {
            K16RuntimeSignal.HALT -> k16RunHaltSignals.incrementAndGet()
            K16RuntimeSignal.WAIT -> k16RunWaitSignals.incrementAndGet()
            K16RuntimeSignal.YIELD -> k16RunYieldSignals.incrementAndGet()
            K16RuntimeSignal.PAUSE -> k16RunPauseSignals.incrementAndGet()
        }
    }

    override fun recordK16OutputRefresh(
        serialOutputBytes: Int,
        nanos: Long,
    ) {
        k16OutputRefreshes.incrementAndGet()
        k16OutputRefreshNanos.addAndGet(nanos.coerceAtLeast(0))
        val sanitizedSerialOutputBytes = serialOutputBytes.coerceAtLeast(0)
        if (sanitizedSerialOutputBytes > 0) {
            k16SerialOutputSnapshots.incrementAndGet()
        }
        k16SerialOutputSnapshotBytes.addAndGet(sanitizedSerialOutputBytes.toLong())
    }

    override fun recordK16RetainedPayloadSent(bytes: Int) {
        k16RetainedPayloadsSent.incrementAndGet()
        k16RetainedPayloadBytesSent.addAndGet(bytes.coerceAtLeast(0).toLong())
    }

    override fun recordK16TextInput(
        byteCount: Int,
        nanos: Long,
    ) {
        val sanitizedByteCount = byteCount.coerceAtLeast(0)
        if (sanitizedByteCount > 0) {
            k16TextInputEvents.incrementAndGet()
            k16TextInputBytes.addAndGet(sanitizedByteCount.toLong())
        }
        k16TextInputNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordK16StatsSnapshot(snapshot: NativeK16ComputerStatsSnapshot) {
        k16Stats.set(snapshot.toRuntimeMetrics())
    }

    override fun recordK16WaitEnter() {
        k16WaitEntries.incrementAndGet()
    }

    override fun recordK16WaitTimerWakeup() {
        k16WaitTimerWakeups.incrementAndGet()
    }

    override fun recordK16WaitInputWakeup() {
        k16WaitInputWakeups.incrementAndGet()
    }

    override fun recordK16WaitIdleSkip() {
        k16WaitIdleSkips.incrementAndGet()
    }

    override fun snapshot(): RuntimeProfilingSnapshot =
        RuntimeProfilingSnapshot(
            tick =
                RuntimeTickMetrics(
                    serverTickCalls = serverTickCalls.get(),
                    serverTickNanos = serverTickNanos.get(),
                    requestSliceCalls = requestSliceCalls.get(),
                    requestSliceNanos = requestSliceNanos.get(),
                ),
            vm =
                RuntimeVmMetrics(
                    sliceRequests = sliceRequests.get(),
                    nativeExecutionQuotaRefills = nativeExecutionQuotaRefills.get(),
                    nativeExecutionQuotaWallNanos = nativeExecutionQuotaWallNanos.get(),
                    nativeExecutionQuotaLastServerTick = nativeExecutionQuotaLastServerTick.get(),
                    haltSignals = haltSignals.get(),
                    pauseSignals = pauseSignals.get(),
                    yieldSignals = yieldSignals.get(),
                    sleepSignals = sleepSignals.get(),
                    waitEventSignals = waitEventSignals.get(),
                    waitPollSignals = waitPollSignals.get(),
                    waitProcessSignals = waitProcessSignals.get(),
                    hostCallSignals = hostCallSignals.get(),
                    nativeWaitCalls = nativeWaitCalls.get(),
                    nativeWaitNanos = nativeWaitNanos.get(),
                    nativeWaitWakeups = nativeWaitWakeups.get(),
                    nativeWaitTimeouts = nativeWaitTimeouts.get(),
                    nativeDaemonTicks = nativeDaemonTicks.get(),
                    nativeDaemonActiveNanos = nativeDaemonActiveNanos.get(),
                    nativeDaemonIdleTicks = nativeDaemonIdleTicks.get(),
                    nativeDaemonTurns = nativeDaemonTurns.get(),
                    nativeDaemonHaltedProcesses = nativeDaemonHaltedProcesses.get(),
                    nativeDaemonHostRequests = nativeDaemonHostRequests.get(),
                    k16RunSlices = k16RunSlices.get(),
                    k16RunNanos = k16RunNanos.get(),
                    k16RunHaltSignals = k16RunHaltSignals.get(),
                    k16RunWaitSignals = k16RunWaitSignals.get(),
                    k16RunYieldSignals = k16RunYieldSignals.get(),
                    k16RunPauseSignals = k16RunPauseSignals.get(),
                    k16OutputRefreshes = k16OutputRefreshes.get(),
                    k16OutputRefreshNanos = k16OutputRefreshNanos.get(),
                    k16SerialOutputSnapshots = k16SerialOutputSnapshots.get(),
                    k16SerialOutputSnapshotBytes = k16SerialOutputSnapshotBytes.get(),
                    k16RetainedPayloadsSent = k16RetainedPayloadsSent.get(),
                    k16RetainedPayloadBytesSent = k16RetainedPayloadBytesSent.get(),
                    k16TextInputEvents = k16TextInputEvents.get(),
                    k16TextInputBytes = k16TextInputBytes.get(),
                    k16TextInputNanos = k16TextInputNanos.get(),
                    k16WaitEntries = k16WaitEntries.get(),
                    k16WaitTimerWakeups = k16WaitTimerWakeups.get(),
                    k16WaitInputWakeups = k16WaitInputWakeups.get(),
                    k16WaitIdleSkips = k16WaitIdleSkips.get(),
                ),
            k16 = k16Stats.get(),
            hostCalls =
                hostCalls
                    .map { (key, counter) ->
                        RuntimeHostCallMetrics(
                            moduleName = key.first,
                            functionName = key.second,
                            calls = counter.count.get(),
                            nanos = counter.nanos.get(),
                            waitNanos = counter.waitNanos.get(),
                        )
                    }.sortedWith(
                        compareByDescending<RuntimeHostCallMetrics> { it.activeNanos }
                            .thenByDescending { it.nanos }
                            .thenBy { it.moduleName }
                            .thenBy { it.functionName },
                    ),
            instructions =
                instructions
                    .map { (kind, counter) ->
                        RuntimeInstructionMetrics(
                            kind = kind,
                            count = counter.count.get(),
                            nanos = counter.nanos.get(),
                        )
                    }.sortedWith(compareByDescending<RuntimeInstructionMetrics> { it.nanos }.thenBy { it.kind.name }),
        )
}

private fun NativeK16ComputerStatsSnapshot.toRuntimeMetrics(): RuntimeK16StatsMetrics =
    RuntimeK16StatsMetrics(
        ram = ram.toRuntimeMetrics(),
        mmio = mmio.toRuntimeMetrics(),
        os = os.toRuntimeMetrics(),
        decodeCache = decodeCache.toRuntimeMetrics(),
        devices = devices.map { it.toRuntimeMetrics() },
    )

private fun NativeK16DecodeCacheStats.toRuntimeMetrics(): RuntimeK16DecodeCacheMetrics =
    RuntimeK16DecodeCacheMetrics(
        entries = entries,
        hits = hits,
        misses = misses,
    )

private fun NativeK16BusTraffic.toRuntimeMetrics(): RuntimeK16BusTrafficMetrics =
    RuntimeK16BusTrafficMetrics(
        loads = loads,
        stores = stores,
        bytesRead = bytesRead,
        bytesWritten = bytesWritten,
    )

private fun NativeK16MmioDeviceStats.toRuntimeMetrics(): RuntimeK16MmioDeviceMetrics =
    RuntimeK16MmioDeviceMetrics(
        deviceId = deviceId,
        base = base,
        size = size,
        traffic = traffic.toRuntimeMetrics(),
        storage = storage.toRuntimeMetrics(),
        gpu = gpu.toRuntimeMetrics(),
    )

private fun NativeK16StorageStats.toRuntimeMetrics(): RuntimeK16StorageMetrics =
    RuntimeK16StorageMetrics(
        readCommands = readCommands,
        writeCommands = writeCommands,
        flushCommands = flushCommands,
        bytesRead = bytesRead,
        bytesWritten = bytesWritten,
        failedCommands = failedCommands,
        mediaReadBlocks = mediaReadBlocks,
        mediaWriteBlocks = mediaWriteBlocks,
        uniqueReadBlocks = uniqueReadBlocks,
        repeatedReadBlocks = repeatedReadBlocks,
        partitionTableReadBlocks = partitionTableReadBlocks,
        bootMetadataReadBlocks = bootMetadataReadBlocks,
        bootDataReadBlocks = bootDataReadBlocks,
        rootMetadataReadBlocks = rootMetadataReadBlocks,
        rootDataReadBlocks = rootDataReadBlocks,
        unknownReadBlocks = unknownReadBlocks,
        requestedReadBlocks = requestedReadBlocks,
        requestedReadBytes = requestedReadBytes,
    )

private fun NativeK16GpuStats.toRuntimeMetrics(): RuntimeK16GpuMetrics =
    RuntimeK16GpuMetrics(
        submissionAttempts = submissionAttempts,
        committedSubmissions = committedSubmissions,
        rejectedSubmissions = rejectedSubmissions,
        submittedBytes = submittedBytes,
        resourceCount = resourceCount,
        authoritativePayloadBytes = authoritativePayloadBytes,
        viewerCount = viewerCount,
        snapshotPayloads = snapshotPayloads,
        deltaPayloads = deltaPayloads,
        networkPayloadBytes = networkPayloadBytes,
        resyncRequests = resyncRequests,
    )

private fun NativeK16OsStats.toRuntimeMetrics(): RuntimeK16OsMetrics =
    RuntimeK16OsMetrics(
        pathLookups = pathLookups,
        inodeLoads = inodeLoads,
        dirEntryScans = dirEntryScans,
        fileOpens = fileOpens,
        fileReads = fileReads,
        statCalls = statCalls,
        processSpawns = processSpawns,
        programLoads = programLoads,
        dynamicImportLoads = dynamicImportLoads,
        libraryLoads = libraryLoads,
        readDirCalls = readDirCalls,
        programLoadBytes = programLoadBytes,
        dynamicImportBytes = dynamicImportBytes,
        libraryLoadBytes = libraryLoadBytes,
        genericFileDataReadBlocks = genericFileDataReadBlocks,
        genericFileDataReadBytes = genericFileDataReadBytes,
        readDirDataReadBlocks = readDirDataReadBlocks,
        readDirDataReadBytes = readDirDataReadBytes,
        programDataReadBlocks = programDataReadBlocks,
        programDataReadBytes = programDataReadBytes,
        dynamicImportDataReadBlocks = dynamicImportDataReadBlocks,
        dynamicImportDataReadBytes = dynamicImportDataReadBytes,
        libraryDataReadBlocks = libraryDataReadBlocks,
        libraryDataReadBytes = libraryDataReadBytes,
        blockCacheHits = blockCacheHits,
        blockCacheMisses = blockCacheMisses,
        blockCacheBatchReads = blockCacheBatchReads,
        initProgramFileDataReadBlocks = initProgramFileDataReadBlocks,
        initProgramFileDataReadBytes = initProgramFileDataReadBytes,
        shellProgramFileDataReadBlocks = shellProgramFileDataReadBlocks,
        shellProgramFileDataReadBytes = shellProgramFileDataReadBytes,
        otherProgramFileDataReadBlocks = otherProgramFileDataReadBlocks,
        otherProgramFileDataReadBytes = otherProgramFileDataReadBytes,
        libkraftLibraryFileDataReadBlocks = libkraftLibraryFileDataReadBlocks,
        libkraftLibraryFileDataReadBytes = libkraftLibraryFileDataReadBytes,
        otherLibraryFileDataReadBlocks = otherLibraryFileDataReadBlocks,
        otherLibraryFileDataReadBytes = otherLibraryFileDataReadBytes,
    )

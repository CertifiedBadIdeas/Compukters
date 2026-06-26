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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface RuntimeMetricsCollector {
    fun recordServerTick(nanos: Long)

    fun recordRequestSlice(nanos: Long)

    fun recordDisplayFrameDrain(
        frameCount: Int,
        nanos: Long,
    )

    fun recordDisplayFlush(
        frameCount: Int,
        nanos: Long,
    )

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

    fun recordNativeDisplayPumpWait(
        nanos: Long,
        woke: Boolean = true,
    )

    fun recordNativeDisplayFrameBytes(bytes: Int)

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
    )

    fun recordK16OutputRefresh(
        serialOutputBytes: Int,
        gpuFrameBytes: Int,
        gpuFrameCount: Int,
        nanos: Long,
    )

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
    val displayFrameDrainCalls: Long = 0,
    val displayFramesDrained: Long = 0,
    val displayFrameDrainNanos: Long = 0,
    val displayFlushCalls: Long = 0,
    val displayFramesFlushed: Long = 0,
    val displayFlushNanos: Long = 0,
) {
    val allCalls =
        serverTickCalls +
            requestSliceCalls +
            displayFrameDrainCalls +
            displayFlushCalls
    val allNanos =
        serverTickNanos +
            requestSliceNanos +
            displayFrameDrainNanos +
            displayFlushNanos
    val tickCalls = serverTickCalls + requestSliceCalls
    val tickNanos = serverTickNanos + requestSliceNanos
    val displayCalls = displayFrameDrainCalls + displayFlushCalls
    val displayNanos = displayFrameDrainNanos + displayFlushNanos
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
    val nativeDisplayPumpWaitCalls: Long = 0,
    val nativeDisplayPumpWaitNanos: Long = 0,
    val nativeDisplayPumpWakeups: Long = 0,
    val nativeDisplayPumpTimeouts: Long = 0,
    val nativeDisplayFrameByteBatches: Long = 0,
    val nativeDisplayFrameBytes: Long = 0,
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
    val k16SerialOutputSnapshotBytes: Long = 0,
    val k16GpuFrameBatches: Long = 0,
    val k16GpuFrameBytes: Long = 0,
    val k16GpuFramesDecoded: Long = 0,
    val k16WaitEntries: Long = 0,
    val k16WaitTimerWakeups: Long = 0,
    val k16WaitInputWakeups: Long = 0,
    val k16WaitIdleSkips: Long = 0,
) {
    val nativeWaitSignals: Long get() = waitPollSignals
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
            appendLine("  display-runtime: calls=${tick.displayCalls}, time=${tick.displayNanos.nanos()}")
            appendLine(
                "    drain: calls=${tick.displayFrameDrainCalls}, frames=${tick.displayFramesDrained}, time=${tick.displayFrameDrainNanos.nanos()}",
            )
            appendLine(
                "    flush: calls=${tick.displayFlushCalls}, frames=${tick.displayFramesFlushed}, time=${tick.displayFlushNanos.nanos()}",
            )
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
                "    k16Output: refreshes=${vm.k16OutputRefreshes}, time=${vm.k16OutputRefreshNanos.nanos()}, serialSnapshotBytes=${vm.k16SerialOutputSnapshotBytes}, gpuBatches=${vm.k16GpuFrameBatches}, gpuBytes=${vm.k16GpuFrameBytes}, gpuFrames=${vm.k16GpuFramesDecoded}",
            )
            appendLine(
                "    k16Wait: entries=${vm.k16WaitEntries}, timerWakeups=${vm.k16WaitTimerWakeups}, inputWakeups=${vm.k16WaitInputWakeups}, idleSkips=${vm.k16WaitIdleSkips}",
            )
            appendLine(
                "  signals: halt=${vm.haltSignals}, pause=${vm.pauseSignals}, yield=${vm.yieldSignals}, sleep=${vm.sleepSignals}, waitEvent=${vm.waitEventSignals}, waitPoll=${vm.waitPollSignals}, waitProcess=${vm.waitProcessSignals}, hostCall=${vm.hostCallSignals}",
            )
            appendLine(
                "    nativeDisplayPump: waits=${vm.nativeDisplayPumpWaitCalls}, waitTime=${vm.nativeDisplayPumpWaitNanos.nanos()}, wakeups=${vm.nativeDisplayPumpWakeups}, timeouts=${vm.nativeDisplayPumpTimeouts}, byteBatches=${vm.nativeDisplayFrameByteBatches}, bytes=${vm.nativeDisplayFrameBytes}",
            )
            appendHostCallSummary()
            appendInstructionSummary()
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

    override fun recordDisplayFrameDrain(
        frameCount: Int,
        nanos: Long,
    ) = Unit

    override fun recordDisplayFlush(
        frameCount: Int,
        nanos: Long,
    ) = Unit

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

    override fun recordNativeDisplayPumpWait(
        nanos: Long,
        woke: Boolean,
    ) = Unit

    override fun recordNativeDisplayFrameBytes(bytes: Int) = Unit

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
    ) = Unit

    override fun recordK16OutputRefresh(
        serialOutputBytes: Int,
        gpuFrameBytes: Int,
        gpuFrameCount: Int,
        nanos: Long,
    ) = Unit

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
    private val serverTickCalls = AtomicLong()
    private val serverTickNanos = AtomicLong()
    private val requestSliceCalls = AtomicLong()
    private val requestSliceNanos = AtomicLong()
    private val displayFrameDrainCalls = AtomicLong()
    private val displayFramesDrained = AtomicLong()
    private val displayFrameDrainNanos = AtomicLong()
    private val displayFlushCalls = AtomicLong()
    private val displayFramesFlushed = AtomicLong()
    private val displayFlushNanos = AtomicLong()
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
    private val nativeDisplayPumpWaitCalls = AtomicLong()
    private val nativeDisplayPumpWaitNanos = AtomicLong()
    private val nativeDisplayPumpWakeups = AtomicLong()
    private val nativeDisplayPumpTimeouts = AtomicLong()
    private val nativeDisplayFrameByteBatches = AtomicLong()
    private val nativeDisplayFrameBytes = AtomicLong()
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
    private val k16SerialOutputSnapshotBytes = AtomicLong()
    private val k16GpuFrameBatches = AtomicLong()
    private val k16GpuFrameBytes = AtomicLong()
    private val k16GpuFramesDecoded = AtomicLong()
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

    override fun recordDisplayFrameDrain(
        frameCount: Int,
        nanos: Long,
    ) {
        displayFrameDrainCalls.incrementAndGet()
        displayFramesDrained.addAndGet(frameCount.coerceAtLeast(0).toLong())
        displayFrameDrainNanos.addAndGet(nanos.coerceAtLeast(0))
    }

    override fun recordDisplayFlush(
        frameCount: Int,
        nanos: Long,
    ) {
        displayFlushCalls.incrementAndGet()
        displayFramesFlushed.addAndGet(frameCount.coerceAtLeast(0).toLong())
        displayFlushNanos.addAndGet(nanos.coerceAtLeast(0))
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

    override fun recordNativeDisplayPumpWait(
        nanos: Long,
        woke: Boolean,
    ) {
        nativeDisplayPumpWaitCalls.incrementAndGet()
        nativeDisplayPumpWaitNanos.addAndGet(nanos.coerceAtLeast(0))
        if (woke) {
            nativeDisplayPumpWakeups.incrementAndGet()
        } else {
            nativeDisplayPumpTimeouts.incrementAndGet()
        }
    }

    override fun recordNativeDisplayFrameBytes(bytes: Int) {
        nativeDisplayFrameByteBatches.incrementAndGet()
        nativeDisplayFrameBytes.addAndGet(bytes.coerceAtLeast(0).toLong())
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
    ) {
        k16RunSlices.incrementAndGet()
        k16RunNanos.addAndGet(nanos.coerceAtLeast(0))
        when (signal) {
            K16RuntimeSignal.HALT -> k16RunHaltSignals.incrementAndGet()
            K16RuntimeSignal.WAIT -> k16RunWaitSignals.incrementAndGet()
            K16RuntimeSignal.YIELD -> k16RunYieldSignals.incrementAndGet()
            K16RuntimeSignal.PAUSE -> k16RunPauseSignals.incrementAndGet()
        }
    }

    override fun recordK16OutputRefresh(
        serialOutputBytes: Int,
        gpuFrameBytes: Int,
        gpuFrameCount: Int,
        nanos: Long,
    ) {
        k16OutputRefreshes.incrementAndGet()
        k16OutputRefreshNanos.addAndGet(nanos.coerceAtLeast(0))
        k16SerialOutputSnapshotBytes.addAndGet(serialOutputBytes.coerceAtLeast(0).toLong())
        val sanitizedGpuFrameBytes = gpuFrameBytes.coerceAtLeast(0)
        val sanitizedGpuFrameCount = gpuFrameCount.coerceAtLeast(0)
        if (sanitizedGpuFrameBytes > 0 || sanitizedGpuFrameCount > 0) {
            k16GpuFrameBatches.incrementAndGet()
        }
        k16GpuFrameBytes.addAndGet(sanitizedGpuFrameBytes.toLong())
        k16GpuFramesDecoded.addAndGet(sanitizedGpuFrameCount.toLong())
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
                    displayFrameDrainCalls = displayFrameDrainCalls.get(),
                    displayFramesDrained = displayFramesDrained.get(),
                    displayFrameDrainNanos = displayFrameDrainNanos.get(),
                    displayFlushCalls = displayFlushCalls.get(),
                    displayFramesFlushed = displayFramesFlushed.get(),
                    displayFlushNanos = displayFlushNanos.get(),
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
                    nativeDisplayPumpWaitCalls = nativeDisplayPumpWaitCalls.get(),
                    nativeDisplayPumpWaitNanos = nativeDisplayPumpWaitNanos.get(),
                    nativeDisplayPumpWakeups = nativeDisplayPumpWakeups.get(),
                    nativeDisplayPumpTimeouts = nativeDisplayPumpTimeouts.get(),
                    nativeDisplayFrameByteBatches = nativeDisplayFrameByteBatches.get(),
                    nativeDisplayFrameBytes = nativeDisplayFrameBytes.get(),
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
                    k16SerialOutputSnapshotBytes = k16SerialOutputSnapshotBytes.get(),
                    k16GpuFrameBatches = k16GpuFrameBatches.get(),
                    k16GpuFrameBytes = k16GpuFrameBytes.get(),
                    k16GpuFramesDecoded = k16GpuFramesDecoded.get(),
                    k16WaitEntries = k16WaitEntries.get(),
                    k16WaitTimerWakeups = k16WaitTimerWakeups.get(),
                    k16WaitInputWakeups = k16WaitInputWakeups.get(),
                    k16WaitIdleSkips = k16WaitIdleSkips.get(),
                ),
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

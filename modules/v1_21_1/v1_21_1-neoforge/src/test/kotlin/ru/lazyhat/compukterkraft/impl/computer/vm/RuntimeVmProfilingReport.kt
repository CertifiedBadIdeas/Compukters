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

import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeHostCallMetrics
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeInstructionMetrics
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingSnapshot
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeTickMetrics
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeVmMetrics
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayFrameBuildTotals
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayFrameMetrics
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayOperationMetrics
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingSnapshot
import ru.lazyhat.compukterkraft.lang.frontend.CompilerProfilingSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.VmInstructionKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines
import kotlin.io.path.writeText

internal data class RuntimeVmProfile(
    val runtimeName: String,
    val workloads: List<RuntimeWorkloadProfile>,
)

internal data class RuntimeWorkloadProfile(
    val name: String,
    val display: DisplayProfilingSnapshot,
    val runtime: RuntimeProfilingSnapshot,
    val compiler: CompilerProfilingSnapshot,
    val heldEnter: HeldEnterWorkloadSummary? = null,
)

internal data class HeldEnterWorkloadSummary(
    val enterEventsQueued: Int,
    val settleTicks: Int,
    val maxQueuedEvents: Int,
    val finalQueuedEvents: Int,
    val maxPendingHostCalls: Int,
    val finalPendingHostCalls: Int,
    val displayFramesDrained: Int,
)

internal object RuntimeVmProfileCodec {
    fun write(
        profile: RuntimeVmProfile,
        path: Path,
    ) {
        Files.createDirectories(path.parent)
        path.writeText(
            buildString {
                appendLine("runtime\t${profile.runtimeName}")
                profile.workloads.forEach { workload ->
                    appendLine("workload\t${workload.name}")
                    workload.display.operations.run {
                        appendLine("displayOps\t$clearCalls\t$clearNanos\t$setPixelCalls\t$setPixelNanos\t$fillRectCalls\t$fillRectArea\t$fillRectNanos\t$copyRectCalls\t$copyRectArea\t$copyRectNanos\t$blitMonoCalls\t$blitMonoArea\t$blitMonoNanos\t$presentCalls\t$presentFrames\t$presentNanos")
                    }
                    workload.display.frames.run {
                        appendLine("displayFrames\t$frameCount\t$fullRefreshFrames\t$tileCount\t$payloadBytes")
                    }
                    workload.display.frameBuild.run {
                        appendLine("displayBuild\t$buildCalls\t$dirtyTileScanNanos\t$frameBuildNanos\t$tileSerializationNanos\t$frontCopyNanos\t$totalNanos\t$tileCount\t$payloadBytes")
                    }
                    workload.runtime.tick.run {
                        appendLine("runtimeTick\t$serverTickCalls\t$serverTickNanos\t$requestSliceCalls\t$requestSliceNanos\t$hostCallDrainCalls\t$hostCallsDrained\t$hostCallDrainNanos\t$hostCallDispatchCalls\t$hostCallsDispatched\t$hostCallDispatchNanos\t$hostResultDeliveryCalls\t$hostResultsDelivered\t$hostResultDeliveryNanos\t$displayFrameDrainCalls\t$displayFramesDrained\t$displayFrameDrainNanos\t$displayFlushCalls\t$displayFramesFlushed\t$displayFlushNanos")
                    }
                    workload.runtime.vm.run {
                        appendLine("runtimeVm\t$sliceRequests\t$slicePermitsSent\t$sleepGatedSliceRequests\t$slicePermitsReceived\t$schedulingPoints\t$yieldSchedulingPoints\t$waitForSliceSchedulingPoints\t$executionWindows\t$executionWindowNanos\t$haltSignals\t$pauseSignals\t$yieldSignals\t$sleepSignals\t$waitEventSignals\t$hostCallSignals")
                    }
                    workload.runtime.hostCalls.forEach { call ->
                        appendLine("host\t${call.moduleName}\t${call.functionName}\t${call.calls}\t${call.nanos}")
                    }
                    workload.runtime.instructions.forEach { instruction ->
                        appendLine("instruction\t${instruction.kind}\t${instruction.count}\t${instruction.nanos}")
                    }
                    workload.compiler.run {
                        appendLine("compiler\t$parseCalls\t$parseNanos\t$sourceBytes\t$tokens\t$analyzeCalls\t$analyzeNanos\t$diagnostics\t$symbols\t$references\t$codegenCalls\t$codegenNanos\t$functions\t$instructions\t$compileCalls\t$compileNanos\t$compiledSources")
                    }
                    workload.heldEnter?.run {
                        appendLine("held\t$enterEventsQueued\t$settleTicks\t$maxQueuedEvents\t$finalQueuedEvents\t$maxPendingHostCalls\t$finalPendingHostCalls\t$displayFramesDrained")
                    }
                    appendLine("endWorkload")
                }
            },
        )
    }

    fun read(path: Path): RuntimeVmProfile {
        var runtimeName: String? = null
        val workloads = mutableListOf<WorkloadBuilder>()
        var current: WorkloadBuilder? = null

        path.readLines().forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "runtime", "runner" -> runtimeName = parts[1]
                "workload" -> current = WorkloadBuilder(parts[1])
                "displayOps" -> current.requireCurrent().operations = parts.longs().let { v -> DisplayOperationMetrics(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15]) }
                "displayFrames" -> current.requireCurrent().frames = parts.longs().let { v -> DisplayFrameMetrics(v[0], v[1], v[2], v[3]) }
                "displayBuild" -> current.requireCurrent().frameBuild = parts.longs().let { v -> DisplayFrameBuildTotals(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]) }
                "runtimeTick" -> current.requireCurrent().tick = parts.longs().let { v -> RuntimeTickMetrics(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15], v[16], v[17], v[18]) }
                "runtimeVm" -> current.requireCurrent().vm = parts.longs().let { v -> RuntimeVmMetrics(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14]) }
                "host" -> current.requireCurrent().hostCalls += RuntimeHostCallMetrics(parts[1], parts[2], parts[3].toLong(), parts[4].toLong())
                "instruction" -> current.requireCurrent().instructions += RuntimeInstructionMetrics(VmInstructionKind.valueOf(parts[1]), parts[2].toLong(), parts[3].toLong())
                "compiler" -> current.requireCurrent().compiler = parts.longs().let { v -> CompilerProfilingSnapshot(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15]) }
                "held" -> current.requireCurrent().heldEnter = parts.ints().let { v -> HeldEnterWorkloadSummary(v[0], v[1], v[2], v[3], v[4], v[5], v[6]) }
                "endWorkload" -> {
                    workloads += current.requireCurrent()
                    current = null
                }
            }
        }

        return RuntimeVmProfile(
            runtimeName = runtimeName ?: error("Missing runtime line in $path"),
            workloads = workloads.map { it.build() },
        )
    }

    private data class WorkloadBuilder(
        val name: String,
        var operations: DisplayOperationMetrics? = null,
        var frames: DisplayFrameMetrics? = null,
        var frameBuild: DisplayFrameBuildTotals? = null,
        var tick: RuntimeTickMetrics? = null,
        var vm: RuntimeVmMetrics? = null,
        val hostCalls: MutableList<RuntimeHostCallMetrics> = mutableListOf(),
        val instructions: MutableList<RuntimeInstructionMetrics> = mutableListOf(),
        var compiler: CompilerProfilingSnapshot? = null,
        var heldEnter: HeldEnterWorkloadSummary? = null,
    ) {
        fun build(): RuntimeWorkloadProfile =
            RuntimeWorkloadProfile(
                name = name,
                display = DisplayProfilingSnapshot(operations ?: error("Missing displayOps for $name"), frames ?: error("Missing displayFrames for $name"), frameBuild ?: error("Missing displayBuild for $name")),
                runtime = RuntimeProfilingSnapshot(tick ?: error("Missing runtimeTick for $name"), vm ?: error("Missing runtimeVm for $name"), hostCalls.toList(), instructions.toList()),
                compiler = compiler ?: error("Missing compiler for $name"),
                heldEnter = heldEnter,
            )
    }

    private fun WorkloadBuilder?.requireCurrent(): WorkloadBuilder = this ?: error("Profile line appeared before workload")

    private fun List<String>.longs(): List<Long> = drop(1).map(String::toLong)

    private fun List<String>.ints(): List<Int> = drop(1).map(String::toInt)
}

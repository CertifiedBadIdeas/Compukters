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

internal data class VmRunnerProfile(
    val runnerName: String,
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
        profile: VmRunnerProfile,
        path: Path,
    ) {
        Files.createDirectories(path.parent)
        path.writeText(
            buildString {
                appendLine("runner\t${profile.runnerName}")
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

    fun read(path: Path): VmRunnerProfile {
        var runnerName: String? = null
        val workloads = mutableListOf<WorkloadBuilder>()
        var current: WorkloadBuilder? = null

        path.readLines().forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "runner" -> runnerName = parts[1]
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

        return VmRunnerProfile(
            runnerName = runnerName ?: error("Missing runner line in $path"),
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

internal object RuntimeVmProfilingReportFormatter {
    fun markdown(
        jvm: VmRunnerProfile,
        rust: VmRunnerProfile,
    ): String =
        buildString {
            appendLine("# Runtime VM Profiling Comparison")
            appendLine()
            appendLine("Generated by `profileRuntimeVmComparison`.")
            appendLine()
            appendLine("- JVM runner: `${jvm.runnerName}`")
            appendLine("- Rust runner: `${rust.runnerName}`")
            appendLine()

            jvm.workloads.forEach { jvmWorkload ->
                val rustWorkload = rust.workloads.firstOrNull { it.name == jvmWorkload.name }
                if (rustWorkload != null) {
                    appendWorkload(jvmWorkload, rustWorkload)
                }
            }

            appendLine("## Notes")
            appendLine()
            appendLine("- JVM and Rust profiles are collected by separate Gradle Test tasks with explicit runner system properties and a short per-task warm-up, but these workloads are still integration diagnostics, not strict microbenchmarks.")
            appendLine("- Compiler timings use the same Kotlin compiler for both VM runners and are sensitive to JVM warm-up/order effects; treat them as startup context, not Rust-vs-JVM VM speed.")
            if (rust.workloads.any { it.runtime.instructions.isEmpty() }) {
                appendLine("- Rust instruction metrics are currently unavailable, so opcode-level JVM/Rust comparisons are omitted for Rust runs.")
            }
            appendLine("- Host-call timings may include coroutine wait time for blocking APIs such as IPC reads and event polling.")
        }.trimEnd() + "\n"

    private fun StringBuilder.appendWorkload(
        jvm: RuntimeWorkloadProfile,
        rust: RuntimeWorkloadProfile,
    ) {
        appendLine("## ${jvm.name}")
        appendLine()
        appendLine("| Metric | JVM | Rust | Rust/JVM |")
        appendLine("|---|---:|---:|---:|")
        appendMetric("Display operations", jvm.display.operations.allCalls, rust.display.operations.allCalls)
        appendMetric("Display operation time", jvm.display.operations.allNanos, rust.display.operations.allNanos, ::formatNanos)
        appendMetric("Frames emitted", jvm.display.frames.frameCount, rust.display.frames.frameCount)
        appendMetric("Tiles emitted", jvm.display.frames.tileCount, rust.display.frames.tileCount)
        appendMetric("Frame build time", jvm.display.frameBuild.totalNanos, rust.display.frameBuild.totalNanos, ::formatNanos)
        appendMetric("Runtime all ticks", jvm.runtime.tick.allNanos, rust.runtime.tick.allNanos, ::formatNanos)
        appendMetric("VM execution time", jvm.runtime.vm.executionWindowNanos, rust.runtime.vm.executionWindowNanos, ::formatNanos)
        appendMetric("VM execution windows", jvm.runtime.vm.executionWindows, rust.runtime.vm.executionWindows)
        appendMetric("Host-call signals", jvm.runtime.vm.hostCallSignals, rust.runtime.vm.hostCallSignals)
        appendMetric("Host calls", jvm.runtime.hostCalls.sumOf { it.calls }, rust.runtime.hostCalls.sumOf { it.calls })
        appendMetric("Host-call time", jvm.runtime.hostCalls.sumOf { it.nanos }, rust.runtime.hostCalls.sumOf { it.nanos }, ::formatNanos)
        appendInstructionMetric("Instructions", jvm.runtime.instructions.sumOf { it.count }, rust.runtime.instructions.sumOf { it.count })
        appendInstructionMetric("Instruction time", jvm.runtime.instructions.sumOf { it.nanos }, rust.runtime.instructions.sumOf { it.nanos }, ::formatNanos)
        appendMetric("Compiler time", jvm.compiler.compileNanos, rust.compiler.compileNanos, ::formatNanos)
        appendLine()

        progressWarning(jvm, rust)?.let { warning ->
            appendLine("> $warning")
            appendLine()
        }

        appendLine("### Host calls")
        appendLine()
        appendLine("| Host call | JVM calls | JVM time | Rust calls | Rust time | Rust/JVM time |")
        appendLine("|---|---:|---:|---:|---:|---:|")
        val jvmCalls = jvm.runtime.hostCalls.associateBy { it.key }
        val rustCalls = rust.runtime.hostCalls.associateBy { it.key }
        (jvmCalls.keys + rustCalls.keys).sorted().forEach { key ->
            val jvmCall = jvmCalls[key]
            val rustCall = rustCalls[key]
            appendLine(
                "| $key | ${jvmCall?.calls ?: 0} | ${formatNanos(jvmCall?.nanos ?: 0)} | " +
                    "${rustCall?.calls ?: 0} | ${formatNanos(rustCall?.nanos ?: 0)} | ${ratio(jvmCall?.nanos ?: 0, rustCall?.nanos ?: 0)} |",
            )
        }
        appendLine()

        if (jvm.heldEnter != null || rust.heldEnter != null) {
            appendLine("### Held Enter")
            appendLine()
            appendLine("| Metric | JVM | Rust | Rust/JVM |")
            appendLine("|---|---:|---:|---:|")
            appendMetric("Accepted Enter events", (jvm.heldEnter?.enterEventsQueued ?: 0).toLong(), (rust.heldEnter?.enterEventsQueued ?: 0).toLong())
            appendMetric("Final queued events", (jvm.heldEnter?.finalQueuedEvents ?: 0).toLong(), (rust.heldEnter?.finalQueuedEvents ?: 0).toLong())
            appendMetric("Max queued events", (jvm.heldEnter?.maxQueuedEvents ?: 0).toLong(), (rust.heldEnter?.maxQueuedEvents ?: 0).toLong())
            appendMetric("Frames drained", (jvm.heldEnter?.displayFramesDrained ?: 0).toLong(), (rust.heldEnter?.displayFramesDrained ?: 0).toLong())
            appendLine()
        }
    }

    private fun StringBuilder.appendMetric(
        name: String,
        jvm: Long,
        rust: Long,
        format: (Long) -> String = Long::toString,
    ) {
        appendLine("| $name | ${format(jvm)} | ${format(rust)} | ${ratio(jvm, rust)} |")
    }

    private fun StringBuilder.appendInstructionMetric(
        name: String,
        jvm: Long,
        rust: Long,
        format: (Long) -> String = Long::toString,
    ) {
        if (jvm > 0 && rust == 0L) {
            appendLine("| $name | ${format(jvm)} | unavailable | — |")
        } else {
            appendMetric(name, jvm, rust, format)
        }
    }

    private fun progressWarning(
        jvm: RuntimeWorkloadProfile,
        rust: RuntimeWorkloadProfile,
    ): String? {
        val frameRatio = comparableRatio(jvm.display.frames.frameCount, rust.display.frames.frameCount)
        val hostCallRatio = comparableRatio(jvm.runtime.hostCalls.sumOf { it.calls }, rust.runtime.hostCalls.sumOf { it.calls })
        val warnings =
            buildList {
                if (frameRatio != null && frameRatio !in 0.90..1.10) {
                    add("frames JVM=${jvm.display.frames.frameCount}, Rust=${rust.display.frames.frameCount}")
                }
                if (hostCallRatio != null && hostCallRatio !in 0.90..1.10) {
                    add("host calls JVM=${jvm.runtime.hostCalls.sumOf { it.calls }}, Rust=${rust.runtime.hostCalls.sumOf { it.calls }}")
                }
            }
        if (warnings.isEmpty()) return null
        return "Progress differs for this workload (${warnings.joinToString()}); timing ratios may not represent equal completed work."
    }

    private fun comparableRatio(
        jvm: Long,
        rust: Long,
    ): Double? = if (jvm <= 0) null else rust.toDouble() / jvm.toDouble()

    private val RuntimeHostCallMetrics.key: String get() = "$moduleName.$functionName"

    private fun ratio(
        jvm: Long,
        rust: Long,
    ): String = if (jvm <= 0) "—" else "%.2fx".format(rust.toDouble() / jvm.toDouble())

    private fun formatNanos(nanos: Long): String = "$nanos ns"
}

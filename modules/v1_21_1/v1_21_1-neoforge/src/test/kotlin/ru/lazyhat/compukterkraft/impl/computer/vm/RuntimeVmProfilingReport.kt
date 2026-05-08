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
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal data class RuntimeVmProfile(
    val runtimeName: String,
    val workloads: List<RuntimeWorkloadProfile>,
)

internal data class RuntimeVmProfileRun(
    val metadata: RuntimeVmProfileRunMetadata,
    val profile: RuntimeVmProfile,
)

internal data class RuntimeVmProfileRunMetadata(
    val timestamp: String,
    val runtimeName: String,
    val gitCommit: String? = null,
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
                        appendLine(
                            "displayOps\t$clearCalls\t$clearNanos\t$setPixelCalls\t$setPixelNanos\t$fillRectCalls\t$fillRectArea\t$fillRectNanos\t$copyRectCalls\t$copyRectArea\t$copyRectNanos\t$blitMonoCalls\t$blitMonoArea\t$blitMonoNanos\t$presentCalls\t$presentFrames\t$presentNanos",
                        )
                    }
                    workload.display.frames.run {
                        appendLine("displayFrames\t$frameCount\t$fullRefreshFrames\t$tileCount\t$payloadBytes")
                    }
                    workload.display.frameBuild.run {
                        appendLine(
                            "displayBuild\t$buildCalls\t$dirtyTileScanNanos\t$frameBuildNanos\t$tileSerializationNanos\t$frontCopyNanos\t$totalNanos\t$tileCount\t$payloadBytes",
                        )
                    }
                    workload.runtime.tick.run {
                        appendLine(
                            "runtimeTick\t$serverTickCalls\t$serverTickNanos\t$requestSliceCalls\t$requestSliceNanos\t$hostCallDrainCalls\t$hostCallsDrained\t$hostCallDrainNanos\t$hostCallDispatchCalls\t$hostCallsDispatched\t$hostCallDispatchNanos\t$hostResultDeliveryCalls\t$hostResultsDelivered\t$hostResultDeliveryNanos\t$displayFrameDrainCalls\t$displayFramesDrained\t$displayFrameDrainNanos\t$displayFlushCalls\t$displayFramesFlushed\t$displayFlushNanos",
                        )
                    }
                    workload.runtime.vm.run {
                        appendLine(
                            "runtimeVm\t$sliceRequests\t$slicePermitsSent\t$sleepGatedSliceRequests\t$slicePermitsReceived\t$schedulingPoints\t$yieldSchedulingPoints\t$waitForSliceSchedulingPoints\t$executionWindows\t$executionWindowNanos\t$haltSignals\t$pauseSignals\t$yieldSignals\t$sleepSignals\t$waitEventSignals\t$hostCallSignals",
                        )
                    }
                    workload.runtime.hostCalls.forEach { call ->
                        appendLine("host\t${call.moduleName}\t${call.functionName}\t${call.calls}\t${call.nanos}")
                    }
                    workload.runtime.instructions.forEach { instruction ->
                        appendLine("instruction\t${instruction.kind}\t${instruction.count}\t${instruction.nanos}")
                    }
                    workload.compiler.run {
                        appendLine(
                            "compiler\t$parseCalls\t$parseNanos\t$sourceBytes\t$tokens\t$analyzeCalls\t$analyzeNanos\t$diagnostics\t$symbols\t$references\t$codegenCalls\t$codegenNanos\t$functions\t$instructions\t$compileCalls\t$compileNanos\t$compiledSources",
                        )
                    }
                    workload.heldEnter?.run {
                        appendLine(
                            "held\t$enterEventsQueued\t$settleTicks\t$maxQueuedEvents\t$finalQueuedEvents\t$maxPendingHostCalls\t$finalPendingHostCalls\t$displayFramesDrained",
                        )
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
                "runtime", "runner" -> {
                    runtimeName = parts[1]
                }

                "workload" -> {
                    current = WorkloadBuilder(parts[1])
                }

                "displayOps" -> {
                    current.requireCurrent().operations =
                        parts.longs().let { v ->
                            DisplayOperationMetrics(
                                v[0],
                                v[1],
                                v[2],
                                v[3],
                                v[4],
                                v[5],
                                v[6],
                                v[7],
                                v[8],
                                v[9],
                                v[10],
                                v[11],
                                v[12],
                                v[13],
                                v[14],
                                v[15],
                            )
                        }
                }

                "displayFrames" -> {
                    current.requireCurrent().frames =
                        parts.longs().let { v -> DisplayFrameMetrics(v[0], v[1], v[2], v[3]) }
                }

                "displayBuild" -> {
                    current.requireCurrent().frameBuild =
                        parts
                            .longs()
                            .let { v -> DisplayFrameBuildTotals(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]) }
                }

                "runtimeTick" -> {
                    current.requireCurrent().tick =
                        parts.longs().let { v ->
                            RuntimeTickMetrics(
                                v[0],
                                v[1],
                                v[2],
                                v[3],
                                v[4],
                                v[5],
                                v[6],
                                v[7],
                                v[8],
                                v[9],
                                v[10],
                                v[11],
                                v[12],
                                v[13],
                                v[14],
                                v[15],
                                v[16],
                                v[17],
                                v[18],
                            )
                        }
                }

                "runtimeVm" -> {
                    current.requireCurrent().vm =
                        parts.longs().let { v ->
                            RuntimeVmMetrics(
                                v[0],
                                v[1],
                                v[2],
                                v[3],
                                v[4],
                                v[5],
                                v[6],
                                v[7],
                                v[8],
                                v[9],
                                v[10],
                                v[11],
                                v[12],
                                v[13],
                                v[14],
                            )
                        }
                }

                "host" -> {
                    current.requireCurrent().hostCalls +=
                        RuntimeHostCallMetrics(parts[1], parts[2], parts[3].toLong(), parts[4].toLong())
                }

                "instruction" -> {
                    current.requireCurrent().instructions +=
                        RuntimeInstructionMetrics(
                            VmInstructionKind.valueOf(parts[1]),
                            parts[2].toLong(),
                            parts[3].toLong(),
                        )
                }

                "compiler" -> {
                    current.requireCurrent().compiler =
                        parts.longs().let { v ->
                            CompilerProfilingSnapshot(
                                v[0],
                                v[1],
                                v[2],
                                v[3],
                                v[4],
                                v[5],
                                v[6],
                                v[7],
                                v[8],
                                v[9],
                                v[10],
                                v[11],
                                v[12],
                                v[13],
                                v[14],
                                v[15],
                            )
                        }
                }

                "held" -> {
                    current.requireCurrent().heldEnter =
                        parts.ints().let { v -> HeldEnterWorkloadSummary(v[0], v[1], v[2], v[3], v[4], v[5], v[6]) }
                }

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
                display =
                    DisplayProfilingSnapshot(
                        operations ?: error("Missing displayOps for $name"),
                        frames ?: error("Missing displayFrames for $name"),
                        frameBuild ?: error("Missing displayBuild for $name"),
                    ),
                runtime =
                    RuntimeProfilingSnapshot(
                        tick ?: error("Missing runtimeTick for $name"),
                        vm ?: error("Missing runtimeVm for $name"),
                        hostCalls.toList(),
                        instructions.toList(),
                    ),
                compiler = compiler ?: error("Missing compiler for $name"),
                heldEnter = heldEnter,
            )
    }

    private fun WorkloadBuilder?.requireCurrent(): WorkloadBuilder = this ?: error("Profile line appeared before workload")

    private fun List<String>.longs(): List<Long> = drop(1).map(String::toLong)

    private fun List<String>.ints(): List<Int> = drop(1).map(String::toInt)
}

internal object RuntimeVmProfilingReportArchive {
    const val PROFILE_FILE_NAME = "runtime-vm-image.tsv"
    const val MARKDOWN_FILE_NAME = "runtime-vm-image.md"
    private const val METADATA_FILE_NAME = "metadata.properties"
    private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ssXXX")

    fun currentTimestamp(clock: Clock = Clock.systemDefaultZone()): String =
        OffsetDateTime.now(clock).format(timestampFormatter).replace(':', '-')

    fun writeRun(
        profile: RuntimeVmProfile,
        stableProfilePath: Path,
        runsDir: Path,
        metadata: RuntimeVmProfileRunMetadata,
    ): RuntimeVmProfileRun {
        Files.createDirectories(stableProfilePath.parent)
        RuntimeVmProfileCodec.write(profile, stableProfilePath)

        val run = RuntimeVmProfileRun(metadata.copy(runtimeName = profile.runtimeName), profile)
        val runDir = runsDir.resolve(run.metadata.timestamp)
        Files.createDirectories(runDir)
        RuntimeVmProfileCodec.write(profile, runDir.resolve(PROFILE_FILE_NAME))
        runDir.resolve(MARKDOWN_FILE_NAME).writeText(RuntimeVmProfilingReportFormatter.runMarkdown(run))
        writeMetadata(run.metadata, runDir.resolve(METADATA_FILE_NAME))
        return run
    }

    fun readRuns(runsDir: Path): List<RuntimeVmProfileRun> {
        if (!Files.exists(runsDir)) return emptyList()
        return Files.list(runsDir).use { stream ->
            stream
                .filter { it.isDirectory() }
                .map { runDir ->
                    val profile = RuntimeVmProfileCodec.read(runDir.resolve(PROFILE_FILE_NAME))
                    RuntimeVmProfileRun(
                        metadata = readMetadata(runDir.resolve(METADATA_FILE_NAME), runDir.name, profile.runtimeName),
                        profile = profile,
                    )
                }.sorted { left, right -> left.metadata.timestamp.compareTo(right.metadata.timestamp) }
                .toList()
        }
    }

    fun writeHistoricalReport(
        runsDir: Path,
        reportPath: Path,
    ): List<RuntimeVmProfileRun> {
        val runs = readRuns(runsDir)
        Files.createDirectories(reportPath.parent)
        reportPath.writeText(RuntimeVmProfilingReportFormatter.historicalMarkdown(runs))
        return runs
    }

    private fun writeMetadata(
        metadata: RuntimeVmProfileRunMetadata,
        path: Path,
    ) {
        path.writeText(
            buildString {
                appendLine("timestamp=${metadata.timestamp}")
                appendLine("runtimeName=${metadata.runtimeName}")
                metadata.gitCommit?.let { appendLine("gitCommit=$it") }
            },
        )
    }

    private fun readMetadata(
        path: Path,
        fallbackTimestamp: String,
        fallbackRuntimeName: String,
    ): RuntimeVmProfileRunMetadata {
        if (!Files.exists(path)) {
            return RuntimeVmProfileRunMetadata(fallbackTimestamp, fallbackRuntimeName)
        }
        val values =
            path
                .readText()
                .lineSequence()
                .mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index < 0) null else line.substring(0, index) to line.substring(index + 1)
                }.toMap()
        return RuntimeVmProfileRunMetadata(
            timestamp = values["timestamp"] ?: fallbackTimestamp,
            runtimeName = values["runtimeName"] ?: fallbackRuntimeName,
            gitCommit = values["gitCommit"],
        )
    }
}

internal object RuntimeVmProfilingReportFormatter {
    fun runMarkdown(run: RuntimeVmProfileRun): String =
        buildString {
            appendLine("# Runtime VM Profiling Report")
            appendLine()
            appendLine("- Timestamp: `${run.metadata.timestamp}`")
            appendLine("- Runtime: `${run.profile.runtimeName}`")
            run.metadata.gitCommit?.let { appendLine("- Git commit: `$it`") }
            appendLine()
            run.profile.workloads.forEach { workload ->
                appendRunWorkload(workload)
            }
        }.trimEnd() + "\n"

    fun historicalMarkdown(runs: List<RuntimeVmProfileRun>): String =
        buildString {
            val orderedRuns = runs.sortedByDescending { it.metadata.timestamp }
            appendLine("# Runtime VM Profiling History")
            appendLine()
            appendLine("| Timestamp | Runtime | Commit | Workloads |")
            appendLine("|---|---|---|---:|")
            orderedRuns.forEach { run ->
                appendLine(
                    "| ${run.metadata.timestamp} | ${run.profile.runtimeName} | ${run.metadata.gitCommit ?: ""} | ${run.profile.workloads.size} |",
                )
            }
            appendLine()

            val workloadNames = orderedRuns.flatMap { run -> run.profile.workloads.map { it.name } }.distinct().sorted()
            workloadNames.forEach { workloadName ->
                appendLine("## $workloadName")
                appendLine()
                appendHistoricalWorkloadTable(orderedRuns, workloadName)
            }
        }.trimEnd() + "\n"

    private fun StringBuilder.appendRunWorkload(workload: RuntimeWorkloadProfile) {
        appendLine("## ${workload.name}")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|---|---:|")
        appendLine("| Display operations | ${workload.display.operations.allCalls} |")
        appendLine("| Display operation time | ${formatNanos(workload.display.operations.allNanos)} |")
        appendLine("| Frames emitted | ${workload.display.frames.frameCount} |")
        appendLine("| Frame build time | ${formatNanos(workload.display.frameBuild.totalNanos)} |")
        appendLine("| Runtime all ticks | ${formatNanos(workload.runtime.tick.allNanos)} |")
        appendLine("| VM execution time | ${formatNanos(workload.runtime.vm.executionWindowNanos)} |")
        appendLine("| Host-call signals | ${workload.runtime.vm.hostCallSignals} |")
        appendLine("| Host calls | ${workload.runtime.hostCalls.sumOf { it.calls }} |")
        appendLine("| Host-call time | ${formatNanos(workload.runtime.hostCalls.sumOf { it.nanos })} |")
        appendLine("| Compiler time | ${formatNanos(workload.compiler.compileNanos)} |")
        workload.heldEnter?.let { held ->
            appendLine("| Held Enter accepted events | ${held.enterEventsQueued} |")
            appendLine("| Held Enter max queued events | ${held.maxQueuedEvents} |")
        }
        appendLine()
        appendHostCalls(workload.runtime.hostCalls)
    }

    private fun StringBuilder.appendHistoricalWorkloadTable(
        runs: List<RuntimeVmProfileRun>,
        workloadName: String,
    ) {
        val columns = runs.map { run -> run.metadata.timestamp to run.profile.workloads.firstOrNull { it.name == workloadName } }
        val hostCallKeys =
            columns
                .flatMap { (_, workload) -> workload?.runtime?.hostCalls.orEmpty().map { it.key } }
                .distinct()
                .sorted()

        appendLine("| Metric | ${columns.joinToString(" | ") { (timestamp, _) -> timestamp }} |")
        appendLine("|---|${columns.joinToString("|") { "---:" }}|")
        appendHistoricalMetricRow("Display operations", columns) { workload -> workload.display.operations.allCalls.toString() }
        appendHistoricalMetricRow("Display operation time", columns) { workload -> formatNanos(workload.display.operations.allNanos) }
        appendHistoricalMetricRow("Frames emitted", columns) { workload -> workload.display.frames.frameCount.toString() }
        appendHistoricalMetricRow("Frame build time", columns) { workload -> formatNanos(workload.display.frameBuild.totalNanos) }
        appendHistoricalMetricRow("Runtime all ticks", columns) { workload -> formatNanos(workload.runtime.tick.allNanos) }
        appendHistoricalMetricRow("VM execution time", columns) { workload -> formatNanos(workload.runtime.vm.executionWindowNanos) }
        appendHistoricalMetricRow("Host-call signals", columns) { workload -> workload.runtime.vm.hostCallSignals.toString() }
        appendHistoricalMetricRow("Host calls", columns) { workload -> workload.runtime.hostCalls.sumOf { it.calls }.toString() }
        appendHistoricalMetricRow("Host-call time", columns) { workload -> formatNanos(workload.runtime.hostCalls.sumOf { it.nanos }) }
        appendHistoricalMetricRow("Compiler time", columns) { workload -> formatNanos(workload.compiler.compileNanos) }
        if (columns.any { (_, workload) -> workload?.heldEnter != null }) {
            appendHistoricalMetricRow("Held Enter accepted events", columns) { workload -> workload.heldEnter?.enterEventsQueued?.toString() ?: "" }
            appendHistoricalMetricRow("Held Enter max queued events", columns) { workload -> workload.heldEnter?.maxQueuedEvents?.toString() ?: "" }
        }
        hostCallKeys.forEach { key ->
            appendHistoricalMetricRow("host $key", columns) { workload ->
                val call = workload.runtime.hostCalls.firstOrNull { it.key == key }
                if (call == null) "0 calls / 0 ns" else "${call.calls} calls / ${formatNanos(call.nanos)}"
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendHistoricalMetricRow(
        name: String,
        columns: List<Pair<String, RuntimeWorkloadProfile?>>,
        value: (RuntimeWorkloadProfile) -> String,
    ) {
        appendLine("| $name | ${columns.joinToString(" | ") { (_, workload) -> workload?.let(value) ?: "" }} |")
    }

    private fun StringBuilder.appendHostCalls(hostCalls: List<RuntimeHostCallMetrics>) {
        appendLine("### Host calls")
        appendLine()
        appendLine("| Host call | Calls | Time |")
        appendLine("|---|---:|---:|")
        hostCalls.sortedWith(compareByDescending<RuntimeHostCallMetrics> { it.nanos }.thenBy { it.key }).forEach { call ->
            appendLine("| ${call.key} | ${call.calls} | ${formatNanos(call.nanos)} |")
        }
        if (hostCalls.isEmpty()) {
            appendLine("| none | 0 | 0 ns |")
        }
        appendLine()
    }

    private val RuntimeHostCallMetrics.key: String get() = "$moduleName.$functionName"

    private fun formatNanos(nanos: Long): String = "$nanos ns"
}

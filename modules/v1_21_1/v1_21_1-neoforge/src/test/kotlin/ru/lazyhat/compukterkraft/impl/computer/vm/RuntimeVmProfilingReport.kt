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

import ru.lazyhat.compukterkraft.common.computer.client.ClientDisplayProfilingSnapshot
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
import java.util.Locale
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
    val client: ClientDisplayProfilingSnapshot = ClientDisplayProfilingSnapshot(),
    val runtime: RuntimeProfilingSnapshot,
    val compiler: CompilerProfilingSnapshot,
    val heldEnter: HeldEnterWorkloadSummary? = null,
    val enterAutoscroll: EnterAutoscrollWorkloadSummary? = null,
    val pipeline: TerminalPipelineSummary? = null,
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

internal data class EnterAutoscrollWorkloadSummary(
    val enterEventsQueued: Int,
    val ticksUntilFirstAutoscroll: Int,
    val copyRectCallsBefore: Long,
    val copyRectCallsAfter: Long,
    val displayFramesDrained: Int,
    val clientFramesApplied: Long,
)

internal data class TerminalPipelineSummary(
    val inputChars: Int,
    val inputPhaseNanos: Long,
    val inputClientFrames: Long,
    val enterPhaseNanos: Long,
    val enterClientFrames: Long,
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
                    workload.client.run {
                        appendLine(
                            "clientDisplay\t$framesReceived\t$framesApplied\t$rejectedFrames\t$fullRefreshFrames\t$tilesApplied\t$payloadBytes\t$applyNanos\t$swapCalls\t$dirtySwaps\t$swapNanos\t$snapshotsCopied\t$snapshotRegions\t$snapshotPixels\t$snapshotCopyNanos",
                        )
                    }
                    workload.runtime.tick.run {
                        appendLine(
                            "runtimeTick\t$serverTickCalls\t$serverTickNanos\t$requestSliceCalls\t$requestSliceNanos\t$hostCallDrainCalls\t$hostCallsDrained\t$hostCallDrainNanos\t$hostCallDispatchCalls\t$hostCallsDispatched\t$hostCallDispatchNanos\t$hostResultDeliveryCalls\t$hostResultsDelivered\t$hostResultDeliveryNanos\t$displayFrameDrainCalls\t$displayFramesDrained\t$displayFrameDrainNanos\t$displayFlushCalls\t$displayFramesFlushed\t$displayFlushNanos",
                        )
                    }
                    workload.runtime.vm.run {
                        appendLine(
                            "runtimeVm\t$sliceRequests\t$slicePermitsSent\t$sleepGatedSliceRequests\t$slicePermitsReceived\t$schedulingPoints\t$yieldSchedulingPoints\t$waitForSliceSchedulingPoints\t$executionWindows\t$executionWindowNanos\t$haltSignals\t$pauseSignals\t$yieldSignals\t$sleepSignals\t$waitEventSignals\t$waitPollSignals\t$hostCallSignals\t$nativeFastPathCalls",
                        )
                    }
                    workload.runtime.hostCalls.forEach { call ->
                        appendLine("host\t${call.moduleName}\t${call.functionName}\t${call.calls}\t${call.nanos}\t${call.waitNanos}")
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
                    workload.enterAutoscroll?.run {
                        appendLine(
                            "enterAutoscroll\t$enterEventsQueued\t$ticksUntilFirstAutoscroll\t$copyRectCallsBefore\t$copyRectCallsAfter\t$displayFramesDrained\t$clientFramesApplied",
                        )
                    }
                    workload.pipeline?.run {
                        appendLine(
                            "pipeline\t$inputChars\t$inputPhaseNanos\t$inputClientFrames\t$enterPhaseNanos\t$enterClientFrames",
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

                "clientDisplay" -> {
                    current.requireCurrent().client =
                        parts.longs().let { v ->
                            ClientDisplayProfilingSnapshot(
                                framesReceived = v[0],
                                framesApplied = v[1],
                                rejectedFrames = v[2],
                                fullRefreshFrames = v[3],
                                tilesApplied = v[4],
                                payloadBytes = v[5],
                                applyNanos = v[6],
                                swapCalls = v[7],
                                dirtySwaps = v[8],
                                swapNanos = v[9],
                                snapshotsCopied = v[10],
                                snapshotRegions = v[11],
                                snapshotPixels = v[12],
                                snapshotCopyNanos = v[13],
                            )
                        }
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
                            val legacyHostCallSignals = v.getOrElse(14) { 0 }
                            RuntimeVmMetrics(
                                sliceRequests = v.getOrElse(0) { 0 },
                                slicePermitsSent = v.getOrElse(1) { 0 },
                                sleepGatedSliceRequests = v.getOrElse(2) { 0 },
                                slicePermitsReceived = v.getOrElse(3) { 0 },
                                schedulingPoints = v.getOrElse(4) { 0 },
                                yieldSchedulingPoints = v.getOrElse(5) { 0 },
                                waitForSliceSchedulingPoints = v.getOrElse(6) { 0 },
                                executionWindows = v.getOrElse(7) { 0 },
                                executionWindowNanos = v.getOrElse(8) { 0 },
                                haltSignals = v.getOrElse(9) { 0 },
                                pauseSignals = v.getOrElse(10) { 0 },
                                yieldSignals = v.getOrElse(11) { 0 },
                                sleepSignals = v.getOrElse(12) { 0 },
                                waitEventSignals = v.getOrElse(13) { 0 },
                                waitPollSignals = if (v.size >= 16) v[14] else 0,
                                hostCallSignals = if (v.size >= 16) v[15] else legacyHostCallSignals,
                                nativeFastPathCalls = if (v.size >= 17) v[16] else 0,
                            )
                        }
                }

                "host" -> {
                    current.requireCurrent().hostCalls +=
                        RuntimeHostCallMetrics(
                            parts[1],
                            parts[2],
                            parts[3].toLong(),
                            parts[4].toLong(),
                            waitNanos = parts.getOrNull(5)?.toLong() ?: 0,
                        )
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

                "enterAutoscroll" -> {
                    current.requireCurrent().enterAutoscroll =
                        parts.drop(1).let { v ->
                            EnterAutoscrollWorkloadSummary(
                                enterEventsQueued = v[0].toInt(),
                                ticksUntilFirstAutoscroll = v[1].toInt(),
                                copyRectCallsBefore = v[2].toLong(),
                                copyRectCallsAfter = v[3].toLong(),
                                displayFramesDrained = v[4].toInt(),
                                clientFramesApplied = v[5].toLong(),
                            )
                        }
                }

                "pipeline" -> {
                    val values = parts.drop(1)
                    current.requireCurrent().pipeline =
                        TerminalPipelineSummary(
                            inputChars = values[0].toInt(),
                            inputPhaseNanos = values[1].toLong(),
                            inputClientFrames = values[2].toLong(),
                            enterPhaseNanos = values[3].toLong(),
                            enterClientFrames = values[4].toLong(),
                        )
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
        var client: ClientDisplayProfilingSnapshot = ClientDisplayProfilingSnapshot(),
        var tick: RuntimeTickMetrics? = null,
        var vm: RuntimeVmMetrics? = null,
        val hostCalls: MutableList<RuntimeHostCallMetrics> = mutableListOf(),
        val instructions: MutableList<RuntimeInstructionMetrics> = mutableListOf(),
        var compiler: CompilerProfilingSnapshot? = null,
        var heldEnter: HeldEnterWorkloadSummary? = null,
        var enterAutoscroll: EnterAutoscrollWorkloadSummary? = null,
        var pipeline: TerminalPipelineSummary? = null,
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
                client = client,
                runtime =
                    RuntimeProfilingSnapshot(
                        tick ?: error("Missing runtimeTick for $name"),
                        vm ?: error("Missing runtimeVm for $name"),
                        hostCalls.toList(),
                        instructions.toList(),
                    ),
                compiler = compiler ?: error("Missing compiler for $name"),
                heldEnter = heldEnter,
                enterAutoscroll = enterAutoscroll,
                pipeline = pipeline,
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
        appendLine("| Client frames applied | ${workload.client.framesApplied} |")
        appendLine("| Client apply time | ${formatNanos(workload.client.applyNanos)} |")
        appendLine("| Client swap time | ${formatNanos(workload.client.swapNanos)} |")
        appendLine("| Client snapshot time | ${formatNanos(workload.client.snapshotCopyNanos)} |")
        appendLine("| Client snapshot pixels | ${workload.client.snapshotPixels} |")
        appendLine("| Runtime all ticks | ${formatNanos(workload.runtime.tick.allNanos)} |")
        appendLine("| VM execution time | ${formatNanos(workload.runtime.vm.executionWindowNanos)} |")
        appendLine("| Native wait signals | ${workload.runtime.vm.nativeWaitSignals} |")
        appendLine("| Native fast-path calls | ${workload.runtime.vm.nativeFastPathCalls} |")
        appendLine("| Host-call signals | ${workload.runtime.vm.hostCallSignals} |")
        appendLine("| Host calls | ${workload.runtime.hostCalls.sumOf { it.calls }} |")
        appendLine("| Host-call time | ${formatNanos(workload.runtime.hostCalls.sumOf { it.nanos })} |")
        appendLine("| Host-call wait time | ${formatNanos(workload.runtime.hostCalls.sumOf { it.waitNanos })} |")
        appendLine("| Host-call active time | ${formatNanos(workload.runtime.hostCalls.sumOf { it.activeNanos })} |")
        appendLine("| Compiler time | ${formatNanos(workload.compiler.compileNanos)} |")
        workload.heldEnter?.let { held ->
            appendLine("| Held Enter accepted events | ${held.enterEventsQueued} |")
            appendLine("| Held Enter max queued events | ${held.maxQueuedEvents} |")
        }
        workload.enterAutoscroll?.let { autoscroll ->
            appendLine("| Enter autoscroll accepted events | ${autoscroll.enterEventsQueued} |")
            appendLine("| Enter autoscroll ticks until first scroll | ${autoscroll.ticksUntilFirstAutoscroll} |")
            appendLine("| Enter autoscroll copyRect calls before | ${autoscroll.copyRectCallsBefore} |")
            appendLine("| Enter autoscroll copyRect calls after | ${autoscroll.copyRectCallsAfter} |")
            appendLine("| Enter autoscroll display frames drained | ${autoscroll.displayFramesDrained} |")
            appendLine("| Enter autoscroll client frames applied | ${autoscroll.clientFramesApplied} |")
        }
        workload.pipeline?.let { pipeline ->
            appendLine("| Input chars | ${pipeline.inputChars} |")
            appendLine("| Input phase to client | ${formatNanos(pipeline.inputPhaseNanos)} |")
            appendLine("| Input client frames | ${pipeline.inputClientFrames} |")
            appendLine("| Enter phase to client | ${formatNanos(pipeline.enterPhaseNanos)} |")
            appendLine("| Enter client frames | ${pipeline.enterClientFrames} |")
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
                .sortedWith(
                    compareByDescending<String> { key ->
                        columns.maxOf { (_, workload) ->
                            workload
                                ?.runtime
                                ?.hostCalls
                                ?.firstOrNull { it.key == key }
                                ?.nanos ?: 0L
                        }
                    }.thenBy { it },
                )

        appendLine("| Metric | ${columns.joinToString(" | ") { (timestamp, _) -> timestamp }} |")
        appendLine("|---|${columns.joinToString("|") { "---:" }}|")
        appendHistoricalMetricRow("Display operations", columns) { workload -> workload.display.operations.allCalls.toString() }
        appendHistoricalMetricRow("Display operation time", columns) { workload -> formatNanos(workload.display.operations.allNanos) }
        appendHistoricalMetricRow("Frames emitted", columns) { workload -> workload.display.frames.frameCount.toString() }
        appendHistoricalMetricRow("Frame build time", columns) { workload -> formatNanos(workload.display.frameBuild.totalNanos) }
        appendHistoricalMetricRow("Client frames applied", columns) { workload -> workload.client.framesApplied.toString() }
        appendHistoricalMetricRow("Client apply time", columns) { workload -> formatNanos(workload.client.applyNanos) }
        appendHistoricalMetricRow("Client swap time", columns) { workload -> formatNanos(workload.client.swapNanos) }
        appendHistoricalMetricRow("Client snapshot time", columns) { workload -> formatNanos(workload.client.snapshotCopyNanos) }
        appendHistoricalMetricRow("Client snapshot pixels", columns) { workload -> workload.client.snapshotPixels.toString() }
        appendHistoricalMetricRow("Runtime all ticks", columns) { workload -> formatNanos(workload.runtime.tick.allNanos) }
        appendHistoricalMetricRow("VM execution time", columns) { workload -> formatNanos(workload.runtime.vm.executionWindowNanos) }
        appendHistoricalMetricRow("Native wait signals", columns) { workload -> workload.runtime.vm.nativeWaitSignals.toString() }
        appendHistoricalMetricRow("Native fast-path calls", columns) { workload -> workload.runtime.vm.nativeFastPathCalls.toString() }
        appendHistoricalMetricRow("Host-call signals", columns) { workload -> workload.runtime.vm.hostCallSignals.toString() }
        appendHistoricalMetricRow("Host calls", columns) { workload -> workload.runtime.hostCalls.sumOf { it.calls }.toString() }
        appendHistoricalMetricRow("Host-call time", columns) { workload -> formatNanos(workload.runtime.hostCalls.sumOf { it.nanos }) }
        appendHistoricalMetricRow(
            "Host-call wait time",
            columns,
        ) { workload -> formatNanos(workload.runtime.hostCalls.sumOf { it.waitNanos }) }
        appendHistoricalMetricRow(
            "Host-call active time",
            columns,
        ) { workload -> formatNanos(workload.runtime.hostCalls.sumOf { it.activeNanos }) }
        appendHistoricalMetricRow("Compiler time", columns) { workload -> formatNanos(workload.compiler.compileNanos) }
        if (columns.any { (_, workload) -> workload?.heldEnter != null }) {
            appendHistoricalMetricRow("Held Enter accepted events", columns) { workload -> workload.heldEnter?.enterEventsQueued?.toString() ?: "" }
            appendHistoricalMetricRow("Held Enter max queued events", columns) { workload -> workload.heldEnter?.maxQueuedEvents?.toString() ?: "" }
        }
        if (columns.any { (_, workload) -> workload?.enterAutoscroll != null }) {
            appendHistoricalMetricRow(
                "Enter autoscroll accepted events",
                columns,
            ) { workload -> workload.enterAutoscroll?.enterEventsQueued?.toString() ?: "" }
            appendHistoricalMetricRow(
                "Enter autoscroll ticks until first scroll",
                columns,
            ) { workload -> workload.enterAutoscroll?.ticksUntilFirstAutoscroll?.toString() ?: "" }
            appendHistoricalMetricRow(
                "Enter autoscroll copyRect calls before",
                columns,
            ) { workload -> workload.enterAutoscroll?.copyRectCallsBefore?.toString() ?: "" }
            appendHistoricalMetricRow(
                "Enter autoscroll copyRect calls after",
                columns,
            ) { workload -> workload.enterAutoscroll?.copyRectCallsAfter?.toString() ?: "" }
            appendHistoricalMetricRow(
                "Enter autoscroll display frames drained",
                columns,
            ) { workload -> workload.enterAutoscroll?.displayFramesDrained?.toString() ?: "" }
            appendHistoricalMetricRow(
                "Enter autoscroll client frames applied",
                columns,
            ) { workload -> workload.enterAutoscroll?.clientFramesApplied?.toString() ?: "" }
        }
        if (columns.any { (_, workload) -> workload?.pipeline != null }) {
            appendHistoricalMetricRow("Input chars", columns) { workload -> workload.pipeline?.inputChars?.toString() ?: "" }
            appendHistoricalMetricRow("Input phase to client", columns) { workload -> workload.pipeline?.inputPhaseNanos?.let(::formatNanos) ?: "" }
            appendHistoricalMetricRow("Input client frames", columns) { workload -> workload.pipeline?.inputClientFrames?.toString() ?: "" }
            appendHistoricalMetricRow("Enter phase to client", columns) { workload -> workload.pipeline?.enterPhaseNanos?.let(::formatNanos) ?: "" }
            appendHistoricalMetricRow("Enter client frames", columns) { workload -> workload.pipeline?.enterClientFrames?.toString() ?: "" }
        }
        hostCallKeys.forEach { key ->
            appendHistoricalMetricRow("host $key calls", columns) { workload ->
                workload.runtime.hostCalls
                    .firstOrNull { it.key == key }
                    ?.calls
                    ?.toString() ?: "0"
            }
            appendHistoricalMetricRow("host $key active", columns) { workload ->
                formatNanos(
                    workload.runtime.hostCalls
                        .firstOrNull { it.key == key }
                        ?.activeNanos ?: 0,
                )
            }
            appendHistoricalMetricRow("host $key wait", columns) { workload ->
                formatNanos(workload.runtime.hostCalls
                    .firstOrNull { it.key == key }?.waitNanos ?: 0)
            }
            appendHistoricalMetricRow("host $key total", columns) { workload ->
                formatNanos(workload.runtime.hostCalls.firstOrNull { it.key == key }?.nanos ?: 0)
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
        appendLine("| Host call | Calls | Total | Wait | Active |")
        appendLine("|---|---:|---:|---:|---:|")
        hostCalls
            .sortedWith(
                compareByDescending<RuntimeHostCallMetrics> { it.activeNanos }
                    .thenByDescending { it.nanos }
                    .thenBy { it.key },
            ).forEach { call ->
                appendLine(
                    "| ${call.key} | ${call.calls} | ${formatNanos(call.nanos)} | ${formatNanos(call.waitNanos)} | ${
                        formatNanos(
                            call.activeNanos,
                        )
                    } |"
                )
            }
        if (hostCalls.isEmpty()) {
            appendLine("| none | 0 | 0 ns | 0 ns | 0 ns |")
        }
        appendLine()
    }

    private val RuntimeHostCallMetrics.key: String get() = "$moduleName.$functionName"

    private fun formatNanos(nanos: Long): String =
        when {
            nanos < 1_000L -> "$nanos ns"
            nanos < 1_000_000L -> "${formatScaledNanos(nanos, 1_000.0)} us"
            nanos < 1_000_000_000L -> "${formatScaledNanos(nanos, 1_000_000.0)} ms"
            else -> "${formatScaledNanos(nanos, 1_000_000_000.0)} s"
        }

    private fun formatScaledNanos(
        nanos: Long,
        scale: Double,
    ): String =
        String
            .format(Locale.ROOT, "%.2f", nanos / scale)
            .trimEnd('0')
            .trimEnd('.')
}

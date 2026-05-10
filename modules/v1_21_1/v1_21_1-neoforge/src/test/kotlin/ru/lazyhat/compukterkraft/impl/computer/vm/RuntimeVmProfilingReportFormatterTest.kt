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
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeProfilingSnapshot
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeTickMetrics
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeVmMetrics
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayFrameBuildTotals
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayFrameMetrics
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayOperationMetrics
import ru.lazyhat.compukterkraft.core.device.vm.display.DisplayProfilingSnapshot
import ru.lazyhat.compukterkraft.lang.frontend.CompilerProfilingSnapshot
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeVmProfilingReportFormatterTest {
    @Test
    fun historicalMarkdownContainsAllRunsWorkloadsAndHostCalls() {
        val first =
            profileRun(
                timestamp = "2026-05-08T14-00-00+03-00",
                workloads =
                    listOf(
                        workload(
                            name = "bundled terminal",
                            runtimeNanos = 100_000,
                            executionNanos = 40,
                            waitPollSignals = 3,
                            waitProcessSignals = 1,
                            nativeProcessRegistrations = 1,
                            nativeProcessCompletions = 1,
                            nativeWaitCalls = 4,
                            nativeWaitNanos = 55_000,
                            nativeWaitWakeups = 3,
                            nativeWaitTimeouts = 1,
                            hostCallSignals = 10,
                            hostCalls =
                                listOf(
                                    RuntimeHostCallMetrics("strings", "length", calls = 5, nanos = 50_000),
                                    RuntimeHostCallMetrics("ipc", "tryRead", calls = 1, nanos = 11),
                                ),
                        ),
                    ),
            )
        val second =
            profileRun(
                timestamp = "2026-05-08T14-05-00+03-00",
                workloads =
                    listOf(
                        workload(
                            name = "bundled terminal",
                            runtimeNanos = 1_500_000,
                            executionNanos = 20,
                            waitPollSignals = 7,
                            waitProcessSignals = 2,
                            nativeProcessRegistrations = 2,
                            nativeProcessCompletions = 2,
                            nativeWaitCalls = 8,
                            nativeWaitNanos = 2_000_000,
                            nativeWaitWakeups = 6,
                            nativeWaitTimeouts = 2,
                            nativeDisplayPumpWaitCalls = 11,
                            nativeDisplayPumpWaitNanos = 3_000_000,
                            nativeDisplayPumpWakeups = 9,
                            nativeDisplayPumpTimeouts = 2,
                            nativeDisplayFrameByteBatches = 5,
                            nativeDisplayFrameBytes = 4096,
                            processSchedulerTicks = 12,
                            processSchedulerSelectedTicks = 10,
                            processSchedulerIdleTicks = 2,
                            processSchedulerWokenProcesses = 3,
                            nativeProcessSchedulerComparisons = 12,
                            nativeProcessSchedulerMatches = 11,
                            nativeProcessSchedulerMismatches = 1,
                            nativeProcessSchedulerAcceptedTicks = 11,
                            nativeProcessSchedulerFallbackTicks = 1,
                            nativeExecutionQuotaRefills = 12,
                            nativeExecutionQuotaInstructions = 3_852,
                            nativeExecutionQuotaWallNanos = 7_848,
                            nativeExecutionQuotaLastServerTick = 42,
                            nativeSchedulerDryRuns = 12,
                            nativeSchedulerDryRunTurns = 30,
                            nativeSchedulerDryRunSelectedPids = 28,
                            nativeSchedulerDryRunRemainingInstructions = 6,
                            nativeSchedulerDryRunFirstSelectionMatches = 11,
                            nativeSchedulerDryRunFirstSelectionMismatches = 1,
                            hostCallSignals = 2,
                            hostCalls =
                                listOf(
                                    RuntimeHostCallMetrics(
                                        "display",
                                        "present",
                                        calls = 3,
                                        nanos = 90_000_000,
                                        waitNanos = 60_000_000,
                                    ),
                            ),
                                ),
                        workload(name = "new workload", runtimeNanos = 7, executionNanos = 3, hostCallSignals = 1),
                    ),
            )

        val markdown = RuntimeVmProfilingReportFormatter.historicalMarkdown(listOf(first, second))

        assertTrue(markdown.contains("# Runtime VM Profiling History"), markdown)
        assertTrue(markdown.contains("## bundled terminal"), markdown)
        assertTrue(markdown.contains("## new workload"), markdown)
        assertTrue(
            markdown.contains("| Metric | 2026-05-08T14-05-00+03-00 | 2026-05-08T14-00-00+03-00 |"),
            markdown,
        )
        assertTrue(markdown.contains("| Runtime all ticks | 1.5 ms | 100 us |"), markdown)
        assertTrue(markdown.contains("| VM execution time | 20 ns | 40 ns |"), markdown)
        assertTrue(markdown.contains("| Native wait signals | 7 | 3 |"), markdown)
        assertTrue(markdown.contains("| Native process wait signals | 2 | 1 |"), markdown)
        assertTrue(markdown.contains("| Native process registrations | 2 | 1 |"), markdown)
        assertTrue(markdown.contains("| Native process completions | 2 | 1 |"), markdown)
        assertTrue(markdown.contains("| Native process stale completions | 0 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native fast-path calls | 0 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native wait calls | 8 | 4 |"), markdown)
        assertTrue(markdown.contains("| Native wait time | 2 ms | 55 us |"), markdown)
        assertTrue(markdown.contains("| Native wait wakeups | 6 | 3 |"), markdown)
        assertTrue(markdown.contains("| Native wait timeouts | 2 | 1 |"), markdown)
        assertTrue(markdown.contains("| Native display pump wait calls | 11 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native display pump wait time | 3 ms | 0 ns |"), markdown)
        assertTrue(markdown.contains("| Native display pump wakeups | 9 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native display pump timeouts | 2 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native display frame byte batches | 5 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native display frame bytes | 4096 | 0 |"), markdown)
        assertTrue(markdown.contains("| Process scheduler ticks | 12 | 0 |"), markdown)
        assertTrue(markdown.contains("| Process scheduler selected ticks | 10 | 0 |"), markdown)
        assertTrue(markdown.contains("| Process scheduler idle ticks | 2 | 0 |"), markdown)
        assertTrue(markdown.contains("| Process scheduler woken processes | 3 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler comparisons | 12 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler matches | 11 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler mismatches | 1 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler accepted ticks | 11 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler fallback ticks | 1 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native execution quota refills | 12 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native execution quota instructions | 3852 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native execution quota wall time | 7.85 us | 0 ns |"), markdown)
        assertTrue(markdown.contains("| Native execution quota last tick | 42 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run calls | 12 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run turns | 30 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run selected pids | 28 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run remaining instructions | 6 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run first-selection matches | 11 | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run first-selection mismatches | 1 | 0 |"), markdown)
        assertTrue(markdown.contains("| Host-call signals | 2 | 10 |"), markdown)
        assertTrue(markdown.contains("| Host-call active time | 30 ms | 50.01 us |"), markdown)
        assertTrue(markdown.contains("| Host-call wait time | 60 ms | 0 ns |"), markdown)
        assertTrue(
            markdown.contains("| host display.present calls | 3 | 0 |"),
            markdown,
        )
        assertTrue(markdown.contains("| host display.present active | 30 ms | 0 ns |"), markdown)
        assertTrue(markdown.contains("| host display.present wait | 60 ms | 0 ns |"), markdown)
        assertTrue(markdown.contains("| host display.present total | 90 ms | 0 ns |"), markdown)
        assertTrue(
            markdown.contains("| host strings.length calls | 0 | 5 |"),
            markdown,
        )
        assertTrue(markdown.contains("| host strings.length active | 0 ns | 50 us |"), markdown)
        assertTrue(markdown.contains("| host strings.length wait | 0 ns | 0 ns |"), markdown)
        assertTrue(markdown.contains("| host strings.length total | 0 ns | 50 us |"), markdown)
        assertTrue(markdown.contains("| host ipc.tryRead calls | 0 | 1 |"), markdown)
        assertTrue(markdown.contains("| host ipc.tryRead active | 0 ns | 11 ns |"), markdown)
        assertTrue(markdown.contains("| host ipc.tryRead wait | 0 ns | 0 ns |"), markdown)
        assertTrue(markdown.contains("| host ipc.tryRead total | 0 ns | 11 ns |"), markdown)
        assertTrue(
            markdown.indexOf("| host display.present calls |") <
                markdown.indexOf("| host strings.length calls |") &&
                markdown.indexOf("| host strings.length calls |") <
                markdown.indexOf("| host ipc.tryRead calls |"),
            markdown,
        )
        assertTrue(markdown.contains("| Client frames applied | 2 | 2 |"), markdown)
        assertTrue(markdown.contains("| Client apply time | 12 ns | 12 ns |"), markdown)
        assertTrue(markdown.contains("| Input phase to client | 44 ns | 44 ns |"), markdown)
        assertTrue(markdown.contains("| Enter autoscroll accepted events | 9 | 9 |"), markdown)
        assertTrue(markdown.contains("| Enter autoscroll ticks until first scroll | 10 | 10 |"), markdown)
    }

    @Test
    fun runMarkdownContainsEveryWorkloadFromProfile() {
        val run =
            profileRun(
                timestamp = "2026-05-08T14-00-00+03-00",
                workloads =
                    listOf(
                        workload("sustained terminal no-delay", runtimeNanos = 100, executionNanos = 40, hostCallSignals = 4),
                        workload(
                            "held Enter backlog",
                            runtimeNanos = 80,
                            executionNanos = 20,
                            waitPollSignals = 6,
                            waitProcessSignals = 3,
                            nativeProcessRegistrations = 4,
                            nativeProcessCompletions = 3,
                            nativeProcessStaleCompletions = 1,
                            nativeWaitCalls = 5,
                            nativeWaitNanos = 700_000,
                            nativeWaitWakeups = 4,
                            nativeWaitTimeouts = 1,
                            nativeDisplayPumpWaitCalls = 6,
                            nativeDisplayPumpWaitNanos = 800_000,
                            nativeDisplayPumpWakeups = 5,
                            nativeDisplayPumpTimeouts = 1,
                            nativeDisplayFrameByteBatches = 4,
                            nativeDisplayFrameBytes = 2048,
                            processSchedulerTicks = 15,
                            processSchedulerSelectedTicks = 14,
                            processSchedulerIdleTicks = 1,
                            processSchedulerWokenProcesses = 6,
                            nativeProcessSchedulerComparisons = 15,
                            nativeProcessSchedulerMatches = 15,
                            nativeProcessSchedulerMismatches = 0,
                            nativeProcessSchedulerAcceptedTicks = 15,
                            nativeProcessSchedulerFallbackTicks = 0,
                            nativeExecutionQuotaRefills = 16,
                            nativeExecutionQuotaInstructions = 5_136,
                            nativeExecutionQuotaWallNanos = 10_464,
                            nativeExecutionQuotaLastServerTick = 43,
                            nativeSchedulerDryRuns = 16,
                            nativeSchedulerDryRunTurns = 40,
                            nativeSchedulerDryRunSelectedPids = 37,
                            nativeSchedulerDryRunRemainingInstructions = 8,
                            nativeSchedulerDryRunFirstSelectionMatches = 15,
                            nativeSchedulerDryRunFirstSelectionMismatches = 1,
                            hostCallSignals = 2,
                        ),
                    ),
            )

        val markdown = RuntimeVmProfilingReportFormatter.runMarkdown(run)

        assertTrue(markdown.contains("# Runtime VM Profiling Report"), markdown)
        assertTrue(markdown.contains("sustained terminal no-delay"), markdown)
        assertTrue(markdown.contains("held Enter backlog"), markdown)
        assertTrue(markdown.contains("| Native wait signals | 6 |"), markdown)
        assertTrue(markdown.contains("| Native process wait signals | 3 |"), markdown)
        assertTrue(markdown.contains("| Native process registrations | 4 |"), markdown)
        assertTrue(markdown.contains("| Native process completions | 3 |"), markdown)
        assertTrue(markdown.contains("| Native process stale completions | 1 |"), markdown)
        assertTrue(markdown.contains("| Native fast-path calls | 0 |"), markdown)
        assertTrue(markdown.contains("| Native wait calls | 5 |"), markdown)
        assertTrue(markdown.contains("| Native wait time | 700 us |"), markdown)
        assertTrue(markdown.contains("| Native wait wakeups | 4 |"), markdown)
        assertTrue(markdown.contains("| Native wait timeouts | 1 |"), markdown)
        assertTrue(markdown.contains("| Native display pump wait calls | 6 |"), markdown)
        assertTrue(markdown.contains("| Native display pump wait time | 800 us |"), markdown)
        assertTrue(markdown.contains("| Native display pump wakeups | 5 |"), markdown)
        assertTrue(markdown.contains("| Native display pump timeouts | 1 |"), markdown)
        assertTrue(markdown.contains("| Native display frame byte batches | 4 |"), markdown)
        assertTrue(markdown.contains("| Native display frame bytes | 2048 |"), markdown)
        assertTrue(markdown.contains("| Process scheduler ticks | 15 |"), markdown)
        assertTrue(markdown.contains("| Process scheduler selected ticks | 14 |"), markdown)
        assertTrue(markdown.contains("| Process scheduler idle ticks | 1 |"), markdown)
        assertTrue(markdown.contains("| Process scheduler woken processes | 6 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler comparisons | 15 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler matches | 15 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler mismatches | 0 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler accepted ticks | 15 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler fallback ticks | 0 |"), markdown)
        assertTrue(markdown.contains("| Native execution quota refills | 16 |"), markdown)
        assertTrue(markdown.contains("| Native execution quota instructions | 5136 |"), markdown)
        assertTrue(markdown.contains("| Native execution quota wall time | 10.46 us |"), markdown)
        assertTrue(markdown.contains("| Native execution quota last tick | 43 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run calls | 16 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run turns | 40 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run selected pids | 37 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run remaining instructions | 8 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run first-selection matches | 15 |"), markdown)
        assertTrue(markdown.contains("| Native scheduler dry-run first-selection mismatches | 1 |"), markdown)
        assertTrue(markdown.contains("| Host-call signals | 4 |"), markdown)
        assertTrue(markdown.contains("| Host-call active time | 0 ns |"), markdown)
        assertTrue(markdown.contains("| Host-call wait time | 0 ns |"), markdown)
        assertTrue(markdown.contains("| Client frames applied | 2 |"), markdown)
        assertTrue(markdown.contains("| Client snapshot pixels | 32 |"), markdown)
        assertTrue(markdown.contains("| Enter phase to client | 1.5 s |"), markdown)
        assertTrue(markdown.contains("| Enter autoscroll copyRect calls before | 11 |"), markdown)
        assertTrue(markdown.contains("| Enter autoscroll copyRect calls after | 12 |"), markdown)
    }

    private fun profileRun(
        timestamp: String,
        workloads: List<RuntimeWorkloadProfile>,
    ): RuntimeVmProfileRun =
        RuntimeVmProfileRun(
            metadata =
                RuntimeVmProfileRunMetadata(
                    timestamp = timestamp,
                    runtimeName = "Rust image",
                    gitCommit = "abc1234",
                ),
            profile =
                RuntimeVmProfile(
                    runtimeName = "Rust image",
                    workloads = workloads,
                ),
        )

    private fun workload(
        name: String,
        runtimeNanos: Long,
        executionNanos: Long,
        waitPollSignals: Long = 0,
        waitProcessSignals: Long = 0,
        nativeProcessRegistrations: Long = 0,
        nativeProcessCompletions: Long = 0,
        nativeProcessStaleCompletions: Long = 0,
        nativeWaitCalls: Long = 0,
        nativeWaitNanos: Long = 0,
        nativeWaitWakeups: Long = 0,
        nativeWaitTimeouts: Long = 0,
        nativeDisplayPumpWaitCalls: Long = 0,
        nativeDisplayPumpWaitNanos: Long = 0,
        nativeDisplayPumpWakeups: Long = 0,
        nativeDisplayPumpTimeouts: Long = 0,
        nativeDisplayFrameByteBatches: Long = 0,
        nativeDisplayFrameBytes: Long = 0,
        processSchedulerTicks: Long = 0,
        processSchedulerSelectedTicks: Long = 0,
        processSchedulerIdleTicks: Long = 0,
        processSchedulerWokenProcesses: Long = 0,
        nativeProcessSchedulerComparisons: Long = 0,
        nativeProcessSchedulerMatches: Long = 0,
        nativeProcessSchedulerMismatches: Long = 0,
        nativeProcessSchedulerAcceptedTicks: Long = 0,
        nativeProcessSchedulerFallbackTicks: Long = 0,
        nativeExecutionQuotaRefills: Long = 0,
        nativeExecutionQuotaInstructions: Long = 0,
        nativeExecutionQuotaWallNanos: Long = 0,
        nativeExecutionQuotaLastServerTick: Long = 0,
        nativeSchedulerDryRuns: Long = 0,
        nativeSchedulerDryRunTurns: Long = 0,
        nativeSchedulerDryRunSelectedPids: Long = 0,
        nativeSchedulerDryRunRemainingInstructions: Long = 0,
        nativeSchedulerDryRunFirstSelectionMatches: Long = 0,
        nativeSchedulerDryRunFirstSelectionMismatches: Long = 0,
        hostCallSignals: Long,
        hostCalls: List<RuntimeHostCallMetrics> = emptyList(),
    ): RuntimeWorkloadProfile =
        RuntimeWorkloadProfile(
            name = name,
            display =
                DisplayProfilingSnapshot(
                    operations =
                        DisplayOperationMetrics(
                            fillRectCalls = 2,
                            fillRectArea = 20,
                            fillRectNanos = 80,
                            presentCalls = 1,
                            presentFrames = 1,
                            presentNanos = 40,
                        ),
                    frames = DisplayFrameMetrics(frameCount = 1, tileCount = 2, payloadBytes = 128),
                    frameBuild = DisplayFrameBuildTotals(buildCalls = 1, totalNanos = 64, tileCount = 2, payloadBytes = 128),
                ),
            client =
                ClientDisplayProfilingSnapshot(
                    framesReceived = 2,
                    framesApplied = 2,
                    fullRefreshFrames = 1,
                    tilesApplied = 3,
                    payloadBytes = 96,
                    applyNanos = 12,
                    swapCalls = 2,
                    dirtySwaps = 2,
                    swapNanos = 8,
                    snapshotsCopied = 2,
                    snapshotRegions = 2,
                    snapshotPixels = 32,
                    snapshotCopyNanos = 6,
                ),
            runtime =
                RuntimeProfilingSnapshot(
                    tick = RuntimeTickMetrics(serverTickCalls = 1, serverTickNanos = runtimeNanos),
                    vm =
                        RuntimeVmMetrics(
                            executionWindows = 1,
                            executionWindowNanos = executionNanos,
                            waitPollSignals = waitPollSignals,
                            waitProcessSignals = waitProcessSignals,
                            nativeProcessRegistrations = nativeProcessRegistrations,
                            nativeProcessCompletions = nativeProcessCompletions,
                            nativeProcessStaleCompletions = nativeProcessStaleCompletions,
                            nativeWaitCalls = nativeWaitCalls,
                            nativeWaitNanos = nativeWaitNanos,
                            nativeWaitWakeups = nativeWaitWakeups,
                            nativeWaitTimeouts = nativeWaitTimeouts,
                            nativeDisplayPumpWaitCalls = nativeDisplayPumpWaitCalls,
                            nativeDisplayPumpWaitNanos = nativeDisplayPumpWaitNanos,
                            nativeDisplayPumpWakeups = nativeDisplayPumpWakeups,
                            nativeDisplayPumpTimeouts = nativeDisplayPumpTimeouts,
                            nativeDisplayFrameByteBatches = nativeDisplayFrameByteBatches,
                            nativeDisplayFrameBytes = nativeDisplayFrameBytes,
                            processSchedulerTicks = processSchedulerTicks,
                            processSchedulerSelectedTicks = processSchedulerSelectedTicks,
                            processSchedulerIdleTicks = processSchedulerIdleTicks,
                            processSchedulerWokenProcesses = processSchedulerWokenProcesses,
                            nativeProcessSchedulerComparisons = nativeProcessSchedulerComparisons,
                            nativeProcessSchedulerMatches = nativeProcessSchedulerMatches,
                            nativeProcessSchedulerMismatches = nativeProcessSchedulerMismatches,
                            nativeProcessSchedulerAcceptedTicks = nativeProcessSchedulerAcceptedTicks,
                            nativeProcessSchedulerFallbackTicks = nativeProcessSchedulerFallbackTicks,
                            nativeExecutionQuotaRefills = nativeExecutionQuotaRefills,
                            nativeExecutionQuotaInstructions = nativeExecutionQuotaInstructions,
                            nativeExecutionQuotaWallNanos = nativeExecutionQuotaWallNanos,
                            nativeExecutionQuotaLastServerTick = nativeExecutionQuotaLastServerTick,
                            nativeSchedulerDryRuns = nativeSchedulerDryRuns,
                            nativeSchedulerDryRunTurns = nativeSchedulerDryRunTurns,
                            nativeSchedulerDryRunSelectedPids = nativeSchedulerDryRunSelectedPids,
                            nativeSchedulerDryRunRemainingInstructions = nativeSchedulerDryRunRemainingInstructions,
                            nativeSchedulerDryRunFirstSelectionMatches = nativeSchedulerDryRunFirstSelectionMatches,
                            nativeSchedulerDryRunFirstSelectionMismatches = nativeSchedulerDryRunFirstSelectionMismatches,
                            hostCallSignals = hostCallSignals,
                        ),
                    hostCalls = hostCalls,
                ),
            compiler = CompilerProfilingSnapshot(compileCalls = 1, compileNanos = 30, compiledSources = 1),
            enterAutoscroll =
                EnterAutoscrollWorkloadSummary(
                    enterEventsQueued = 9,
                    ticksUntilFirstAutoscroll = 10,
                    copyRectCallsBefore = 11,
                    copyRectCallsAfter = 12,
                    displayFramesDrained = 13,
                    clientFramesApplied = 14,
                ),
            pipeline =
                TerminalPipelineSummary(
                    inputChars = 4,
                    inputPhaseNanos = 44,
                    inputClientFrames = 2,
                    enterPhaseNanos = 1_500_000_000,
                    enterClientFrames = 1,
                ),
        )
}

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
                            runtimeNanos = 100,
                            executionNanos = 40,
                            hostCallSignals = 10,
                            hostCalls = listOf(RuntimeHostCallMetrics("strings", "length", calls = 5, nanos = 50)),
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
                            runtimeNanos = 50,
                            executionNanos = 20,
                            hostCallSignals = 2,
                            hostCalls =
                                listOf(
                                    RuntimeHostCallMetrics(
                                        "display",
                                        "present",
                                        calls = 3,
                                        nanos = 90,
                                        waitNanos = 60,
                                    ),
                            ),
                                ),
                        workload(
                            name = "new workload",
                            runtimeNanos = 7,
                            executionNanos = 3,
                            hostCallSignals = 1,
                            hostCalls = listOf(RuntimeHostCallMetrics("ipc", "tryRead", calls = 1, nanos = 11)),
                        ),
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
        assertTrue(markdown.contains("| Runtime all ticks | 50 ns | 100 ns |"), markdown)
        assertTrue(markdown.contains("| VM execution time | 20 ns | 40 ns |"), markdown)
        assertTrue(markdown.contains("| Host-call signals | 2 | 10 |"), markdown)
        assertTrue(markdown.contains("| Host-call active time | 30 ns | 50 ns |"), markdown)
        assertTrue(markdown.contains("| Host-call wait time | 60 ns | 0 ns |"), markdown)
        assertTrue(
            markdown.contains(
                "| host display.present | 3 calls / 30 ns active / 60 ns wait / 90 ns total | 0 calls / 0 ns active / 0 ns wait / 0 ns total |",
            ),
            markdown,
        )
        assertTrue(
            markdown.contains(
                "| host strings.length | 0 calls / 0 ns active / 0 ns wait / 0 ns total | 5 calls / 50 ns active / 0 ns wait / 50 ns total |",
            ),
            markdown,
        )
        assertTrue(
            markdown.contains("| host ipc.tryRead | 1 calls / 11 ns active / 0 ns wait / 11 ns total |"),
            markdown
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
                        workload("held Enter backlog", runtimeNanos = 80, executionNanos = 20, hostCallSignals = 2),
                    ),
            )

        val markdown = RuntimeVmProfilingReportFormatter.runMarkdown(run)

        assertTrue(markdown.contains("# Runtime VM Profiling Report"), markdown)
        assertTrue(markdown.contains("sustained terminal no-delay"), markdown)
        assertTrue(markdown.contains("held Enter backlog"), markdown)
        assertTrue(markdown.contains("| Host-call signals | 4 |"), markdown)
        assertTrue(markdown.contains("| Host-call active time | 0 ns |"), markdown)
        assertTrue(markdown.contains("| Host-call wait time | 0 ns |"), markdown)
        assertTrue(markdown.contains("| Client frames applied | 2 |"), markdown)
        assertTrue(markdown.contains("| Client snapshot pixels | 32 |"), markdown)
        assertTrue(markdown.contains("| Enter phase to client | 55 ns |"), markdown)
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
                    vm = RuntimeVmMetrics(executionWindows = 1, executionWindowNanos = executionNanos, hostCallSignals = hostCallSignals),
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
                    enterPhaseNanos = 55,
                    enterClientFrames = 1,
                ),
        )
}

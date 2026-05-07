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
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeVmProfilingReportFormatterTest {
    @Test
    fun markdownContainsRunnerComparisonRatiosAndUnavailableInstructionNote() {
        val jvm =
            VmRunnerProfile(
                runnerName = "JVM",
                workloads = listOf(workload("terminal", runtimeNanos = 100, instructionCount = 4, frameCount = 2)),
            )
        val rust =
            VmRunnerProfile(
                runnerName = "Rust",
                workloads = listOf(workload("terminal", runtimeNanos = 150, instructionCount = 0, frameCount = 1)),
            )

        val markdown = RuntimeVmProfilingReportFormatter.markdown(jvm, rust)

        assertTrue(markdown.contains("# Runtime VM Profiling Comparison"), markdown)
        assertTrue(markdown.contains("## terminal"), markdown)
        assertTrue(markdown.contains("| Runtime all ticks | 100 ns | 150 ns | 1.50x |"), markdown)
        assertTrue(markdown.contains("| display.present | 2 | 100 ns | 2 | 100 ns | 1.00x |"), markdown)
        assertTrue(markdown.contains("| Instructions | 4 | unavailable | — |"), markdown)
        assertTrue(markdown.contains("Progress differs for this workload"), markdown)
        assertTrue(markdown.contains("Rust instruction metrics are currently unavailable"), markdown)
    }

    private fun workload(
        name: String,
        runtimeNanos: Long,
        instructionCount: Long,
        frameCount: Long,
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
                    frames = DisplayFrameMetrics(frameCount = frameCount, tileCount = 2, payloadBytes = 128),
                    frameBuild = DisplayFrameBuildTotals(buildCalls = 1, totalNanos = 64, tileCount = 2, payloadBytes = 128),
                ),
            runtime =
                RuntimeProfilingSnapshot(
                    tick = RuntimeTickMetrics(serverTickCalls = 1, serverTickNanos = runtimeNanos),
                    vm = RuntimeVmMetrics(executionWindows = 1, executionWindowNanos = 200, hostCallSignals = 2),
                    hostCalls = listOf(RuntimeHostCallMetrics("display", "present", calls = 2, nanos = 100)),
                    instructions =
                        if (instructionCount > 0) {
                            listOf(RuntimeInstructionMetrics(VmInstructionKind.CALL_BUILTIN, count = instructionCount, nanos = 20))
                        } else {
                            emptyList()
                        },
                ),
            compiler = CompilerProfilingSnapshot(compileCalls = 1, compileNanos = 30, compiledSources = 1),
            heldEnter = null,
        )
}

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
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeVmProfilingProfileCodecTest {
    @Test
    fun profileRoundTripsThroughFile() {
        val profile =
            RuntimeVmProfile(
                runtimeName = "Rust image",
                workloads =
                    listOf(
                        RuntimeWorkloadProfile(
                            name = "sample workload",
                            display =
                                DisplayProfilingSnapshot(
                                    operations = DisplayOperationMetrics(clearCalls = 1, clearNanos = 2, presentCalls = 3, presentFrames = 4, presentNanos = 5),
                                    frames = DisplayFrameMetrics(frameCount = 6, fullRefreshFrames = 7, tileCount = 8, payloadBytes = 9),
                                    frameBuild = DisplayFrameBuildTotals(buildCalls = 10, totalNanos = 11, tileCount = 12, payloadBytes = 13),
                                ),
                            runtime =
                                RuntimeProfilingSnapshot(
                                    tick = RuntimeTickMetrics(serverTickCalls = 14, serverTickNanos = 15, requestSliceCalls = 16, requestSliceNanos = 17),
                                    vm = RuntimeVmMetrics(executionWindows = 18, executionWindowNanos = 19, hostCallSignals = 20),
                                    hostCalls = listOf(RuntimeHostCallMetrics("display", "present", calls = 21, nanos = 22)),
                                    instructions = listOf(RuntimeInstructionMetrics(VmInstructionKind.CALL_BUILTIN, count = 23, nanos = 24)),
                                ),
                            compiler = CompilerProfilingSnapshot(compileCalls = 25, compileNanos = 26, compiledSources = 27),
                            heldEnter = HeldEnterWorkloadSummary(
                                enterEventsQueued = 28,
                                settleTicks = 29,
                                maxQueuedEvents = 30,
                                finalQueuedEvents = 31,
                                maxPendingHostCalls = 32,
                                finalPendingHostCalls = 33,
                                displayFramesDrained = 34,
                            ),
                        ),
                    ),
            )
        val path = createTempFile("runtime-vm-profile", ".tsv")

        RuntimeVmProfileCodec.write(profile, path)
        val decoded = RuntimeVmProfileCodec.read(path)

        assertEquals(profile, decoded)
    }
}

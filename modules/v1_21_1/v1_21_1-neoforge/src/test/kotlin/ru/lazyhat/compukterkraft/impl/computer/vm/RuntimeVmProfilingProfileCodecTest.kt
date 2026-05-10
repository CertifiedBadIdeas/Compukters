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
                                    operations =
                                        DisplayOperationMetrics(
                                            clearCalls = 1,
                                            clearNanos = 2,
                                            presentCalls = 3,
                                            presentFrames = 4,
                                            presentNanos = 5,
                                        ),
                                    frames =
                                        DisplayFrameMetrics(
                                            frameCount = 6,
                                            fullRefreshFrames = 7,
                                            tileCount = 8,
                                            payloadBytes = 9,
                                        ),
                                    frameBuild =
                                        DisplayFrameBuildTotals(
                                            buildCalls = 10,
                                            totalNanos = 11,
                                            tileCount = 12,
                                            payloadBytes = 13,
                                        ),
                                ),
                            client =
                                ClientDisplayProfilingSnapshot(
                                    framesReceived = 14,
                                    framesApplied = 15,
                                    rejectedFrames = 16,
                                    fullRefreshFrames = 17,
                                    tilesApplied = 18,
                                    payloadBytes = 19,
                                    applyNanos = 20,
                                    swapCalls = 21,
                                    dirtySwaps = 22,
                                    swapNanos = 23,
                                    snapshotsCopied = 24,
                                    snapshotRegions = 25,
                                    snapshotPixels = 26,
                                    snapshotCopyNanos = 27,
                                ),
                            runtime =
                                RuntimeProfilingSnapshot(
                                    tick =
                                        RuntimeTickMetrics(
                                            serverTickCalls = 28,
                                            serverTickNanos = 29,
                                            requestSliceCalls = 30,
                                            requestSliceNanos = 31,
                                        ),
                                    vm =
                                        RuntimeVmMetrics(
                                            executionWindows = 32,
                                            executionWindowNanos = 33,
                                            executionQuotaRefills = 61,
                                            executionQuotaAcceptedRefills = 62,
                                            executionQuotaUnavailableRefills = 63,
                                            executionQuotaPermitsConsumed = 64,
                                            processSchedulerTicks = 65,
                                            processSchedulerSelectedTicks = 66,
                                            processSchedulerIdleTicks = 67,
                                            processSchedulerWokenProcesses = 68,
                                            nativeProcessSchedulerComparisons = 69,
                                            nativeProcessSchedulerMatches = 70,
                                            nativeProcessSchedulerMismatches = 71,
                                            nativeProcessSchedulerAcceptedTicks = 72,
                                            nativeProcessSchedulerFallbackTicks = 73,
                                            nativeExecutionQuotaRefills = 74,
                                            nativeExecutionQuotaInstructions = 75,
                                            nativeExecutionQuotaWallNanos = 76,
                                            nativeExecutionQuotaLastServerTick = 77,
                                            nativeSchedulerDryRuns = 78,
                                            nativeSchedulerDryRunTurns = 79,
                                            nativeSchedulerDryRunSelectedPids = 80,
                                            nativeSchedulerDryRunRemainingInstructions = 81,
                                            nativeSchedulerDryRunFirstSelectionMatches = 82,
                                            nativeSchedulerDryRunFirstSelectionMismatches = 83,
                                            waitPollSignals = 34,
                                            waitProcessSignals = 35,
                                            nativeProcessRegistrations = 36,
                                            nativeProcessCompletions = 37,
                                            nativeProcessStaleCompletions = 38,
                                            hostCallSignals = 39,
                                            nativeFastPathCalls = 40,
                                            nativeWaitCalls = 41,
                                            nativeWaitNanos = 42,
                                            nativeWaitWakeups = 43,
                                            nativeWaitTimeouts = 44,
                                        ),
                                    hostCalls =
                                        listOf(
                                            RuntimeHostCallMetrics(
                                                "display",
                                                "present",
                                                calls = 41,
                                                nanos = 42,
                                                waitNanos = 20,
                                            ),
                                        ),
                                        instructions =
                                        listOf(
                                            RuntimeInstructionMetrics(
                                                VmInstructionKind.CALL_BUILTIN,
                                                count = 43,
                                                nanos = 44,
                                            ),
                                        ),
                                ),
                            compiler = CompilerProfilingSnapshot(compileCalls = 45, compileNanos = 46, compiledSources = 47),
                            heldEnter =
                                HeldEnterWorkloadSummary(
                                    enterEventsQueued = 48,
                                    settleTicks = 49,
                                    maxQueuedEvents = 50,
                                    finalQueuedEvents = 51,
                                    maxPendingHostCalls = 52,
                                    finalPendingHostCalls = 53,
                                    displayFramesDrained = 54,
                                ),
                            enterAutoscroll =
                                EnterAutoscrollWorkloadSummary(
                                    enterEventsQueued = 55,
                                    ticksUntilFirstAutoscroll = 56,
                                    copyRectCallsBefore = 57,
                                    copyRectCallsAfter = 58,
                                    displayFramesDrained = 59,
                                    clientFramesApplied = 60,
                                ),
                            pipeline =
                                TerminalPipelineSummary(
                                    inputChars = 50,
                                    inputPhaseNanos = 51,
                                    inputClientFrames = 52,
                                    enterPhaseNanos = 53,
                                    enterClientFrames = 54,
                                ),
                        ),
                    ),
            )
        val path = createTempFile("runtime-vm-profile", ".tsv")

        RuntimeVmProfileCodec.write(profile, path)
        val decoded = RuntimeVmProfileCodec.read(path)

        assertEquals(profile, decoded)
    }

    @Test
    fun profileReaderAcceptsLegacyHostCallRowsWithoutWaitNanos() {
        val path = createTempFile("runtime-vm-profile-legacy", ".tsv")
        path.toFile().writeText(
            """
            runtime	Rust image
            workload	sample workload
            displayOps	0	0	0	0	0	0	0	0	0	0	0	0	0	0	0	0
            displayFrames	0	0	0	0
            displayBuild	0	0	0	0	0	0	0	0
            clientDisplay	0	0	0	0	0	0	0	0	0	0	0	0	0	0
            runtimeTick	0	0	0	0	0	0	0	0	0	0	0	0	0	0	0	0	0	0	0
            runtimeVm	0	0	0	0	0	0	0	0	0	0	0	0	0	0	12
            host	display	present	35	36
            compiler	0	0	0	0	0	0	0	0	0	0	0	0	0	0	0	0
            endWorkload
            """.trimIndent() + "\n",
        )

        val decoded = RuntimeVmProfileCodec.read(path)

        assertEquals(
            0,
            decoded.workloads
                .single()
                .runtime.hostCalls
                .single().waitNanos,
        )
        assertEquals(36, decoded.workloads.single().runtime.hostCalls.single().activeNanos)
        assertEquals(0, decoded.workloads.single().runtime.vm.waitPollSignals)
        assertEquals(0, decoded.workloads.single().runtime.vm.waitProcessSignals)
        assertEquals(0, decoded.workloads.single().runtime.vm.nativeProcessRegistrations)
        assertEquals(0, decoded.workloads.single().runtime.vm.nativeProcessCompletions)
        assertEquals(0, decoded.workloads.single().runtime.vm.nativeProcessStaleCompletions)
        assertEquals(12, decoded.workloads.single().runtime.vm.hostCallSignals)
        assertEquals(0, decoded.workloads.single().runtime.vm.nativeWaitCalls)
        assertEquals(0, decoded.workloads.single().runtime.vm.nativeWaitNanos)
    }
}

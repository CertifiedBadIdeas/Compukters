/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.benchmark

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.impl.computer.NeoForgeVmActorServices
import ru.lazyhat.compukters.impl.computer.compuktersComputerBlockEntity
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.LockSupport
import javax.management.ObjectName
import kotlin.io.path.writeText

/** Explicit, disposable GameTest profile. Diagnostic full GC deliberately pauses this test server. */
internal object PhysicalVmMemoryProbe {
    fun run(helper: GameTestHelper) {
        val count = requireNotNull(System.getProperty("compukters.memoryProbe.population")).toInt()
        require(count in 1..4096)
        val output = Path.of(requireNotNull(System.getProperty("compukters.memoryProbe.directory")))
        require(!Files.exists(output.resolve("baseline-histogram.txt"))) { "Use a fresh memory probe directory" }
        Files.createDirectories(output)
        val positions = (0 until count).map { BlockPos(it % 16, 1 + 2 * (it / 256), (it / 16) % 16) }
        val warmup = BlockPos(12, 1, 0)
        val server = helper.level.server
        var terminals: List<CompletableFuture<TerminalState?>>? = null
        var admitted = 0
        var nextAdmissionNanos = 0L
        var baselineReadyNanos = 0L

        fun actors() = NeoForgeVmActorServices.metrics(server)?.scheduler?.registeredActors ?: 0

        fun waiting(): Int =
            positions.count { position ->
                val state = helper.compuktersComputerBlockEntity(position).runtimeState
                if (state is ProgramComputerState.PoweredOff && state.reason is ProgramComputerStopReason.Failure) {
                    error("Physical computer at $position failed: $state")
                }
                state == ProgramComputerState.WaitingForInput
            }

        fun snapshot(phase: String) {
            val histogram = diagnostic("gcClassHistogram", emptyArray())
            output.resolve("$phase-histogram.txt").writeText(histogram)
            output.resolve("$phase-nmt.txt").writeText(diagnostic("vmNativeMemory", arrayOf("summary", "scale=KB")))
            output.resolve("$phase-smaps.txt").writeText(Files.readString(Path.of("/proc/self/smaps_rollup")))
            output.resolve("$phase-metadata.txt").writeText(
                "population=$count\nactors=${actors()}\nserverTick=${server.tickCount}\npid=${ProcessHandle.current().pid()}\n",
            )
        }
        helper.setBlock(warmup, CompuktersRegistry.COMPUTER.get())
        helper
            .startSequence()
            .thenWaitUntil {
                // GameTestServer does not pace ticks; give real actor and persistence workers time.
                LockSupport.parkNanos(100_000L)

                helper.assertTrue(
                    helper.compuktersComputerBlockEntity(warmup).runtimeState == ProgramComputerState.WaitingForInput,
                    "warmup shell is still booting",
                )
            }.thenExecute { helper.level.destroyBlock(helper.absolutePos(warmup), false) }
            .thenWaitUntil {
                // GameTestServer does not pace ticks; give real actor and persistence workers time.
                LockSupport.parkNanos(100_000L)
                val fallback = NeoForgeVmActorServices.metrics(server)?.capacityFallbackReason
                if (fallback != null && fallback != "calibration pending") error("VM calibration failed: $fallback")
                helper.assertTrue(actors() == 0 && fallback == null, "warmup actor or capacity calibration is still finishing")
            }.thenExecute {
                snapshot("warmup")
                baselineReadyNanos = System.nanoTime() + 5_000_000_000L
            }.thenWaitUntil {
                LockSupport.parkNanos(100_000L)
                helper.assertTrue(System.nanoTime() >= baselineReadyNanos, "Minecraft baseline is warming")
            }.thenExecute {
                snapshot("baseline")
            }.thenWaitUntil {
                // GameTest ticks are unpaced. Keep this yield below the VM host window.
                LockSupport.parkNanos(100_000L)
                if (admitted < count) {
                    val previous =
                        admitted.takeIf { it > 0 }?.let {
                            helper.compuktersComputerBlockEntity(positions[it - 1]).runtimeState
                        }
                    if (previous is ProgramComputerState.PoweredOff && previous.reason is ProgramComputerStopReason.Failure) {
                        error("Physical computer failed during admission: $previous")
                    }
                    if ((previous == null || previous == ProgramComputerState.WaitingForInput) && System.nanoTime() >= nextAdmissionNanos) {
                        helper.setBlock(positions[admitted++], CompuktersRegistry.COMPUTER.get())
                        nextAdmissionNanos = System.nanoTime() + 10_000_000L
                    }
                }
                helper.assertTrue(admitted == count && waiting() == count, "fleet admission is still settling: $admitted/$count")
            }.thenIdle(100)
            .thenExecute {
                helper.assertTrue(actors() == count, "fleet admission mismatch")
                snapshot("prompt")
                terminals = positions.map { helper.compuktersComputerBlockEntity(it).terminalFullStateAsync() }
            }.thenWaitUntil {
                // GameTestServer does not pace ticks; give real actor and persistence workers time.
                LockSupport.parkNanos(100_000L)

                val pending = requireNotNull(terminals)
                helper.assertTrue(pending.all { it.isDone }, "terminal reads are still pending")
                helper.assertTrue(
                    pending.all { future -> future.join()?.cells?.any { it.codePoint == '>'.code } == true },
                    "a physical shell prompt is missing",
                )
            }.thenExecute {
                terminals = null
                snapshot("observed")
                positions.forEach { helper.level.destroyBlock(helper.absolutePos(it), false) }
            }.thenWaitUntil {
                // GameTestServer does not pace ticks; give real actor and persistence workers time.
                LockSupport.parkNanos(100_000L)
                helper.assertTrue(actors() == 0, "fleet actors are still closing")
            }.thenIdle(100)
            .thenExecute { snapshot("dropped") }
            .thenSucceed()
    }

    private fun diagnostic(
        operation: String,
        arguments: Array<String>,
    ): String =
        ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            operation,
            arrayOf(arguments),
            arrayOf("[Ljava.lang.String;"),
        ) as String
}

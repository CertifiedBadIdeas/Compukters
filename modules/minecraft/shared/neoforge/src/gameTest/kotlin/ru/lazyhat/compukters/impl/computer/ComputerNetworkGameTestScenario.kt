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

package ru.lazyhat.compukters.impl.computer

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.network.ComputerNetworkFailure
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.minecraft.network.ComputerCableMessages
import java.util.concurrent.CompletableFuture

/** Real Guest -> native VM -> server host -> peer VM round trip, on both native transports. */
internal object ComputerNetworkGameTestScenario {
    fun run(helper: GameTestHelper) {
        val first = BlockPos(2, 2, 2)
        val second = BlockPos(6, 2, 2)
        val cables = (3..5).map { BlockPos(it, 2, 2) }
        helper.setBlock(first, CompuktersRegistry.COMPUTER.get())
        helper.setBlock(second, CompuktersRegistry.COMPUTER.get())
        cables.forEach {
            helper.setBlock(it.below(), Blocks.STONE)
            helper.setBlock(it, CompuktersRegistry.PERIPHERAL_CABLE.get())
        }
        val a = helper.compuktersComputerBlockEntity(first)
        val b = helper.compuktersComputerBlockEntity(second)
        a.prepareTerminalAsync()
        b.prepareTerminalAsync()
        var setup: CompletableFuture<Void>? = null
        var snapshots = arrayOfNulls<CompletableFuture<TerminalState?>>(2)

        fun output(index: Int): String {
            val computer = if (index == 0) a else b
            if (snapshots[index] == null) snapshots[index] = computer.terminalFullStateAsync()
            val future = snapshots[index]!!
            helper.assertTrue(future.isDone, "network terminal snapshot is pending")
            val terminal = future.getNow(null)
            snapshots[index] = null
            helper.assertTrue(terminal != null, "network terminal snapshot is unavailable")
            return (0 until terminal!!.height).joinToString("\n") { y ->
                buildString { repeat(terminal.width) { x -> appendCodePoint(terminal.cells[y * terminal.width + x].codePoint) } }.trimEnd()
            }
        }

        fun launch(
            computer: NeoForgeComputerBlockEntity,
            name: String,
        ): CompletableFuture<Void> =
            computer
                .verifyForDeployAsync(fixture(name))
                .thenCompose { candidate ->
                    requireNotNull(candidate)
                    computer.executableRevisionAsync("/home/$name").thenCompose { revision ->
                        computer.deployAsync("/home/$name", requireNotNull(revision), candidate)
                    }
                }.thenCompose { computer.submitTerminalTextAsync(name) }
                .thenCompose { accepted ->
                    check(accepted)
                    computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
                }.thenAccept { check(it) }
        helper
            .startSequence()
            .thenWaitUntil {
                helper.assertTrue(
                    a.runtimeState == ProgramComputerState.WaitingForInput && b.runtimeState == ProgramComputerState.WaitingForInput,
                    "network computer shells are not ready",
                )
            }.thenExecute {
                setup = CompletableFuture.allOf(launch(b, "network-receiver"), launch(a, "network-sender"))
            }.thenWaitUntil {
                helper.assertTrue(setup?.isDone == true, "network setup is pending")
                setup!!.getNow(null)
            }.thenWaitUntil {
                val firstOutput = output(0)
                val secondOutput = output(1)
                helper.assertTrue(
                    firstOutput.contains("NETWORK-SENDER-OK") && secondOutput.contains("NETWORK-RECEIVER-OK"),
                    "binary network round trip failed: first=$firstOutput second=$secondOutput",
                )
            }.thenWaitUntil {
                helper.assertTrue(
                    a.runtimeState == ProgramComputerState.WaitingForInput && b.runtimeState == ProgramComputerState.WaitingForInput,
                    "network programs have not exited",
                )
            }.thenExecute {
                val old = requireNotNull(ComputerCableMessages.endpoint(helper.level, helper.absolutePos(first)).connection())
                old.send(byteArrayOf(19))
                // A disconnected queued message must not survive a cut-and-repair in the same tick.
                helper.setBlock(cables[1], Blocks.AIR)
                helper.setBlock(cables[1], CompuktersRegistry.PERIPHERAL_CABLE.get())
                helper.assertTrue(!old.connected, "old connection survived cut and repair")
                val fresh = requireNotNull(ComputerCableMessages.endpoint(helper.level, helper.absolutePos(second)).connection())
                helper.assertTrue(fresh.receive() == null, "cut cable leaked queued bytes into new connection")
                // Neighbor updates and an unrelated cable must leave this connection alive.
                helper.setBlock(BlockPos(3, 2, 4).below(), Blocks.STONE)
                helper.setBlock(BlockPos(3, 2, 4), CompuktersRegistry.PERIPHERAL_CABLE.get())
                helper.assertTrue(fresh.connected, "unrelated cable interrupted connection")
                repeat(16) { fresh.send(byteArrayOf()) }
                var rejected = false
                try {
                    fresh.send(byteArrayOf())
                } catch (_: ComputerNetworkFailure) {
                    rejected = true
                }
                helper.assertTrue(rejected, "computer inbox did not enforce its message bound")
                val inbox = requireNotNull(ComputerCableMessages.endpoint(helper.level, helper.absolutePos(first)).connection())
                repeat(16) { helper.assertTrue(inbox.receive()?.size == 0, "empty message lost") }
                setup = launch(a, "network-disconnect")
            }.thenWaitUntil {
                helper.assertTrue(setup?.isDone == true, "network disconnect setup is pending")
                setup!!.getNow(null)
            }.thenWaitUntil {
                helper.assertTrue(output(0).contains("NETWORK-WAITING"), "Guest has not reached network receive")
            }.thenIdle(10)
            .thenExecute {
                helper.setBlock(cables[1], Blocks.AIR)
                helper.setBlock(cables[1], CompuktersRegistry.PERIPHERAL_CABLE.get())
            }.thenWaitUntil {
                helper.assertTrue(output(0).contains("NETWORK-INTERRUPTED"), "Guest did not catch cable interruption as IOException")
            }.thenSucceed()
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name.cpkt")).use {
            it.readAllBytes()
        }
}

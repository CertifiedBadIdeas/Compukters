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
import net.minecraft.world.level.block.entity.BlockEntity
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import java.util.concurrent.CompletableFuture

/** The unsaved Guest editor buffer, child process and open file must survive two carrier replacements. */
internal object ComputerHibernationGameTestScenario {
    fun run(helper: GameTestHelper) {
        val position = BlockPos.ZERO
        helper.setBlock(position, CompuktersRegistry.COMPUTER.get())
        var computer = helper.compuktersComputerBlockEntity(position)
        val identity = computer.computerId()
        var operation: CompletableFuture<Boolean>? = null
        var snapshot: CompletableFuture<TerminalState?>? = null
        val sequence = helper.startSequence()

        fun awaitText(expected: String) {
            sequence.thenWaitUntil {
                if (snapshot == null) snapshot = computer.terminalFullStateAsync()
                helper.assertTrue(snapshot!!.isDone, "hibernation terminal snapshot pending")
                val state = snapshot!!.join()
                snapshot = null
                val text =
                    state?.let { terminal ->
                        (0 until terminal.height).joinToString("\n", postfix = "\n") { y ->
                            buildString {
                                repeat(terminal.width) { x -> appendCodePoint(terminal.cells[y * terminal.width + x].codePoint) }
                            }.trimEnd()
                        }
                    }
                helper.assertTrue(
                    computer.runtimeState == ProgramComputerState.WaitingForInput && text?.contains(expected) == true,
                    "expected $expected after hibernation; state=${computer.runtimeState}; terminal=$text",
                )
            }
        }

        fun input(action: () -> CompletableFuture<Boolean>) {
            sequence.thenExecute { operation = action() }
            sequence.thenWaitUntil {
                helper.assertTrue(operation?.isDone == true, "hibernation input pending")
                helper.assertTrue(operation!!.join(), "hibernation input rejected")
            }
        }

        fun reload() {
            sequence.thenExecute {
                val old = computer
                val level = helper.level
                val absolutePosition = helper.absolutePos(position)
                val state = level.getBlockState(absolutePosition)
                val payload = old.saveWithFullMetadata(level.registryAccess())
                level.removeBlockEntity(absolutePosition)
                helper.assertTrue(old.isRemoved, "carrier was not removed")
                helper.assertTrue(old.runtimeState == ProgramComputerState.Closed, "old carrier still runs")
                computer = BlockEntity.loadStatic(absolutePosition, state, payload, level.registryAccess()) as NeoForgeComputerBlockEntity
                level.setBlockEntity(computer)
                helper.assertTrue(computer.computerId() == identity, "hibernation changed ComputerId")
            }
        }
        sequence.thenExecute { computer.prepareTerminalAsync() }
        awaitText(">\n")
        input {
            computer.submitTerminalTextAsync("edit hibernation.txt").thenCompose {
                check(it)
                computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
            }
        }
        awaitText("Compukters edit")
        input { computer.submitTerminalTextAsync("unsaved-before") }
        awaitText("unsaved-before")
        reload()
        awaitText("unsaved-before")
        input { computer.submitTerminalTextAsync("-between") }
        awaitText("unsaved-before-between")
        reload()
        awaitText("unsaved-before-between")
        input { computer.submitTerminalTextAsync("-after") }
        input { computer.submitTerminalKeyAsync(TerminalKey.S, TerminalKeyAction.PRESS, setOf(TerminalModifier.CONTROL)) }
        input { computer.submitTerminalKeyAsync(TerminalKey.X, TerminalKeyAction.PRESS, setOf(TerminalModifier.CONTROL)) }
        awaitText(">\n")
        input {
            computer.submitTerminalTextAsync("cat hibernation.txt").thenCompose {
                check(it)
                computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
            }
        }
        awaitText("unsaved-before-between-after")
        sequence.thenSucceed()
    }
}

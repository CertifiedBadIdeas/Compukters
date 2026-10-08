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

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.impl.fs.NeoForgeWorldFileSystemStores
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** The unsaved Guest editor buffer, child process and open file must survive two carrier replacements. */
internal object ComputerHibernationGameTestScenario {
    fun run(helper: GameTestHelper) {
        val position = BlockPos.ZERO
        helper.setBlock(position, CompuktersRegistry.COMPUTER.get())
        val restartPhase = System.getenv("COMPUKTERS_HIBERNATION_RESTART_PHASE")
        require(restartPhase == null || restartPhase == "produce" || restartPhase == "resume")
        val restartPayload =
            restartPhase?.let {
                Path.of(requireNotNull(System.getenv("COMPUKTERS_HIBERNATION_RESTART_PAYLOAD")))
            }
        if (restartPhase == "resume") {
            val absolutePosition = helper.absolutePos(position)
            val payload =
                Files.newInputStream(requireNotNull(restartPayload)).use {
                    NbtIo.readCompressed(it, NbtAccounter.unlimitedHeap())
                }
            helper.level.removeBlockEntity(absolutePosition)
            helper.level.setBlockEntity(
                BlockEntity.loadStatic(
                    absolutePosition,
                    helper.level.getBlockState(absolutePosition),
                    payload,
                    helper.level.registryAccess(),
                ) as NeoForgeComputerBlockEntity,
            )
        }
        var computer = helper.compuktersComputerBlockEntity(position)
        val identity = computer.computerId()
        var operation: CompletableFuture<Boolean>? = null
        var snapshot: CompletableFuture<TerminalState?>? = null
        val sequence = helper.startSequence()

        fun awaitText(
            expected: String,
            exact: Boolean = false,
        ) {
            sequence.thenExecute { LogUtils.getLogger().info("Hibernation awaiting: {} exact={}", expected.trim(), exact) }
            sequence.thenWaitUntil {
                if (snapshot == null) snapshot = computer.terminalFullStateAsync()
                helper.assertTrue(
                    snapshot!!.isDone,
                    "hibernation terminal snapshot pending for $expected; state=${computer.runtimeState}; epoch=${computer.terminalMachineId}",
                )
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
                    computer.runtimeState == ProgramComputerState.WaitingForInput &&
                        (if (exact) text?.trimEnd() == expected.trimEnd() else text?.contains(expected) == true),
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

        fun reload(
            stopStore: Boolean = false,
            damageCheckpoint: ((Path) -> Unit)? = null,
        ) {
            sequence.thenExecute {
                val old = computer
                val level = helper.level
                val absolutePosition = helper.absolutePos(position)
                val state = level.getBlockState(absolutePosition)
                LogUtils.getLogger().info("Hibernation reload stopStore={} id={} epoch={}", stopStore, identity, old.terminalMachineId)
                val payload = old.saveWithFullMetadata(level.registryAccess())
                if (stopStore) NeoForgeWorldFileSystemStores.onServerStopping(ServerStoppingEvent(level.server))
                level.removeBlockEntity(absolutePosition)
                helper.assertTrue(old.isRemoved, "carrier was not removed")
                helper.assertTrue(old.runtimeState == ProgramComputerState.Closed, "old carrier still runs")
                damageCheckpoint?.let { damage ->
                    check(stopStore) { "checkpoint edits require a completed store close barrier" }
                    val checkpoint =
                        level.server
                            .getWorldPath(LevelResource.ROOT)
                            .resolve("compukters/filesystems/computers")
                            .resolve(identity.toByteArray().joinToString("") { "%02x".format(it) })
                            .resolve("execution")
                    helper.assertTrue(Files.isRegularFile(checkpoint), "running computer did not publish a checkpoint")
                    damage(checkpoint)
                }
                computer = BlockEntity.loadStatic(absolutePosition, state, payload, level.registryAccess()) as NeoForgeComputerBlockEntity
                level.setBlockEntity(computer)
                helper.assertTrue(computer.computerId() == identity, "hibernation changed ComputerId")
            }
        }
        sequence.thenExecute { computer.prepareTerminalAsync() }
        if (restartPhase == "resume") {
            awaitText("unsaved-before-between")
            input { computer.submitTerminalTextAsync("-fresh-process") }
            awaitText("unsaved-before-between-fresh-process")
            input { computer.submitTerminalKeyAsync(TerminalKey.S, TerminalKeyAction.PRESS, setOf(TerminalModifier.CONTROL)) }
            input { computer.submitTerminalKeyAsync(TerminalKey.X, TerminalKeyAction.PRESS, setOf(TerminalModifier.CONTROL)) }
            awaitText(">\n")
            input {
                computer.submitTerminalTextAsync("clear").thenCompose {
                    check(it)
                    computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
                }
            }
            awaitText(">\n", exact = true)
            input {
                computer.submitTerminalTextAsync("edit hibernation.txt").thenCompose {
                    check(it)
                    computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
                }
            }
            awaitText("unsaved-before-between-fresh-process")
            sequence
                .thenExecute {
                    LogUtils.getLogger().info(
                        "Hibernation independent Minecraft process resumed unsaved editor and saved file: {}",
                        identity,
                    )
                }.thenSucceed()
            return
        }
        awaitText(">\n", exact = true)
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
        if (restartPhase == "produce") {
            sequence
                .thenExecute {
                    val payload = computer.saveWithFullMetadata(helper.level.registryAccess())
                    Files.newOutputStream(requireNotNull(restartPayload)).use { NbtIo.writeCompressed(payload, it) }
                    LogUtils.getLogger().info("Hibernation independent Minecraft process prepared unsaved editor: {}", identity)
                }.thenSucceed()
            return
        }
        reload(stopStore = true)
        awaitText("unsaved-before-between")
        input { computer.submitTerminalTextAsync("-after") }
        input { computer.submitTerminalKeyAsync(TerminalKey.S, TerminalKeyAction.PRESS, setOf(TerminalModifier.CONTROL)) }
        input { computer.submitTerminalKeyAsync(TerminalKey.X, TerminalKeyAction.PRESS, setOf(TerminalModifier.CONTROL)) }
        awaitText(">\n")
        input {
            computer.submitTerminalTextAsync("clear").thenCompose {
                check(it)
                computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
            }
        }
        awaitText(">\n", exact = true)
        input {
            computer.submitTerminalTextAsync("edit hibernation.txt").thenCompose {
                check(it)
                computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
            }
        }
        awaitText("Compukters edit")
        awaitText("unsaved-before-between-after")
        sequence.thenExecute {
            val absolutePosition = helper.absolutePos(position)
            helper.level.server.commands.performPrefixedCommand(
                helper.level.server.createCommandSourceStack(),
                "compukters reboot ${absolutePosition.x} ${absolutePosition.y} ${absolutePosition.z}",
            )
        }
        awaitText(">\n", exact = true)
        input {
            computer.submitTerminalTextAsync("edit hibernation.txt").thenCompose {
                check(it)
                computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
            }
        }
        awaitText("Compukters edit")
        awaitText("unsaved-before-between-after")
        sequence.thenExecute { helper.assertTrue(computer.computerId() == identity, "explicit reboot changed ComputerId") }
        // Admission failure must leave a fresh usable shell and preserve the persisted /home file.
        listOf<(Path) -> Unit>(
            { checkpoint ->
                val corrupt = Files.readAllBytes(checkpoint)
                corrupt[0] = (corrupt[0].toInt() xor 1).toByte()
                Files.write(checkpoint, corrupt)
            },
            { checkpoint -> Files.delete(checkpoint) },
        ).forEach { damage ->
            reload(stopStore = true, damageCheckpoint = damage)
            awaitText(">\n", exact = true)
            input {
                computer.submitTerminalTextAsync("edit hibernation.txt").thenCompose {
                    check(it)
                    computer.submitTerminalKeyAsync(TerminalKey.ENTER, TerminalKeyAction.PRESS)
                }
            }
            awaitText("Compukters edit")
            awaitText("unsaved-before-between-after")
            sequence.thenExecute { helper.assertTrue(computer.computerId() == identity, "checkpoint recovery changed ComputerId") }
        }
        sequence.thenSucceed()
    }
}

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

package ru.lazyhat.compukterkraft.impl.computer.block

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.notebook.item.NotebookItem
import ru.lazyhat.compukterkraft.common.utils.computerDataTagCopy
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.computerLabel
import ru.lazyhat.compukterkraft.common.utils.runtimeSnapshot
import ru.lazyhat.compukterkraft.core.MOD_ID
import ru.lazyhat.compukterkraft.core.device.DeviceEvents
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice
import ru.lazyhat.compukterkraft.impl.ModRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder

@GameTestHolder(MOD_ID)
@PrefixGameTestTemplate(false)
class ComputerBlockGameTest {
    private fun placeComputer(
        helper: GameTestHelper,
        pos: BlockPos,
        stack: ItemStack = ItemStack(ModRegistry.Items.NOTEBOOK.get()),
    ) {
        val block = ModRegistry.Blocks.NOTEBOOK.get()
        val player = helper.makeMockPlayer(GameType.SURVIVAL)
        helper.setBlock(pos, block)
        block.setPlacedBy(
            helper.level,
            helper.absolutePos(pos),
            helper.getBlockState(pos),
            player,
            stack,
        )
    }

    @GameTest(template = "computer_platform", templateNamespace = MOD_ID)
    fun placingComputerCreatesComputerBlockEntity(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        val absolutePos = helper.absolutePos(pos)
        placeComputer(helper, pos)

        helper.runAfterDelay(1L) {
            helper.assertTrue(
                ComputerGameTestEnvironment.serverComputerId(helper.level, absolutePos) > 0,
                "Expected computer block to allocate a server computer id",
            )
            helper.succeed()
        }
    }

    @GameTest(template = "computer_platform", templateNamespace = MOD_ID)
    fun tickingComputerRegistersServerComputer(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        val absolutePos = helper.absolutePos(pos)
        placeComputer(helper, pos)

        helper.runAfterDelay(5L) {
            helper.assertTrue(
                ComputerGameTestEnvironment.hasRegisteredServerComputer(helper.level, absolutePos),
                "Expected placed computer block to register a server computer after ticking",
            )
            helper.succeed()
        }
    }

    @GameTest(template = "computer_platform", templateNamespace = MOD_ID)
    fun runtimeSnapshotSurvivesBlockEntityReload(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        val absolutePos = helper.absolutePos(pos)
        val marker = "reload-marker"
        placeComputer(helper, pos)

        helper.runAfterDelay(5L) {
            val computer = ComputerGameTestEnvironment.computerAt(helper.level, absolutePos)
            computer.getOrCreateRuntimeDevice().turnOn()
        }

        helper.runAfterDelay(25L) {
            val originalComputer = ComputerGameTestEnvironment.computerAt(helper.level, absolutePos)
            val expectedId =
                requireNotNull(originalComputer.computerID) {
                    "Expected placed computer block entity to keep an allocated id"
                }
            val originalDevice = originalComputer.getOrCreateRuntimeDevice()
            waitForSavedTerminalSnapshot(helper, originalComputer, "initial shell prompt") { terminal ->
                terminal.contains("INIT> ")
            }
            dispatchText(originalDevice, "echo $marker\n")
            waitForSavedTerminalSnapshot(helper, originalComputer, "echoed reload marker") { terminal ->
                terminal.contains(marker)
            }

            val savedTag = originalComputer.saveWithFullMetadata(helper.level.registryAccess())
            val savedSnapshot = savedTag.runtimeSnapshot

            helper.assertTrue(
                savedSnapshot != null && savedSnapshot.isNotEmpty(),
                "Expected live K16 runtime snapshot to be saved into block entity NBT",
            )

            helper.level.removeBlockEntity(absolutePos)
            helper.assertFalse(
                ComputerGameTestEnvironment.hasRegisteredServerComputer(helper.level, absolutePos),
                "Expected removing the block entity to release the registered runtime device",
            )

            val restoredComputer =
                requireNotNull(
                    BlockEntity.loadStatic(
                        absolutePos,
                        helper.level.getBlockState(absolutePos),
                        savedTag,
                        helper.level.registryAccess(),
                    ) as? AbstractComputerBlockEntity,
                ) {
                    "Expected saved computer NBT to load an AbstractComputerBlockEntity"
                }
            helper.level.setBlockEntity(restoredComputer)

            val reloadedComputer = ComputerGameTestEnvironment.computerAt(helper.level, absolutePos)
            helper.assertTrue(
                reloadedComputer.computerID == expectedId,
                "Expected reloaded computer to preserve id $expectedId, actual ${reloadedComputer.computerID}",
            )

            reloadedComputer.getOrCreateRuntimeDevice().turnOn()
            val restoredSnapshot =
                waitForSavedTerminalSnapshot(helper, reloadedComputer, "restored reload marker") { terminal ->
                    terminal.contains(marker)
                }
            val reloadedSnapshot = reloadedComputer.saveWithFullMetadata(helper.level.registryAccess()).runtimeSnapshot
            helper.assertTrue(
                ComputerGameTestEnvironment.hasRegisteredServerComputer(helper.level, absolutePos),
                "Expected reloaded computer to recreate a registered runtime device",
            )
            helper.assertTrue(
                reloadedSnapshot?.isNotEmpty() == true,
                "Expected reloaded computer to expose a runtime snapshot after recreation",
            )
            helper.assertTrue(
                terminalText(restoredSnapshot).contains(marker),
                "Expected restored K16 runtime terminal state to contain '$marker'",
            )
            helper.succeed()
        }
    }

    @Suppress("DEPRECATION")
    @GameTest(template = "computer_platform", templateNamespace = MOD_ID)
    fun creativeDestroyDropsComputerItemWithIdAndLabel(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        val absolutePos = helper.absolutePos(pos)
        val expectedId = 42
        val expectedLabel = "Atlas"
        val creativePlayer = helper.makeMockServerPlayerInLevel()
        val sourceStack = (ModRegistry.Items.NOTEBOOK.get() as NotebookItem).create(expectedId, expectedLabel)

        creativePlayer.abilities.instabuild = true

        placeComputer(helper, pos, sourceStack)

        helper.runAfterDelay(3L) {
            helper.assertBlockEntityData<AbstractComputerBlockEntity>(pos, {
                helper.assertTrue(
                    it.computerID == expectedId,
                    "Entity: expected placed computer block entity to have computer id $expectedId, actual ${it.computerID}",
                )
                helper.assertTrue(
                    it.label == expectedLabel,
                    "Entity: expected placed computer block entity to have computer label $expectedLabel, actual ${it.label}",
                )
                true
            }, { "" })
        }

        helper.runAfterDelay(5L) {
            helper.assertTrue(
                creativePlayer.gameMode.destroyBlock(absolutePos),
                "Expected creative mock player to destroy the placed computer block",
            )
        }

        helper.runAfterDelay(7L) {
            helper.assertItemEntityPresent(ModRegistry.Items.NOTEBOOK.get(), pos, 2.0)

            val droppedStack =
                helper
                    .getEntities(EntityType.ITEM, pos, 2.0)
                    .single { it.item.`is`(ModRegistry.Items.NOTEBOOK.get()) }
                    .item

            with(droppedStack.computerDataTagCopy()!!) {
                helper.assertTrue(
                    computerID == expectedId,
                    "Expected creative drop to preserve computer id $expectedId, actual $computerID",
                )
                helper.assertTrue(
                    computerLabel == expectedLabel,
                    "Expected creative drop to preserve computer label $expectedLabel, actual $computerLabel",
                )
            }
            helper.succeed()
        }
    }

    private fun dispatchText(
        device: RuntimeDevice,
        text: String,
    ) {
        for (byte in text.encodeToByteArray()) {
            DeviceEvents.dispatch(device, KeyInputEvent.Character(byte))
        }
    }

    private fun waitForSavedTerminalSnapshot(
        helper: GameTestHelper,
        computer: AbstractComputerBlockEntity,
        description: String,
        predicate: (String) -> Boolean,
    ): ByteArray {
        var lastTerminal = "<no snapshot>"
        repeat(80) {
            computer.serverTick()
            val snapshot = computer.saveWithFullMetadata(helper.level.registryAccess()).runtimeSnapshot
            if (snapshot != null) {
                val terminal = terminalText(snapshot)
                if (predicate(terminal)) return snapshot
                lastTerminal = terminal
            }
            Thread.sleep(10)
        }
        error("Expected $description in K16 terminal snapshot; terminal: $lastTerminal")
    }

    private fun terminalText(snapshot: ByteArray): String =
        snapshotRamBytes(snapshot, start = K16_TERMINAL_CELLS_ADDR, size = K16_TERMINAL_ROWS * K16_TERMINAL_COLUMNS)
            .map { byte -> if (byte in 0x20..0x7e) byte.toInt().toChar() else ' ' }
            .joinToString(separator = "")

    private fun snapshotRamBytes(
        snapshot: ByteArray,
        start: Int,
        size: Int,
    ): ByteArray {
        val buffer = ByteBuffer.wrap(snapshot).order(ByteOrder.LITTLE_ENDIAN)
        require(snapshot.copyOfRange(0, 8).contentEquals("K16SNAP\u0000".encodeToByteArray()))
        val headerSize = buffer.getShort(0x0A).toInt()
        val ramSize = buffer.getLong(0x10)
        require(start >= 0 && size >= 0 && start + size <= ramSize)
        return snapshot.copyOfRange(headerSize + start, headerSize + start + size)
    }
}

private const val K16_TERMINAL_CELLS_ADDR = 0x3000
private const val K16_TERMINAL_COLUMNS = 53
private const val K16_TERMINAL_ROWS = 25

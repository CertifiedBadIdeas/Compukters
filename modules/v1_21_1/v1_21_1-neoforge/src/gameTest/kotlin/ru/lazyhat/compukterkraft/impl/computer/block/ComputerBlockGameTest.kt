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
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.item.ComputerItem
import ru.lazyhat.compukterkraft.common.utils.computerDataTagCopy
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.computerLabel
import ru.lazyhat.compukterkraft.core.MOD_ID
import ru.lazyhat.compukterkraft.impl.ModRegistry

@GameTestHolder(MOD_ID)
@PrefixGameTestTemplate(false)
class ComputerBlockGameTest {
    private fun placeComputer(
        helper: GameTestHelper,
        pos: BlockPos,
        stack: ItemStack = ItemStack(ModRegistry.Items.COMPUTER_ADVANCED.get()),
    ) {
        val block = ModRegistry.Blocks.COMPUTER_ADVANCED.get()
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

    @Suppress("DEPRECATION")
    @GameTest(template = "computer_platform", templateNamespace = MOD_ID)
    fun creativeDestroyDropsComputerItemWithIdAndLabel(helper: GameTestHelper) {
        val pos = BlockPos(1, 2, 1)
        val absolutePos = helper.absolutePos(pos)
        val expectedId = 42
        val expectedLabel = "Atlas"
        val creativePlayer = helper.makeMockServerPlayerInLevel()
        val sourceStack = (ModRegistry.Items.COMPUTER_ADVANCED.get() as ComputerItem).create(expectedId, expectedLabel)

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
            helper.assertItemEntityPresent(ModRegistry.Items.COMPUTER_ADVANCED.get(), pos, 2.0)

            val droppedStack =
                helper
                    .getEntities(EntityType.ITEM, pos, 2.0)
                    .single { it.item.`is`(ModRegistry.Items.COMPUTER_ADVANCED.get()) }
                    .item

            with(droppedStack.computerDataTagCopy()!!) {
                helper.assertTrue(
                    computerID == expectedId,
                    "Expected creative drop to preserve computer id $expectedId, actual ${computerID}",
                )
                helper.assertTrue(
                    computerLabel == expectedLabel,
                    "Expected creative drop to preserve computer label $expectedLabel, actual ${computerLabel}",
                )
            }
            helper.succeed()
        }
    }
}

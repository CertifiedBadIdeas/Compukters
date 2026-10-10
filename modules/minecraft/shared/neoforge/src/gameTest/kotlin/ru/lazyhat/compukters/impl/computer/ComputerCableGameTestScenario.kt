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
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.network.ComputerCableLinks

internal object ComputerCableGameTestScenario {
    fun run(helper: GameTestHelper) {
        val player = helper.makeMockServerPlayerInLevel()
        val cable = CompuktersRegistry.PERIPHERAL_CABLE_ITEM.get()
        val computer = CompuktersRegistry.COMPUTER_ITEM.get()

        fun place(
            relative: BlockPos,
            item: Item,
            accepted: Boolean = true,
        ) {
            val floor = relative.below()
            if (helper.getBlockState(floor).isAir) helper.setBlock(floor, Blocks.STONE)
            val target = helper.absolutePos(relative)
            val support = helper.absolutePos(floor)
            player.setPos(target.x - 1.5, target.y.toDouble(), target.z + 0.5)
            val stack = ItemStack(item)
            player.setItemInHand(InteractionHand.MAIN_HAND, stack)
            player.gameMode.useItemOn(
                player,
                helper.level,
                stack,
                InteractionHand.MAIN_HAND,
                BlockHitResult(Vec3(support.x + 0.5, support.y + 1.0, support.z + 0.5), Direction.UP, support, false),
            )
            helper.assertTrue(helper.getBlockState(relative).isAir != accepted, "unexpected placement at $relative, accepted=$accepted")
            if (!accepted) helper.assertTrue(stack.count == 1, "rejected placement consumed an item")
        }
        val first = BlockPos(2, 2, 2)
        val second = BlockPos(6, 4, 4)
        place(first, computer)
        place(second, computer)
        val line =
            listOf(
                BlockPos(3, 2, 2),
                BlockPos(4, 2, 2),
                BlockPos(4, 3, 2),
                BlockPos(4, 4, 2),
                BlockPos(5, 4, 2),
                BlockPos(6, 4, 2),
                BlockPos(6, 4, 3),
            )
        line.forEach { place(it, cable) }
        helper.assertTrue(
            ComputerCableLinks.peer(helper.level, helper.absolutePos(first)) == helper.absolutePos(second),
            "turning vertical line did not connect two PCs",
        )
        place(BlockPos(4, 2, 3), cable, false)
        place(BlockPos(2, 2, 1), cable, false)
        place(BlockPos(5, 4, 3), computer, false)
        helper.setBlock(BlockPos(4, 3, 2), Blocks.AIR)
        helper.assertTrue(ComputerCableLinks.peer(helper.level, helper.absolutePos(first)) == null, "cut line retained a cached peer")
        place(BlockPos(4, 3, 2), cable)
        helper.assertTrue(
            ComputerCableLinks.peer(helper.level, helper.absolutePos(first)) == helper.absolutePos(second),
            "repaired line did not reconnect",
        )
        listOf(BlockPos(2, 6, 2), BlockPos(3, 6, 2), BlockPos(3, 6, 3)).forEach { place(it, cable) }
        place(BlockPos(2, 6, 3), cable, false)
        helper.succeed()
    }
}

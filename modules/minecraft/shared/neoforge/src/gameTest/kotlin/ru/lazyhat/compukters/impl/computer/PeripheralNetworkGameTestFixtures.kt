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
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukters.impl.registry.CompuktersRegistry
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralBindingResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorItem
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer
import java.util.UUID

internal object PeripheralNetworkGameTestFixtures {
    fun bind(
        helper: GameTestHelper,
        computer: BlockPos,
        vararg devices: BlockPos,
    ): UUID {
        val player = helper.makeMockServerPlayerInLevel()
        val stack = ItemStack(CompuktersRegistry.PERIPHERAL_CONFIGURATOR_ITEM.get())
        val item = stack.item as PeripheralConfiguratorItem
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        item.use(helper.level, player, InteractionHand.MAIN_HAND)
        for ((index, relative) in (listOf(computer) + devices).withIndex()) {
            val absolute = helper.absolutePos(relative)
            player.setPos(absolute.x + 0.5, absolute.y + 0.5, absolute.z + 0.5)
            val result = PeripheralConfiguratorServer.bind(player, InteractionHand.MAIN_HAND, absolute, Direction.WEST)
            helper.assertTrue(
                result in
                    if (index ==
                        0
                    ) {
                        setOf(PeripheralBindingResult.CREATED, PeripheralBindingResult.SELECTED)
                    } else {
                        setOf(PeripheralBindingResult.BOUND)
                    },
                "network binding failed: $result",
            )
        }
        return checkNotNull(item.selectedNetwork(stack))
    }

    fun unbind(
        helper: GameTestHelper,
        relative: BlockPos,
    ) {
        val player = helper.makeMockServerPlayerInLevel()
        val stack = ItemStack(CompuktersRegistry.PERIPHERAL_CONFIGURATOR_ITEM.get())
        val item = stack.item as PeripheralConfiguratorItem
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        item.use(helper.level, player, InteractionHand.MAIN_HAND)
        player.setShiftKeyDown(true)
        val absolute = helper.absolutePos(relative)
        player.setPos(absolute.x + 0.5, absolute.y + 0.5, absolute.z + 0.5)
        helper.assertTrue(
            PeripheralConfiguratorServer.bind(player, InteractionHand.MAIN_HAND, absolute, Direction.WEST) ==
                PeripheralBindingResult.REMOVED,
            "member removal failed",
        )
    }
}

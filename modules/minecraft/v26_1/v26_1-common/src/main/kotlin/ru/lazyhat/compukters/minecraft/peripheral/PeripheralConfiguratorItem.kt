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

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext

class PeripheralConfiguratorItem(
    properties: Properties,
) : Item(properties) {
    override fun useOn(context: UseOnContext): InteractionResult {
        if (context.level.isClientSide) return InteractionResult.SUCCESS
        val level = context.level as? ServerLevel ?: return InteractionResult.PASS
        val player = context.player as? ServerPlayer ?: return InteractionResult.PASS
        if (PeripheralCableBlocks.contains(level.getBlockState(context.clickedPos))) {
            return if (PeripheralConfiguratorServer.openCable(player, context.hand, context.clickedPos, context.clickedFace)) {
                InteractionResult.SUCCESS_SERVER
            } else {
                InteractionResult.CONSUME
            }
        }
        val identities = PeripheralDeviceNames.resolveContact(level, context.clickedPos, context.clickedFace)
        if (player.isShiftKeyDown && identities.size == 1) {
            val identity = identities.single()
            PeripheralDeviceNames.clearName(level, identity)
            player.sendSystemMessage(Component.translatable("item.compukters.peripheral_configurator.cleared"))
            return InteractionResult.SUCCESS_SERVER
        }
        if (identities.size > 1) {
            player.sendSystemMessage(Component.translatable("item.compukters.peripheral_configurator.ambiguous"))
            return InteractionResult.CONSUME
        }
        return if (
            PeripheralConfiguratorServer.openDevice(
                player,
                context.hand,
                context.clickedPos,
                context.clickedFace,
            )
        ) {
            InteractionResult.SUCCESS_SERVER
        } else {
            player.sendSystemMessage(Component.translatable("item.compukters.peripheral_configurator.missing"))
            InteractionResult.CONSUME
        }
    }
}

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

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import ru.lazyhat.compukters.minecraft.computer.ComputerBlockEntity
import java.util.UUID

class PeripheralConfiguratorItem(
    properties: Properties,
) : Item(properties) {
    private fun configuratorMode(stack: ItemStack): Int {
        val data = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return 0
        val stored = data.getInt(ASSEMBLY_MODE_KEY).orElse(-1)
        return if (stored in 0..2) {
            stored
        } else if (data.getBoolean(MODE_KEY).orElse(false)) {
            1
        } else {
            0
        }
    }

    fun networkMode(stack: ItemStack): Boolean = configuratorMode(stack) == 1

    fun selectedNetwork(stack: ItemStack): UUID? {
        val value =
            stack
                .get(DataComponents.CUSTOM_DATA)
                ?.copyTag()
                ?.getString(NETWORK_KEY)
                ?.orElse("") ?: return null
        return runCatching { UUID.fromString(value) }.getOrNull()
    }

    fun selectNetwork(
        stack: ItemStack,
        id: UUID?,
    ) {
        val data = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
        if (id == null) data.remove(NETWORK_KEY) else data.putString(NETWORK_KEY, id.toString())
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data))
    }

    override fun isFoil(stack: ItemStack): Boolean = selectedNetwork(stack) != null || super.isFoil(stack)

    override fun use(
        level: Level,
        player: net.minecraft.world.entity.player.Player,
        hand: InteractionHand,
    ): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (player is ServerPlayer) {
            if (player.isShiftKeyDown) {
                selectNetwork(stack, null)
                val data = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
                ru.lazyhat.compukters.minecraft.display.DisplayAssembly.SELECTION_KEYS
                    .forEach(data::remove)
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data))
                player.sendSystemMessage(Component.translatable("item.compukters.peripheral_configurator.selection_cleared"))
            } else {
                val data = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
                val next = (configuratorMode(stack) + 1) % 3
                data.putInt(ASSEMBLY_MODE_KEY, next)
                stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data))
                player.sendSystemMessage(
                    Component.translatable("item.compukters.peripheral_configurator.mode." + listOf("name", "network", "display")[next]),
                )
            }
        }
        return InteractionResult.SUCCESS
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        if (context.level.isClientSide) return InteractionResult.SUCCESS
        val level = context.level as? ServerLevel ?: return InteractionResult.PASS
        val player = context.player as? ServerPlayer ?: return InteractionResult.PASS
        if (configuratorMode(context.itemInHand) == 2) {
            val stack = context.itemInHand
            val data = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: CompoundTag()
            ru.lazyhat.compukters.minecraft.display.DisplayAssembly.click(
                player,
                stack,
                context.clickedPos,
                read = { key -> data.getString(key).orElse("") },
                write = { key, value -> if (value == null) data.remove(key) else data.putString(key, value) },
            )
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data))
            return InteractionResult.SUCCESS
        }
        if (networkMode(context.itemInHand)) {
            val result = PeripheralNetworkBinding.click(player, context.hand, context.clickedPos, context.clickedFace)
            player.sendSystemMessage(
                Component.translatable("item.compukters.peripheral_configurator.binding.${result.name.lowercase(java.util.Locale.ROOT)}"),
            )
            return InteractionResult.SUCCESS_SERVER
        }
        if (level.getBlockEntity(context.clickedPos) is ComputerBlockEntity) {
            if (!PeripheralConfiguratorServer.openComputer(player, context.hand, context.clickedPos, context.clickedFace)) {
                player.sendSystemMessage(Component.translatable("item.compukters.peripheral_configurator.no_network"))
            }
            return InteractionResult.CONSUME
        }
        val identities = PeripheralDeviceNames.resolveContact(level, context.clickedPos, context.clickedFace)
        if (player.isShiftKeyDown && identities.size == 1) {
            PeripheralDeviceNames.clearName(level, identities.single())
            player.sendSystemMessage(Component.translatable("item.compukters.peripheral_configurator.cleared"))
            return InteractionResult.SUCCESS_SERVER
        }
        if (identities.size > 1) {
            player.sendSystemMessage(Component.translatable("item.compukters.peripheral_configurator.ambiguous"))
            return InteractionResult.CONSUME
        }
        if (!PeripheralConfiguratorServer.openDevice(player, context.hand, context.clickedPos, context.clickedFace)) {
            player.sendSystemMessage(Component.translatable("item.compukters.peripheral_configurator.missing"))
        }
        return InteractionResult.CONSUME
    }

    companion object {
        private const val ASSEMBLY_MODE_KEY = "compukters_configurator_mode"
        private const val MODE_KEY = "compukters_network_mode"
        private const val NETWORK_KEY = "compukters_selected_network"
    }
}

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
package ru.lazyhat.compukterkraft.common.serial.item

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlock
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.block.ComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.localization.CompukterComponents
import ru.lazyhat.compukterkraft.common.utils.computerDataTagCopy
import ru.lazyhat.compukterkraft.common.utils.updateComputerDataTag
import ru.lazyhat.compukterkraft.core.Config

class SerialTerminalItem(
    properties: Properties,
) : Item(properties) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS
        val level = context.level
        val pos = context.clickedPos
        val state = level.getBlockState(pos)
        if (state.block !is AbstractComputerBlock<*>) return InteractionResult.PASS

        if (level.isClientSide) return InteractionResult.sidedSuccess(true)
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val be = level.getBlockEntity(pos) as? ComputerBlockEntity ?: return InteractionResult.PASS
        val device = be.getOrCreateRuntimeDevice()

        context.itemInHand.writeSerialBinding(
            SerialBinding(
                deviceId = device.deviceId,
                blockPos = pos.immutable(),
                dimensionId = level.dimension().location().toString(),
            ),
        )
        openFor(serverPlayer, be)
        return InteractionResult.sidedSuccess(false)
    }

    override fun use(
        level: Level,
        player: Player,
        hand: InteractionHand,
    ): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) return InteractionResultHolder.sidedSuccess(stack, true)
        val serverPlayer = player as? ServerPlayer ?: return InteractionResultHolder.pass(stack)

        val binding =
            stack.readSerialBinding() ?: run {
                serverPlayer.displayClientMessage(CompukterComponents.Message.Terminal.noBinding, true)
                return InteractionResultHolder.pass(stack)
            }
        if (binding.dimensionId != level.dimension().location().toString()) {
            serverPlayer.displayClientMessage(CompukterComponents.Message.Terminal.wrongDimension, true)
            return InteractionResultHolder.pass(stack)
        }
        val be =
            level.getBlockEntity(binding.blockPos) as? ComputerBlockEntity ?: run {
                stack.clearSerialBinding()
                return InteractionResultHolder.pass(stack)
            }
        val radius = Config.TERMINAL_CONNECT_RADIUS_BLOCKS.toDouble()
        val distSqr =
            serverPlayer.distanceToSqr(
                binding.blockPos.x + 0.5,
                binding.blockPos.y + 0.5,
                binding.blockPos.z + 0.5,
            )
        if (distSqr > radius * radius) {
            serverPlayer.displayClientMessage(CompukterComponents.Message.Terminal.outOfRange, true)
            return InteractionResultHolder.pass(stack)
        }

        openFor(serverPlayer, be)
        return InteractionResultHolder.sidedSuccess(stack, false)
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        list: MutableList<Component>,
        options: TooltipFlag,
    ) {
        val binding = stack.readSerialBinding()
        if (binding == null) {
            list.add(
                Component
                    .translatable("gui.compukterkraft.tooltip.serial_terminal_link_prompt")
                    .withStyle(ChatFormatting.DARK_GRAY),
            )
        } else {
            list.add(
                Component
                    .translatable(
                        "gui.compukterkraft.tooltip.serial_terminal_linked_computer",
                        binding.deviceId,
                        binding.blockPos.x,
                        binding.blockPos.y,
                        binding.blockPos.z,
                    ).withStyle(ChatFormatting.GRAY),
            )
        }
    }

    private fun openFor(
        serverPlayer: ServerPlayer,
        be: ComputerBlockEntity,
    ) {
        val device = be.getOrCreateRuntimeDevice()
        val displayStack =
            be.blockState.block
                .asItem()
                .defaultInstance
        ModObjects.openSerialTerminalMenu(
            serverPlayer,
            be as AbstractComputerBlockEntity,
            ComputerContainerData(device, displayStack),
        )
    }

    private data class SerialBinding(
        val deviceId: Int,
        val blockPos: BlockPos,
        val dimensionId: String,
    )

    private fun ItemStack.readSerialBinding(): SerialBinding? {
        val tag = computerDataTagCopy() ?: return null
        if (
            !tag.contains(SerialNbt.DEVICE_ID) ||
            !tag.contains(SerialNbt.BLOCK_POS) ||
            !tag.contains(SerialNbt.DIMENSION_ID)
        ) {
            return null
        }
        return SerialBinding(
            deviceId = tag.getInt(SerialNbt.DEVICE_ID),
            blockPos = BlockPos.of(tag.getLong(SerialNbt.BLOCK_POS)),
            dimensionId = tag.getString(SerialNbt.DIMENSION_ID),
        )
    }

    private fun ItemStack.writeSerialBinding(binding: SerialBinding) {
        updateComputerDataTag {
            putInt(SerialNbt.DEVICE_ID, binding.deviceId)
            putLong(SerialNbt.BLOCK_POS, binding.blockPos.asLong())
            putString(SerialNbt.DIMENSION_ID, binding.dimensionId)
        }
    }

    private fun ItemStack.clearSerialBinding() {
        updateComputerDataTag {
            remove(SerialNbt.DEVICE_ID)
            remove(SerialNbt.BLOCK_POS)
            remove(SerialNbt.DIMENSION_ID)
        }
    }

    private object SerialNbt {
        const val DEVICE_ID = "SerialTerminalDeviceId"
        const val BLOCK_POS = "SerialTerminalBlockPos"
        const val DIMENSION_ID = "SerialTerminalDimensionId"
    }
}

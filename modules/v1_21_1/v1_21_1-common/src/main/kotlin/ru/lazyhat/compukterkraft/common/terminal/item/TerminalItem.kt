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
package ru.lazyhat.compukterkraft.common.terminal.item

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.Level
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlock
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.block.ComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.localization.CompukterComponents
import ru.lazyhat.compukterkraft.common.terminal.session.TransientPairing
import ru.lazyhat.compukterkraft.core.Config

/**
 * Portable, stateless terminal. Stores no NBT. UX is spec'd as:
 *   - shift+RMB on a [AbstractComputerBlock] → open that computer's terminal UI,
 *     remember the binding in [TransientPairing] for air-RMB reopens.
 *   - shift+RMB in air → reopen the last bound computer's UI, if any and reachable.
 *   - plain RMB (no sneak) → pass-through (so the item behaves like a normal item
 *     when not sneaking; no accidental terminal spam).
 */
class TerminalItem(
    properties: Properties,
) : Item(properties) {
    override fun useOn(context: UseOnContext): InteractionResult {
        val player = context.player ?: return InteractionResult.PASS

        val level = context.level
        val pos = context.clickedPos
        val state = level.getBlockState(pos)
        if (state.block !is AbstractComputerBlock<*>) return InteractionResult.PASS

        if (level.isClientSide) {
            return InteractionResult.sidedSuccess(true)
        }
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val be = level.getBlockEntity(pos) as? ComputerBlockEntity ?: return InteractionResult.PASS

        openFor(serverPlayer, be)
        TransientPairing.set(
            serverPlayer.uuid,
            TransientPairing.Binding(
                instanceId = be.getOrCreateServerComputer().instanceID,
                blockPos = pos.immutable(),
                dimensionId = level.dimension(),
            ),
        )
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
            TransientPairing.get(serverPlayer.uuid) ?: run {
                serverPlayer.displayClientMessage(
                    CompukterComponents.Message.Terminal.noBinding,
                    true,
                )
                return InteractionResultHolder.pass(stack)
            }
        if (binding.dimensionId != level.dimension()) {
            serverPlayer.displayClientMessage(
                CompukterComponents.Message.Terminal.wrongDimension,
                true,
            )
            return InteractionResultHolder.pass(stack)
        }
        val be =
            level.getBlockEntity(binding.blockPos) as? ComputerBlockEntity ?: run {
                // Block removed between binding and reopening — drop the binding.
                TransientPairing.clear(serverPlayer.uuid)
                return InteractionResultHolder.pass(stack)
            }
        // Task 3.5 — radius enforcement. Cross-dimensional access is already
        // excluded above; here we reject reopens from farther than
        // `TERMINAL_CONNECT_RADIUS_BLOCKS` blocks away.
        val radius = Config.TERMINAL_CONNECT_RADIUS_BLOCKS.toDouble()
        val distSqr =
            serverPlayer.distanceToSqr(
                binding.blockPos.x + 0.5,
                binding.blockPos.y + 0.5,
                binding.blockPos.z + 0.5,
            )
        if (distSqr > radius * radius) {
            serverPlayer.displayClientMessage(
                CompukterComponents.Message.Terminal.outOfRange,
                true,
            )
            return InteractionResultHolder.pass(stack)
        }
        openFor(serverPlayer, be)
        return InteractionResultHolder.sidedSuccess(stack, false)
    }

    private fun openFor(
        serverPlayer: ServerPlayer,
        be: ComputerBlockEntity,
    ) {
        val serverComputer = be.getOrCreateServerComputer()
        val displayStack =
            be.blockState.block
                .asItem()
                .defaultInstance
        ModObjects.openComputerMenu(
            serverPlayer,
            be as AbstractComputerBlockEntity,
            ComputerContainerData(serverComputer, displayStack),
        )
    }
}

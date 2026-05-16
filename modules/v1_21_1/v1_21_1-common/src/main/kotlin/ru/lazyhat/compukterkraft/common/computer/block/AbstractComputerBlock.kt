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

package ru.lazyhat.compukterkraft.common.computer.block

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.BlockHitResult
import ru.lazyhat.compukterkraft.common.computer.context.ServerContext
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.computer.item.AbstractComputerItem
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.utils.castTicker
import ru.lazyhat.compukterkraft.common.utils.computerDataTagCopy
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.computerLabel
import ru.lazyhat.compukterkraft.common.utils.ifServerSide
import ru.lazyhat.compukterkraft.core.MOD_ID

abstract class AbstractComputerBlock<T : AbstractComputerBlockEntity>(
    properties: Properties,
) : HorizontalDirectionalBlock(properties),
    EntityBlock {
    companion object {
        val drop: ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, "computer")

        val serverTicker =
            BlockEntityTicker<AbstractComputerBlockEntity> { _, _, _, computer ->
                computer.serverTick()
            }
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = serverTicker.ifServerSide(level)?.castTicker()

    protected abstract fun blockEntityType(): BlockEntityType<T>

    abstract fun getItem(tile: AbstractComputerBlockEntity): ItemStack

    override fun setPlacedBy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        placer: LivingEntity?,
        stack: ItemStack,
    ) {
        super.setPlacedBy(level, pos, state, placer, stack)

        level
            .getBlockEntity(pos)
            ?.ifServerSide(level)
            ?.let { it as? AbstractComputerBlockEntity }
            ?.takeIf { stack.item is AbstractComputerItem }
            ?.let { tile ->
                stack
                    .computerDataTagCopy()
                    ?.also {
                        tile.computerID = it.computerID
                        tile.label = it.computerLabel
                    }

                val resolvedComputerId =
                    tile.computerID ?: ServerContext.allocateDeviceId().also { tile.computerID = it }

                ServerContext.deviceManager.ensureWorkspaceInitialized(resolvedComputerId)
            }
    }

    override fun newBlockEntity(
        pos: BlockPos,
        state: BlockState,
    ): BlockEntity? = blockEntityType().create(pos, state)

    override fun playerDestroy(
        level: Level,
        player: Player,
        pos: BlockPos,
        state: BlockState,
        blockEntity: BlockEntity?,
        tool: ItemStack,
    ) {
        player.awardStat(Stats.BLOCK_MINED.get(this))
        player.causeFoodExhaustion(0.005f)
    }

    override fun playerWillDestroy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        player: Player,
    ): BlockState {
        val replacementState = super.playerWillDestroy(level, pos, state, player)
        ifServerSide(level) {
            dropResources(state, level, pos, level.getBlockEntity(pos))
        }
        return replacementState
    }

    @Deprecated("Deprecated")
    override fun getDrops(
        state: BlockState,
        params: LootParams.Builder,
    ): List<ItemStack> {
        val computerBlockEntity =
            params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) as? AbstractComputerBlockEntity
        return super.getDrops(
            state,
            computerBlockEntity
                ?.let {
                    params.withDynamicDrop(drop) { consumer ->
                        consumer.accept(getItem(it))
                    }
                } ?: params,
        )
    }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): ItemInteractionResult = ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.sidedSuccess(true)
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val tile = level.getBlockEntity(pos) as? AbstractComputerBlockEntity ?: return InteractionResult.PASS
        val device = tile.getOrCreateRuntimeDevice()
        if (player.isShiftKeyDown || device.isOn) {
            ModObjects.openComputerControlMenu(
                serverPlayer,
                tile,
                ComputerContainerData(device, getItem(tile)),
            )
        } else {
            device.turnOn()
        }
        return InteractionResult.sidedSuccess(false)
    }
}

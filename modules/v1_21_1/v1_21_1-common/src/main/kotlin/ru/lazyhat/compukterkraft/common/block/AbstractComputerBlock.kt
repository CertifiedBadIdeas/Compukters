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

package ru.lazyhat.compukterkraft.common.block

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
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.context.ServerContext
import ru.lazyhat.compukterkraft.common.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.item.AbstractComputerItem
import ru.lazyhat.compukterkraft.common.utils.castTicker
import ru.lazyhat.compukterkraft.common.utils.computerDataTag
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.computerLabel
import ru.lazyhat.compukterkraft.common.utils.ifServerSide
import ru.lazyhat.compukterkraft.core.LOGGER
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
                tile.computerID = stack.computerDataTag?.computerID
                tile.label = stack.computerDataTag?.computerLabel
                val resolvedComputerId = tile.computerID ?: ServerContext.allocateComputerId().also { tile.computerID = it }
                ServerContext.computerManager.ensureWorkspaceInitialized(resolvedComputerId)
                LOGGER.info { "Computer: ${tile.computerID}, ${tile.label} placed" }
                LOGGER.info { "HN: ${stack.hoverName}" }
                LOGGER.info { "Tag: ${stack.computerDataTag}" }
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
        with(player) {
            awardStat(Stats.BLOCK_MINED.get(this@AbstractComputerBlock))
            causeFoodExhaustion(0.005f)
        }
    }

    override fun playerWillDestroy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        player: Player,
    ): BlockState {
        ifServerSide(level) {
            dropResources(state, level, pos, level.getBlockEntity(pos))
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    @Deprecated("Deprecated")
    override fun getDrops(
        state: BlockState,
        params: LootParams.Builder,
    ): List<ItemStack> =
        super
            .getDrops(
                state,
                (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) as? AbstractComputerBlockEntity)
                    ?.let { computerBlockEntity ->
                        params.withDynamicDrop(drop) { it.accept(getItem(computerBlockEntity)) }
                    } ?: params,
            )

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
        if (!player.isCrouching) {
            (level.getBlockEntity(pos) as? AbstractComputerBlockEntity)?.run {
                ifServerSide(level)
                    ?.let { computer ->
                        val serverComputer = computer.getOrCreateServerComputer()
                        ModObjects.openComputerMenu(
                            player as ServerPlayer,
                            computer,
                            ComputerContainerData(serverComputer, getItem(computer)),
                        )
                        return InteractionResult.sidedSuccess(level.isClientSide)
                    }
            }
        }

        return super.useWithoutItem(state, level, pos, player, hit)
    }
}

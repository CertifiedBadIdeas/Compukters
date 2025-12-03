package ru.lazyhat.compuktercraft.block

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.stats.Stats
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
import net.minecraftforge.registries.RegistryObject
import ru.lazyhat.compuktercraft.CompukterCraftMod
import ru.lazyhat.compuktercraft.utils.castTicker
import ru.lazyhat.compuktercraft.utils.computerID
import ru.lazyhat.compuktercraft.utils.computerLabel
import ru.lazyhat.compuktercraft.utils.ifServerSide

abstract class AbstractComputerBlock<T : AbstractComputerBlockEntity>(
    private val type: RegistryObject<BlockEntityType<T>>,
    properties: Properties,
) : HorizontalDirectionalBlock(properties),
    EntityBlock {
    companion object {
        val drop: ResourceLocation = ResourceLocation.fromNamespaceAndPath(CompukterCraftMod.ID, "computer")

        val serverTicker =
            BlockEntityTicker<AbstractComputerBlockEntity> { level, pos, state, computer ->
                computer.serverTick()
            }
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = serverTicker.ifServerSide(level)?.castTicker()

    abstract fun getItem(tile: AbstractComputerBlockEntity): ItemStack?

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
            ?.let { tile ->
                tile.computerID = stack.tag?.computerID
                tile.label = stack.tag?.computerLabel
            }
    }

    override fun newBlockEntity(
        pos: BlockPos,
        state: BlockState,
    ): BlockEntity? = type.get().create(pos, state)

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
    ) {
        super.playerWillDestroy(level, pos, state, player)
        if (level !is ServerLevel) return

        dropResources(state, level, pos, level.getBlockEntity(pos))
    }

    @Deprecated("Deprecated")
    override fun getDrops(
        state: BlockState,
        params: LootParams.Builder,
    ): List<ItemStack> =
        super.getDrops(
            state,
            (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) as? AbstractComputerBlockEntity)
                ?.let { computerBlockEntity ->
                    params.withDynamicDrop(drop) { it.accept(getItem(computerBlockEntity)) }
                } ?: params,
        )
}

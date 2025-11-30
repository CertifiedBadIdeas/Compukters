package ru.lazyhat.compuktercraft.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.stats.Stats
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraftforge.registries.RegistryObject
import ru.lazyhat.compuktercraft.CompukterCraftMod
import ru.lazyhat.compuktercraft.item.ComputerItem

class ComputerBlock(
    properties: Properties,
    val type: RegistryObject<BlockEntityType<ComputerBlockEntity>>,
) : HorizontalDirectionalBlock(properties),
    EntityBlock {
    companion object {
        val state: EnumProperty<ComputerState> = EnumProperty.create("state", ComputerState::class.java)
        val facing: DirectionProperty = BlockStateProperties.HORIZONTAL_FACING
        val drop: ResourceLocation = ResourceLocation.fromNamespaceAndPath(CompukterCraftMod.ID, "computer")
    }

    init {
        registerDefaultState(
            defaultBlockState()
                .setValue(facing, Direction.NORTH)
                .setValue(state, ComputerState.OFF),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(facing, state)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(facing, context.horizontalDirection.opposite)

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
            awardStat(Stats.BLOCK_MINED.get(this@ComputerBlock))
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

    fun getItem(tile: ComputerBlockEntity): ItemStack? {
        val item = asItem()
        if (item !is ComputerItem) return null

        return item.create(tile.computerId, tile.label)
    }

    @Deprecated("Deprecated")
    override fun getDrops(
        state: BlockState,
        params: LootParams.Builder,
    ): List<ItemStack> {
        val resultParams =
            (params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) as? ComputerBlockEntity)
                ?.let { computerBlockEntity ->
                    params.withDynamicDrop(drop) {
                        it.accept(getItem(computerBlockEntity))

                        CompukterCraftMod.LOGGER.info("GET DROPS ACCEPT")
                    }
                } ?: params

        CompukterCraftMod.LOGGER.info("GET DROPS")

        return super.getDrops(state, resultParams)
    }
}

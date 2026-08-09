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

package ru.lazyhat.compukterkraft.common.notebook.block

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlock
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.block.ComputerState
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.notebook.item.NotebookItem
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import java.util.function.Supplier

internal val NOTEBOOK_DEVICE_FAMILY_CODEC: Codec<DeviceFamily> =
    Codec.STRING.flatXmap(
        { family ->
            when (family) {
                "normal" -> DataResult.success(DeviceFamily.NORMAL)
                "advanced" -> DataResult.success(DeviceFamily.ADVANCED)
                else -> DataResult.error(Supplier { "unsupported Notebook device family: $family" })
            }
        },
        { family ->
            when (family) {
                DeviceFamily.NORMAL -> DataResult.success("normal")
                DeviceFamily.ADVANCED -> DataResult.success("advanced")
                DeviceFamily.COMMAND -> DataResult.error(Supplier { "unsupported Notebook device family: command" })
            }
        },
    )

internal fun requireNotebookDeviceFamily(family: DeviceFamily): DeviceFamily {
    require(family == DeviceFamily.NORMAL || family == DeviceFamily.ADVANCED) {
        "unsupported Notebook device family: ${family.name.lowercase()}"
    }
    return family
}

class NotebookBlock(
    properties: Properties,
    deviceFamily: DeviceFamily,
) : AbstractComputerBlock<NotebookBlockEntity>(properties) {
    val deviceFamily: DeviceFamily = requireNotebookDeviceFamily(deviceFamily)

    companion object {
        private val CODEC: MapCodec<NotebookBlock> =
            RecordCodecBuilder.mapCodec { instance ->
                instance
                    .group(
                        propertiesCodec(),
                        NOTEBOOK_DEVICE_FAMILY_CODEC
                            .fieldOf("device_family")
                            .forGetter(NotebookBlock::deviceFamily),
                    ).apply(instance, ::NotebookBlock)
            }
    }

    init {
        registerDefaultState(
            defaultBlockState()
                .setValue(AbstractComputerBlock.facing, Direction.NORTH)
                .setValue(AbstractComputerBlock.state, ComputerState.OFF),
        )
    }

    override fun blockEntityType(): BlockEntityType<out NotebookBlockEntity> = ModObjects.notebookBlockEntityType()

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(AbstractComputerBlock.facing, AbstractComputerBlock.state)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(AbstractComputerBlock.facing, context.horizontalDirection.opposite)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    override fun codec(): MapCodec<out NotebookBlock> = CODEC

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.sidedSuccess(true)
        val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
        val tile = level.getBlockEntity(pos) as? NotebookBlockEntity ?: return InteractionResult.PASS
        val device = tile.getOrCreateRuntimeDevice()

        ModObjects.openComputerMenu(
            serverPlayer,
            tile,
            ComputerContainerData(device, getItem(tile)),
        )
        return InteractionResult.sidedSuccess(false)
    }

    override fun getItem(tile: AbstractComputerBlockEntity): ItemStack {
        check(tile is NotebookBlockEntity) { "NotebookBlock requires NotebookBlockEntity" }
        check(tile.family == deviceFamily) {
            "NotebookBlock family ${deviceFamily.name} does not match block entity family ${tile.family.name}"
        }
        val item = asItem() as? NotebookItem ?: error("NotebookBlock requires NotebookItem")
        check(item.deviceFamily == deviceFamily) {
            "NotebookBlock family ${deviceFamily.name} does not match item family ${item.deviceFamily.name}"
        }
        return item.create(tile.computerID, tile.label)
    }
}

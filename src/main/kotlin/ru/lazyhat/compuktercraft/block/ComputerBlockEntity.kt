package ru.lazyhat.compuktercraft.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compuktercraft.ModRegistry
import ru.lazyhat.compuktercraft.computer.ComputerProperties
import ru.lazyhat.compuktercraft.computer.ServerComputer
import ru.lazyhat.compuktercraft.menu.ComputerMenuWithoutInventory

class ComputerBlockEntity(
    type: BlockEntityType<out ComputerBlockEntity>,
    pos: BlockPos,
    state: BlockState,
    family: ComputerFamily,
) : AbstractComputerBlockEntity(type, pos, state, family) {
    override fun createComputer(id: Int): ServerComputer =
        ServerComputer(
            id,
            level as ServerLevel,
            blockPos,
            ComputerProperties(
                family,
                label,
            ),
        )

    override fun updateBlockState(newState: ComputerState) {
        blockState
            .takeIf { it.getValue(ComputerBlock.state) != newState }
            ?.let {
                level?.setBlock(
                    blockPos,
                    blockState.setValue(ComputerBlock.state, newState),
                    Block.UPDATE_CLIENTS,
                )
            }
    }

    override fun createMenu(
        containerId: Int,
        playerInventory: Inventory,
        player: Player,
    ): AbstractContainerMenu =
        ComputerMenuWithoutInventory(
            ModRegistry.Menus.COMPUTER.get(),
            containerId,
            playerInventory,
            createServerComputer(),
        ).also {
            // CompukterCraftMod.LOGGER.info("ComputerID: ${it.getComputerPublic().instanceID} createMenu()")
        }
}

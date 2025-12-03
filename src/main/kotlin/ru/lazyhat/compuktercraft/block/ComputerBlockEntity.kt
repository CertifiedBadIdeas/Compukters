package ru.lazyhat.compuktercraft.block

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

class ComputerBlockEntity(
    type: BlockEntityType<out ComputerBlockEntity>,
    pos: BlockPos,
    state: BlockState,
    family: ComputerFamily,
) : AbstractComputerBlockEntity(type, pos, state, family) {
    override fun createMenu(
        containerId: Int,
        playerInventory: Inventory,
        player: Player,
    ): AbstractContainerMenu? {
        TODO("Not yet implemented")
    }
}

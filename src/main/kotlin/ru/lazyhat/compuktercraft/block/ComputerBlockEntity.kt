package ru.lazyhat.compuktercraft.block

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compuktercraft.CompukterCraftMod
import ru.lazyhat.compuktercraft.ModRegistry
import ru.lazyhat.compuktercraft.menu.ComputerMenuWithoutInventory

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
    ): AbstractContainerMenu =
        ComputerMenuWithoutInventory(
            ModRegistry.Menus.COMPUTER.get(),
            containerId,
            playerInventory,
            createServerComputer(),
        ).also {
            CompukterCraftMod.LOGGER.info("ComputerID: ${it.getComputerPublic().instanceID} createMenu()")
        }
}

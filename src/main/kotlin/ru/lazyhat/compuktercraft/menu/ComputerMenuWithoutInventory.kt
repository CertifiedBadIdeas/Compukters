package ru.lazyhat.compuktercraft.menu

import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compuktercraft.CompukterCraftMod
import ru.lazyhat.compuktercraft.block.ComputerFamily
import ru.lazyhat.compuktercraft.computer.ServerComputer
import ru.lazyhat.compuktercraft.data.ComputerContainerData

class ComputerMenuWithoutInventory(
    menuType: MenuType<out AbstractComputerMenu>,
    containerId: Int,
    playerInventory: Inventory,
    family: ComputerFamily,
    computer: ServerComputer?,
    menuData: ComputerContainerData?,
) : AbstractComputerMenu(
        menuType,
        containerId,
        { true },
        family,
        computer,
        menuData,
    ) {
    constructor(
        menuType: MenuType<out AbstractComputerMenu>,
        containerId: Int,
        playerInventory: Inventory,
        menuData: ComputerContainerData,
    ) : this(
        menuType,
        containerId,
        playerInventory,
        menuData.family,
        null,
        menuData,
    )

    constructor(
        menuType: MenuType<out AbstractComputerMenu>,
        containerId: Int,
        playerInventory: Inventory,
        computer: ServerComputer,
    ) : this(
        menuType,
        containerId,
        playerInventory,
        computer.family,
        computer,
        null,
    )

    init {
        CompukterCraftMod.LOGGER.info("ComputerMenuWithoutInventory constructor invoked")
        repeat(10) {
            addSlot(
                object : Slot(playerInventory, it, 0, 0) {
                    override fun mayPlace(stack: ItemStack): Boolean = false

                    override fun mayPickup(player: Player): Boolean = false

                    override fun isActive(): Boolean = false
                },
            )
        }
    }

    override fun quickMoveStack(
        player: Player,
        index: Int,
    ): ItemStack = ItemStack.EMPTY
}

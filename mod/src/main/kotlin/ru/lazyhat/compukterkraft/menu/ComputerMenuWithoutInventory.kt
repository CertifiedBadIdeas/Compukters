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

package ru.lazyhat.compukterkraft.menu

import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.block.ComputerFamily
import ru.lazyhat.compukterkraft.computer.ServerComputer
import ru.lazyhat.compukterkraft.data.ComputerContainerData

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
        // compukterkraftMod.LOGGER.info("ComputerMenuWithoutInventory constructor invoked")
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

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

package ru.lazyhat.compukterkraft.common.workbench.menu

import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.common.workbench.context.ServerWorkbench
import ru.lazyhat.compukterkraft.common.workbench.data.WorkbenchContainerData

class WorkbenchMenuWithoutInventory(
    menuType: MenuType<*>,
    containerId: Int,
    playerInventory: Inventory,
    containerData: WorkbenchContainerData,
    serverWorkbench: ServerWorkbench? = null,
    private val onTargetStackChanged: ((ItemStack) -> Unit)? = null,
) : AbstractWorkbenchMenu(menuType, containerId, containerData, serverWorkbench) {
    private val targetContainer =
        SimpleContainer(1).apply {
            setItem(0, containerData.displayStack.copy())
        }

    init {
        addSlot(
            object : WorkbenchPositionableSlot(targetContainer, 0, 12, 7) {
                override fun mayPlace(stack: ItemStack): Boolean {
                    val descriptor = ServerWorkbench.extractTargetDescriptor(stack)
                    return descriptor.computerId != null || descriptor.familyId != null
                }

                override fun mayPickup(player: Player): Boolean = true

                override fun isActive(): Boolean = true

                override fun setChanged() {
                    super.setChanged()
                    syncTargetStack()
                }

                override fun onTake(
                    player: Player,
                    stack: ItemStack,
                ) {
                    super.onTake(player, stack)
                    syncTargetStack()
                }
            },
        )

        for (row in 0 until 3) {
            for (column in 0 until 9) {
                val inventoryIndex = column + row * 9 + 9
                addSlot(WorkbenchPositionableSlot(playerInventory, inventoryIndex, 0, 0))
            }
        }

        for (column in 0 until 9) {
            addSlot(WorkbenchPositionableSlot(playerInventory, column, 0, 0))
        }
    }

    override fun quickMoveStack(
        player: Player,
        index: Int,
    ): ItemStack = ItemStack.EMPTY

    private fun syncTargetStack() {
        val stack = targetContainer.getItem(0).copy()
        serverWorkbench?.setTarget(stack)
        onTargetStackChanged?.invoke(stack)
        refreshFromServerWorkbench()
    }
}

open class WorkbenchPositionableSlot(
    container: Container,
    index: Int,
    x: Int,
    y: Int,
) : Slot(container, index, x, y) {
    fun relocate(
        x: Int,
        y: Int,
    ) {
        xField.setInt(this, x)
        yField.setInt(this, y)
    }

    private companion object {
        val xField = Slot::class.java.getDeclaredField("x").apply { isAccessible = true }
        val yField = Slot::class.java.getDeclaredField("y").apply { isAccessible = true }
    }
}
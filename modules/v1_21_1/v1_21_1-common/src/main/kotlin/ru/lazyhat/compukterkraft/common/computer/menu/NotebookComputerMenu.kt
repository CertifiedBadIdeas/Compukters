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

package ru.lazyhat.compukterkraft.common.computer.menu

import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.common.computer.module.SdkModuleBay
import ru.lazyhat.compukterkraft.common.computer.module.sdkArtifactIdentity
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice

class NotebookComputerMenu private constructor(
    menuType: MenuType<out AbstractComputerMenu>,
    containerId: Int,
    playerInventory: Container,
    family: DeviceFamily,
    computer: RuntimeDevice?,
    menuData: ComputerContainerData?,
    moduleContainer: Container,
    private val serverModuleBay: SdkModuleBay?,
    onRemoved: (() -> Unit)? = null,
) : AbstractComputerMenu(
        menuType,
        containerId,
        { true },
        family,
        computer,
        menuData,
        onRemoved,
    ) {
    private val moduleSlot = addSlot(ModuleSlot(moduleContainer))

    val moduleStack: ItemStack
        get() = moduleSlot.item

    /** Client constructor. Slot contents are synchronized by the container protocol. */
    constructor(
        menuType: MenuType<out AbstractComputerMenu>,
        containerId: Int,
        playerInventory: Inventory,
        menuData: ComputerContainerData,
    ) : this(
        menuType = menuType,
        containerId = containerId,
        playerInventory = playerInventory,
        family = menuData.family,
        computer = null,
        menuData = menuData,
        moduleContainer = SimpleContainer(1),
        serverModuleBay = null,
    )

    /** Server constructor. Uses the block entity's authoritative module bay. */
    constructor(
        menuType: MenuType<out AbstractComputerMenu>,
        containerId: Int,
        playerInventory: Inventory,
        computer: RuntimeDevice,
        moduleBay: SdkModuleBay,
        onRemoved: (() -> Unit)? = null,
    ) : this(
        menuType = menuType,
        containerId = containerId,
        playerInventory = playerInventory,
        family = computer.family,
        computer = computer,
        menuData = null,
        moduleContainer = moduleBay,
        serverModuleBay = moduleBay,
        onRemoved = onRemoved,
    )

    internal constructor(
        menuType: MenuType<out AbstractComputerMenu>,
        containerId: Int,
        playerInventory: Container,
        family: DeviceFamily,
        moduleBay: SdkModuleBay,
    ) : this(
        menuType = menuType,
        containerId = containerId,
        playerInventory = playerInventory,
        family = family,
        computer = null,
        menuData = null,
        moduleContainer = moduleBay,
        serverModuleBay = moduleBay,
    )

    init {
        repeat(3) { row ->
            repeat(9) { column ->
                addSlot(
                    Slot(
                        playerInventory,
                        column + row * 9 + 9,
                        PLAYER_INVENTORY_X + column * SLOT_STRIDE,
                        PLAYER_INVENTORY_Y + row * SLOT_STRIDE,
                    ),
                )
            }
        }
        repeat(9) { column ->
            addSlot(
                Slot(
                    playerInventory,
                    column,
                    PLAYER_INVENTORY_X + column * SLOT_STRIDE,
                    HOTBAR_Y,
                ),
            )
        }
    }

    override fun quickMoveStack(
        player: Player,
        index: Int,
    ): ItemStack = quickMoveStack(index)

    internal fun quickMoveStack(index: Int): ItemStack {
        if (index !in slots.indices) return ItemStack.EMPTY
        val moduleBay = serverModuleBay ?: return ItemStack.EMPTY
        val sourceSlot = slots[index]
        if (!sourceSlot.hasItem()) return ItemStack.EMPTY
        val source = sourceSlot.item
        val original = source.copy()

        if (index == MODULE_SLOT_INDEX) {
            val removed = moduleBay.removeItemNoUpdate(0)
            if (removed.isEmpty) return ItemStack.EMPTY
            val moving = removed.copy()
            if (!moveItemStackTo(moving, PLAYER_SLOT_START, PLAYER_SLOT_END_EXCLUSIVE, true) || !moving.isEmpty) {
                check(moduleBay.setFromPlayer(removed)) { "Cannot roll back K16 SDK module quick move" }
                return ItemStack.EMPTY
            }
        } else {
            val single = source.copyWithCount(1)
            if (!moduleSlot.mayPlace(single) || !moduleBay.setFromPlayer(single)) return ItemStack.EMPTY
            source.shrink(1)
            if (source.isEmpty) {
                sourceSlot.set(ItemStack.EMPTY)
            } else {
                sourceSlot.setChanged()
            }
        }
        return original
    }

    private inner class ModuleSlot(
        container: Container,
    ) : Slot(container, 0, MODULE_SLOT_X, MODULE_SLOT_Y) {
        override fun mayPlace(stack: ItemStack): Boolean {
            if (stack.isEmpty || moduleStack.isEmpty.not()) return false
            val single = stack.copyWithCount(1)
            val identity = single.sdkArtifactIdentity ?: return false
            return !isComputerOn &&
                ModObjects.isKnownSdkArtifactIdentity(identity) &&
                (serverModuleBay?.canPlaceItem(0, single) ?: true)
        }

        override fun mayPickup(player: Player): Boolean = !isComputerOn

        override fun getMaxStackSize(): Int = 1

        override fun getMaxStackSize(stack: ItemStack): Int = 1

        override fun safeInsert(
            stack: ItemStack,
            increment: Int,
        ): ItemStack {
            val moduleBay = serverModuleBay ?: return super.safeInsert(stack, increment)
            if (increment <= 0 || !mayPlace(stack)) return stack
            val single = stack.copyWithCount(1)
            if (moduleBay.setFromPlayer(single)) stack.shrink(1)
            return stack
        }

        override fun set(stack: ItemStack) {
            val moduleBay = serverModuleBay
            if (moduleBay == null) {
                super.set(stack)
            } else if (stack.isEmpty) {
                moduleBay.removeItemNoUpdate(0)
            } else {
                moduleBay.setFromPlayer(stack.copyWithCount(1))
            }
        }

        override fun remove(amount: Int): ItemStack = serverModuleBay?.removeItem(0, amount) ?: super.remove(amount)
    }

    companion object {
        const val MODULE_SLOT_X: Int = 112
        const val MODULE_SLOT_Y: Int = 253
        const val PLAYER_INVENTORY_X: Int = 159
        const val PLAYER_INVENTORY_Y: Int = 290
        const val HOTBAR_Y: Int = 348
        private const val SLOT_STRIDE: Int = 18
        private const val MODULE_SLOT_INDEX: Int = 0
        private const val PLAYER_SLOT_START: Int = 1
        private const val PLAYER_SLOT_END_EXCLUSIVE: Int = 1 + Inventory.INVENTORY_SIZE
    }
}

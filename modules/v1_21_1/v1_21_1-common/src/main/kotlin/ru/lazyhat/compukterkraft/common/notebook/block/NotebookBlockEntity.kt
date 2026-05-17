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

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.computer.block.ComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenuWithoutInventory
import ru.lazyhat.compukterkraft.core.block.DeviceFamily

open class NotebookBlockEntity(
    type: BlockEntityType<out ComputerBlockEntity>,
    pos: BlockPos,
    state: BlockState,
) : ComputerBlockEntity(type, pos, state, DeviceFamily.NORMAL) {
    companion object {
        const val NOTEBOOK_LID_EVENT: Int = 1
        const val NOTEBOOK_LID_CLOSED: Int = 0
        const val NOTEBOOK_LID_OPEN: Int = 1
    }

    private var notebookMenuViewers: Int = 0

    override fun createMenu(
        containerId: Int,
        playerInventory: Inventory,
        player: Player,
    ): AbstractContainerMenu =
        ComputerMenuWithoutInventory(
            ModObjects.computerMenuType(),
            containerId,
            playerInventory,
            getOrCreateRuntimeDevice(),
            onRemoved = ::notebookMenuClosed,
        ).also {
            notebookMenuOpened()
        }

    fun notebookMenuOpened() {
        val level = level ?: return
        if (level.isClientSide) return

        if (notebookMenuViewers == 0) {
            publishNotebookLidState(open = true)
        }
        notebookMenuViewers += 1
    }

    fun notebookMenuClosed() {
        val level = level ?: return
        if (level.isClientSide || notebookMenuViewers == 0) return

        notebookMenuViewers -= 1
        if (notebookMenuViewers == 0) {
            publishNotebookLidState(open = false)
        }
    }

    private fun publishNotebookLidState(open: Boolean) {
        level?.blockEvent(
            blockPos,
            blockState.block,
            NOTEBOOK_LID_EVENT,
            if (open) NOTEBOOK_LID_OPEN else NOTEBOOK_LID_CLOSED,
        )
    }
}

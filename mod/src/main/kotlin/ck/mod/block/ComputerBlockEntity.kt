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

package ck.mod.block

import ck.mod.LOGGER
import ck.mod.ModRegistry
import ck.mod.computer.ComputerProperties
import ck.mod.computer.ServerComputer
import ck.mod.menu.ComputerMenuWithoutInventory
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

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
            getOrCreateServerComputer(),
        ).also {
            LOGGER.info { "ComputerID: ${it.getComputerPublic().instanceID} createMenu" }
        }
}

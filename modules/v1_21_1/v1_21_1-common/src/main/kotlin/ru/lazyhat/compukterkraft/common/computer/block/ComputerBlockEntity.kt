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

package ru.lazyhat.compukterkraft.common.computer.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import ru.lazyhat.compukterkraft.common.binding.ModObjects
import ru.lazyhat.compukterkraft.common.computer.context.BlockEntityRuntimeDeviceHost
import ru.lazyhat.compukterkraft.common.computer.context.ServerContext
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenuWithoutInventory
import ru.lazyhat.compukterkraft.core.LOGGER
import ru.lazyhat.compukterkraft.core.block.DeviceFamily
import ru.lazyhat.compukterkraft.core.device.DeviceProperties
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDevice
import ru.lazyhat.compukterkraft.core.device.runtime.RuntimeDeviceImpl

open class ComputerBlockEntity(
    type: BlockEntityType<out ComputerBlockEntity>,
    pos: BlockPos,
    state: BlockState,
    family: DeviceFamily,
) : AbstractComputerBlockEntity(type, pos, state, family) {
    override fun createComputer(id: Int): RuntimeDevice {
        val serverLevel = level as ServerLevel
        val host = BlockEntityRuntimeDeviceHost(serverLevel, this)
        return RuntimeDeviceImpl(
            deviceId = id,
            properties = DeviceProperties(family, label),
            manager = ServerContext.deviceManager,
            gameTime = host.gameTime,
            terminalNetwork = host.terminalNetwork,
            displayNetwork = host.displayNetwork,
            stateSink = host.stateSink,
        )
    }

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
            ModObjects.computerMenuType(),
            containerId,
            playerInventory,
            getOrCreateRuntimeDevice(),
        ).also {
            LOGGER.debug { "DeviceID: ${it.serverSide.device.deviceId} createMenu" }
        }
}

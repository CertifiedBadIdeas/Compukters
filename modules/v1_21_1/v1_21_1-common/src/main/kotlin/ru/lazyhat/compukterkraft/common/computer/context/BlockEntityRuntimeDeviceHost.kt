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

package ru.lazyhat.compukterkraft.common.computer.context

import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.menu.ComputerMenu
import ru.lazyhat.compukterkraft.common.computer.network.retained.RetainedDisplayStateClientMessage
import ru.lazyhat.compukterkraft.common.network.ServerNetworking
import ru.lazyhat.compukterkraft.common.notebook.block.NotebookBlockEntity
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DeviceStateSink
import ru.lazyhat.compukterkraft.core.device.runtime.ports.DisplayNetworkBridge
import ru.lazyhat.compukterkraft.core.device.runtime.ports.GameTimeSource
import java.util.UUID

/**
 * Adapts an in-world [AbstractComputerBlockEntity] living in [level] to the
 * narrow host ports a `:core` runtime device depends on.
 *
 * One host instance is constructed per runtime device.
 */
class BlockEntityRuntimeDeviceHost(
    private val level: ServerLevel,
    private val blockEntity: AbstractComputerBlockEntity,
) {
    val gameTime: GameTimeSource = GameTimeSource { level.gameTime }

    val displayNetwork: DisplayNetworkBridge =
        object : DisplayNetworkBridge {
            override fun isRetainedDisplayViewerAuthorized(
                playerUuid: UUID,
                deviceId: Int,
            ): Boolean {
                val player = level.server.playerList.getPlayer(playerUuid) ?: return false
                val menu = player.containerMenu
                val menuAuthorized =
                    menu is ComputerMenu && menu.serverSide.device.deviceId == deviceId
                val notebookAuthorized =
                    blockEntity is NotebookBlockEntity &&
                        blockEntity.computerID == deviceId &&
                        player.serverLevel() === level &&
                        level.isLoaded(blockEntity.blockPos) &&
                        level.getBlockEntity(blockEntity.blockPos) === blockEntity &&
                        player.distanceToSqr(
                            blockEntity.blockPos.x + 0.5,
                            blockEntity.blockPos.y + 0.5,
                            blockEntity.blockPos.z + 0.5,
                        ) <= MAX_NOTEBOOK_OBSERVE_DISTANCE_SQUARED
                return menuAuthorized || notebookAuthorized
            }

            override fun sendRetainedDisplayPayload(
                playerUuid: UUID,
                deviceId: Int,
                payload: ByteArray,
            ) {
                val player = level.server.playerList.getPlayer(playerUuid) ?: return
                ServerNetworking.sendToPlayer(RetainedDisplayStateClientMessage(deviceId, payload), player)
            }
        }

    val stateSink: DeviceStateSink =
        DeviceStateSink { isOn -> blockEntity.updateBlockState(isOn) }

    private companion object {
        const val MAX_NOTEBOOK_OBSERVE_DISTANCE_SQUARED = 64.0 * 64.0
    }
}

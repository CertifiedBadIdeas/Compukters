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
package ru.lazyhat.compukterkraft.common.network

import net.minecraft.server.level.ServerPlayer
import ru.lazyhat.compukterkraft.common.network.ClientNetworkContext
import ru.lazyhat.compukterkraft.common.network.NetworkMessage

/**
 * Methods for sending network messages from the server to clients.
 *
 * The [playerSender] must be set by each loader during initialization,
 * before any networking calls are made.
 */
object ServerNetworking {
    lateinit var playerSender: (NetworkMessage<ClientNetworkContext>, ServerPlayer) -> Unit

    /**
     * Send a message to a specific player.
     *
     * @param message The message to send.
     * @param player  The player to send it to.
     */
    fun sendToPlayer(
        message: NetworkMessage<ClientNetworkContext>,
        player: ServerPlayer,
    ) {
        playerSender(message, player)
    }
}

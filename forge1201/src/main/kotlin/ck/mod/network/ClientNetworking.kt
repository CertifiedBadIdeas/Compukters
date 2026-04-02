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
package ck.mod.network

import ck.mod.network.server.ServerNetworkContext
import ck.mod.platform.NetworkHandler
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener

/**
 * Methods for sending packets from clients to the server.
 */
object ClientNetworking {
    /**
     * Send a network message to the server.
     *
     * @param message The message to send.
     */
    fun sendToServer(message: NetworkMessage<ServerNetworkContext>) {
        val connection: ClientPacketListener? = Minecraft.getInstance().connection
        connection?.send(NetworkHandler.createServerboundPacket(message))
    }
}

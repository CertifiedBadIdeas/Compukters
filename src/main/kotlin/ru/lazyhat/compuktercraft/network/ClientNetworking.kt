// SPDX-FileCopyrightText: 2024 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import ru.lazyhat.compuktercraft.network.server.ServerNetworkContext
import ru.lazyhat.compuktercraft.platform.NetworkHandler

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

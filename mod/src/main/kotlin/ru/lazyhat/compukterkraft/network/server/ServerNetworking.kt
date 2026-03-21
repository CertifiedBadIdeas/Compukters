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
package ru.lazyhat.compukterkraft.network.server

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerChunkCache
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.Vec3
import ru.lazyhat.compukterkraft.network.NetworkMessage
import ru.lazyhat.compukterkraft.network.client.ClientNetworkContext
import ru.lazyhat.compukterkraft.platform.NetworkHandler

/**
 * Methods for sending network messages from the server to clients.
 */
object ServerNetworking {
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
        player.connection.send(NetworkHandler.createClientboundPacket(message))
    }

    /**
     * Send a message to a set of players.
     *
     * @param message The message to send.
     * @param players The players to send it to.
     */
    fun sendToPlayers(
        message: NetworkMessage<ClientNetworkContext>,
        players: MutableCollection<ServerPlayer>,
    ) {
        if (players.isEmpty()) return
        val packet =
            NetworkHandler.createClientboundPacket(message)
        for (player in players) player.connection.send(packet)
    }

    /**
     * Send a message to all players.
     *
     * @param message The message to send.
     * @param server  The current server.
     */
    fun sendToAllPlayers(
        message: NetworkMessage<ClientNetworkContext>,
        server: MinecraftServer,
    ) {
        server.playerList.broadcastAll(NetworkHandler.createClientboundPacket(message))
    }

    /**
     * Send a message to all players around a point.
     *
     * @param message  The message to send.
     * @param level    The level the point is in.
     * @param pos      The centre position.
     * @param distance The distance to the centre players must be within.
     */
    fun sendToAllAround(
        message: NetworkMessage<ClientNetworkContext>,
        level: ServerLevel,
        pos: Vec3,
        distance: Float,
    ) {
        level
            .server
            .playerList
            .broadcast(
                null,
                pos.x,
                pos.y,
                pos.z,
                distance.toDouble(),
                level.dimension(),
                NetworkHandler.createClientboundPacket(message),
            )
    }

    /**
     * Send a message to all players tracking a chunk.
     *
     * @param message The message to send.
     * @param chunk   The chunk players must be tracking.
     */
    fun sendToAllTracking(
        message: NetworkMessage<ClientNetworkContext>,
        chunk: LevelChunk,
    ) {
        val packet = NetworkHandler.createClientboundPacket(message)
        for (player in (chunk.getLevel().chunkSource as ServerChunkCache).chunkMap.getPlayers(
            chunk.pos,
            false,
        )) {
            player.connection.send(packet)
        }
    }
}

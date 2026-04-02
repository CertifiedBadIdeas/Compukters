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
package ck.mod.platform

import ck.mod.MOD_ID
import ck.mod.network.MessageType
import ck.mod.network.NetworkMessage
import ck.mod.network.NetworkMessages
import ck.mod.network.client.ClientNetworkContext
import ck.mod.network.server.ServerNetworkContext
import io.netty.buffer.Unpooled
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.function.Function

object NetworkHandler {
    private val LOG: Logger = LoggerFactory.getLogger(NetworkHandler::class.java)

    fun setup() {
        for (type in NetworkMessages.serverbound) {
            val fabricType = type as MessageTypeImpl<out NetworkMessage<ServerNetworkContext>>
            registerServerbound(fabricType)
        }
    }

    fun registerClientbound() {
        for (type in NetworkMessages.clientbound) {
            val fabricType = type as MessageTypeImpl<out NetworkMessage<ClientNetworkContext>>
            registerClientReceiver(fabricType)
        }
    }

    private fun <T : NetworkMessage<ServerNetworkContext>> registerServerbound(type: MessageTypeImpl<T>) {
        val channelId = ResourceLocation(MOD_ID, "msg_${type.id}")
        ServerPlayNetworking.registerGlobalReceiver(channelId) { server, player, _, buf, _ ->
            val packet = type.reader.apply(buf)
            if (packet != null) {
                server.execute {
                    try {
                        packet.handle(ServerNetworkContext { player })
                    } catch (e: RuntimeException) {
                        LOG.error("Failed handling packet", e)
                        throw e
                    } catch (e: Error) {
                        LOG.error("Failed handling packet", e)
                        throw e
                    }
                }
            }
        }
    }

    private fun <T : NetworkMessage<ClientNetworkContext>> registerClientReceiver(type: MessageTypeImpl<T>) {
        val channelId = ResourceLocation(MOD_ID, "msg_${type.id}")
        ClientPlayNetworking.registerGlobalReceiver(channelId) { client, _, buf, _ ->
            val packet = type.reader.apply(buf)
            if (packet != null) {
                client.execute {
                    try {
                        packet.handle(ClientHolder.get())
                    } catch (e: RuntimeException) {
                        LOG.error("Failed handling packet", e)
                        throw e
                    } catch (e: Error) {
                        LOG.error("Failed handling packet", e)
                        throw e
                    }
                }
            }
        }
    }

    fun <T : NetworkMessage<ClientNetworkContext>> sendToClient(
        player: net.minecraft.server.level.ServerPlayer,
        packet: T,
    ) {
        val type = packet.type() as MessageTypeImpl<*>
        val channelId = ResourceLocation(MOD_ID, "msg_${type.id}")
        val buf = PacketByteBufs.create()
        packet.write(buf)
        ServerPlayNetworking.send(player, channelId, buf)
    }

    fun <T : NetworkMessage<ServerNetworkContext>> sendToServer(packet: T) {
        val type = packet.type() as MessageTypeImpl<*>
        val channelId = ResourceLocation(MOD_ID, "msg_${type.id}")
        val buf = PacketByteBufs.create()
        packet.write(buf)
        ClientPlayNetworking.send(channelId, buf)
    }

    class MessageTypeImpl<T : NetworkMessage<*>>(
        val id: Int,
        val klass: Class<T>,
        val reader: Function<FriendlyByteBuf, T?>,
    ) : MessageType<T>

    /**
     * This holds an instance of [ClientNetworkContext]. This is a separate class to ensure that the instance is
     * lazily created when needed on the client.
     */
    private object ClientHolder {
        private val INSTANCE: ClientNetworkContext?
        private val ERROR: Throwable?

        init {
            val helper = Services.tryLoad(ClientNetworkContext::class.java)
            INSTANCE = helper.instance
            ERROR = helper.error
        }

        fun get(): ClientNetworkContext {
            val instance: ClientNetworkContext? = INSTANCE
            return instance ?: Services.raise(ClientNetworkContext::class.java, ERROR)
        }
    }
}

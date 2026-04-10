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
import ck.mod.network.MessageTypeImpl
import ck.mod.network.NetworkMessage
import ck.mod.network.NetworkMessages
import ck.mod.network.client.ClientNetworkContext
import ck.mod.network.server.ServerNetworkContext
import io.netty.buffer.Unpooled
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object NetworkHandler {
    private val LOG: Logger = LoggerFactory.getLogger(NetworkHandler::class.java)

    private fun payloadType(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    fun setup() {
        PayloadTypeRegistry.playC2S().register(ServerboundPayload.TYPE, ServerboundPayload.STREAM_CODEC)
        PayloadTypeRegistry.playS2C().register(ClientboundPayload.TYPE, ClientboundPayload.STREAM_CODEC)

        ServerPlayNetworking.registerGlobalReceiver(ServerboundPayload.TYPE) { payload, context ->
            val player = context.player()
            context.server().execute {
                try {
                    payload.decodeMessage().handle(ServerNetworkContext { player })
                } catch (e: RuntimeException) {
                    LOG.error("Failed handling serverbound packet", e)
                    throw e
                } catch (e: Error) {
                    LOG.error("Failed handling serverbound packet", e)
                    throw e
                }
            }
        }
    }

    fun registerClientbound() {
        ClientPlayNetworking.registerGlobalReceiver(ClientboundPayload.TYPE) { payload, context ->
            context.client().execute {
                try {
                    payload.decodeMessage().handle(ClientHolder.get())
                } catch (e: RuntimeException) {
                    LOG.error("Failed handling clientbound packet", e)
                    throw e
                } catch (e: Error) {
                    LOG.error("Failed handling clientbound packet", e)
                    throw e
                }
            }
        }
    }

    fun sendToServer(packet: NetworkMessage<ServerNetworkContext>) {
        ClientPlayNetworking.send(ServerboundPayload(packet))
    }

    fun sendToPlayer(
        packet: NetworkMessage<ClientNetworkContext>,
        player: ServerPlayer,
    ) {
        ServerPlayNetworking.send(player, ClientboundPayload(packet))
    }

    fun createClientboundPayload(packet: NetworkMessage<ClientNetworkContext>): ClientboundPayload = ClientboundPayload(packet)

    private fun serialize(message: NetworkMessage<*>): ByteArray {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        message.write(buffer)
        return ByteArray(buffer.readableBytes()).also { bytes ->
            buffer.getBytes(0, bytes)
        }
    }

    private fun decodeServerbound(
        id: Int,
        bytes: ByteArray,
    ): NetworkMessage<ServerNetworkContext> {
        val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
        val type = NetworkMessages.serverboundById(id)
        return type.reader(buffer)
    }

    private fun decodeClientbound(
        id: Int,
        bytes: ByteArray,
    ): NetworkMessage<ClientNetworkContext> {
        val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
        val type = NetworkMessages.clientboundById(id)
        return type.reader(buffer)
    }

    class ClientboundPayload(
        private val id: Int,
        private val bytes: ByteArray,
    ) : CustomPacketPayload {
        constructor(message: NetworkMessage<ClientNetworkContext>) : this((message.type() as MessageTypeImpl<*>).id, serialize(message))

        constructor(buffer: RegistryFriendlyByteBuf) : this(buffer.readVarInt(), buffer.readByteArray())

        fun decodeMessage(): NetworkMessage<ClientNetworkContext> = decodeClientbound(id, bytes)

        override fun type(): CustomPacketPayload.Type<ClientboundPayload> = TYPE

        fun write(buffer: RegistryFriendlyByteBuf) {
            buffer.writeVarInt(id)
            buffer.writeByteArray(bytes)
        }

        companion object {
            val TYPE: CustomPacketPayload.Type<ClientboundPayload> = CustomPacketPayload.Type(payloadType("clientbound"))
            val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ClientboundPayload> =
                CustomPacketPayload.codec(ClientboundPayload::write, ::ClientboundPayload)
        }
    }

    private class ServerboundPayload(
        private val id: Int,
        private val bytes: ByteArray,
    ) : CustomPacketPayload {
        constructor(message: NetworkMessage<ServerNetworkContext>) : this((message.type() as MessageTypeImpl<*>).id, serialize(message))

        constructor(buffer: RegistryFriendlyByteBuf) : this(buffer.readVarInt(), buffer.readByteArray())

        fun decodeMessage(): NetworkMessage<ServerNetworkContext> = decodeServerbound(id, bytes)

        override fun type(): CustomPacketPayload.Type<ServerboundPayload> = TYPE

        fun write(buffer: RegistryFriendlyByteBuf) {
            buffer.writeVarInt(id)
            buffer.writeByteArray(bytes)
        }

        companion object {
            val TYPE: CustomPacketPayload.Type<ServerboundPayload> = CustomPacketPayload.Type(payloadType("serverbound"))
            val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, ServerboundPayload> =
                CustomPacketPayload.codec(ServerboundPayload::write, ::ServerboundPayload)
        }
    }

    private object ClientHolder {
        private val instance: ClientNetworkContext?
        private val error: Throwable?

        init {
            val helper = Services.tryLoad(ClientNetworkContext::class.java)
            instance = helper.instance
            error = helper.error
        }

        fun get(): ClientNetworkContext = instance ?: Services.raise(ClientNetworkContext::class.java, error)
    }
}

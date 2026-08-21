/*
 * The Compukters Developers
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
package ru.lazyhat.compukters.impl.platform

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import ru.lazyhat.compukters.common.network.ClientNetworkContext
import ru.lazyhat.compukters.common.network.MessageTypeImpl
import ru.lazyhat.compukters.common.network.NetworkMessage
import ru.lazyhat.compukters.common.network.NetworkMessages
import ru.lazyhat.compukters.common.network.ServerNetworkContext
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.core.platform.Services
import ru.lazyhat.compukters.impl.INSTALLED_VERSION

object NetworkHandler {
    private fun payloadType(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)

    fun setup(modEventBus: IEventBus) {
        modEventBus.addListener(::registerPayloadHandlers)
    }

    fun sendToServer(packet: NetworkMessage<ServerNetworkContext>) {
        PacketDistributor.sendToServer(ServerboundPayload(packet))
    }

    fun sendToPlayer(
        packet: NetworkMessage<ClientNetworkContext>,
        player: ServerPlayer,
    ) {
        PacketDistributor.sendToPlayer(player, ClientboundPayload(packet))
    }

    fun createClientboundPayload(packet: NetworkMessage<ClientNetworkContext>): ClientboundPayload = ClientboundPayload(packet)

    private fun registerPayloadHandlers(event: RegisterPayloadHandlersEvent) {
        event
            .registrar(INSTALLED_VERSION)
            .playToServer(ServerboundPayload.TYPE, ServerboundPayload.STREAM_CODEC, ::handleServerbound)
            .playToClient(ClientboundPayload.TYPE, ClientboundPayload.STREAM_CODEC, ::handleClientbound)
    }

    private fun handleServerbound(
        payload: ServerboundPayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: error("Serverbound payload received without ServerPlayer")
        payload.decodeMessage().handle(ServerNetworkContext { player })
    }

    private fun handleClientbound(
        payload: ClientboundPayload,
        context: IPayloadContext,
    ) {
        context.player()
        payload.decodeMessage().handle(ClientHolder.get())
    }

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

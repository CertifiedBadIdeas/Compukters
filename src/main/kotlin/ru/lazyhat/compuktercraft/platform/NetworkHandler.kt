// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.platform

import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ServerGamePacketListener
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.lazyhat.compuktercraft.CompukterCraftMod
import ru.lazyhat.compuktercraft.network.MessageType
import ru.lazyhat.compuktercraft.network.NetworkMessage
import ru.lazyhat.compuktercraft.network.NetworkMessages
import ru.lazyhat.compuktercraft.network.client.ClientNetworkContext
import ru.lazyhat.compuktercraft.network.server.ServerNetworkContext
import java.util.function.Function
import java.util.function.Supplier

object NetworkHandler {
    private val LOG: Logger = LoggerFactory.getLogger(NetworkHandler::class.java)

    private val network: SimpleChannel

    init {
        val version = CompukterCraftMod.installedVersion
        network =
            NetworkRegistry.ChannelBuilder
                .named(ResourceLocation.fromNamespaceAndPath(CompukterCraftMod.ID, "network"))
                .networkProtocolVersion(Supplier { version })
                .clientAcceptedVersions(version!!::equals)
                .serverAcceptedVersions(version::equals)
                .simpleChannel()
    }

    fun setup() {
        for (type in NetworkMessages.serverbound) {
            val forgeType = type as MessageTypeImpl<out NetworkMessage<ServerNetworkContext>>
            registerMainThread(
                forgeType,
                NetworkDirection.PLAY_TO_SERVER,
            ) { c ->
                ServerNetworkContext {
                    checkNotNull(c.sender)
                }
            }
        }

        for (type in NetworkMessages.clientbound) {
            val forgeType = type as MessageTypeImpl<out NetworkMessage<ClientNetworkContext>>
            registerMainThread(
                forgeType,
                NetworkDirection.PLAY_TO_CLIENT,
            ) { ClientHolder.get() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun createClientboundPacket(packet: NetworkMessage<ClientNetworkContext>): Packet<ClientGamePacketListener> =
        network.toVanillaPacket(packet, NetworkDirection.PLAY_TO_CLIENT) as Packet<ClientGamePacketListener>

    @Suppress("UNCHECKED_CAST")
    fun createServerboundPacket(packet: NetworkMessage<ServerNetworkContext>): Packet<ServerGamePacketListener> =
        network.toVanillaPacket(packet, NetworkDirection.PLAY_TO_SERVER) as Packet<ServerGamePacketListener>

    /**
     * Register packet, and a thread-unsafe handler for it.
     *
     * @param <T>       The type of the packet to send.
     * @param <H>       The context this packet is evaluated under.
     * @param type      The message type to register.
     * @param direction A network direction which will be asserted before any processing of this message occurs
     * @param handler   Gets or constructs the handler for this packet.
     </H></T> */
    fun <H, T : NetworkMessage<H>> registerMainThread(
        type: MessageTypeImpl<T>,
        direction: NetworkDirection?,
        handler: (NetworkEvent.Context) -> H,
    ) {
        network
            .messageBuilder<T?>(type.klass, type.id, direction)
            .encoder(NetworkMessage<H>::write)
            .decoder(type.reader)
            .consumerMainThread(
                { packet: T, contextSup: Supplier<NetworkEvent.Context> ->
                    try {
                        packet.handle(handler(contextSup.get()))
                    } catch (e: RuntimeException) {
                        LOG.error("Failed handling packet", e)
                        throw e
                    } catch (e: Error) {
                        LOG.error("Failed handling packet", e)
                        throw e
                    }
                },
            ).add()
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

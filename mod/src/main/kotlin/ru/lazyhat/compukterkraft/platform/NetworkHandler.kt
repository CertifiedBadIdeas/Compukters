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
package ru.lazyhat.compukterkraft.platform

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
import ru.lazyhat.compukterkraft.INSTALLED_VERSION
import ru.lazyhat.compukterkraft.MOD_ID
import ru.lazyhat.compukterkraft.network.MessageType
import ru.lazyhat.compukterkraft.network.NetworkMessage
import ru.lazyhat.compukterkraft.network.NetworkMessages
import ru.lazyhat.compukterkraft.network.client.ClientNetworkContext
import ru.lazyhat.compukterkraft.network.server.ServerNetworkContext
import java.util.function.Function
import java.util.function.Supplier

object NetworkHandler {
    private val LOG: Logger = LoggerFactory.getLogger(NetworkHandler::class.java)

    private val network: SimpleChannel

    init {
        val version = INSTALLED_VERSION
        network =
            NetworkRegistry.ChannelBuilder
                .named(ResourceLocation.fromNamespaceAndPath(MOD_ID, "network"))
                .networkProtocolVersion { version }
                .clientAcceptedVersions(version::equals)
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

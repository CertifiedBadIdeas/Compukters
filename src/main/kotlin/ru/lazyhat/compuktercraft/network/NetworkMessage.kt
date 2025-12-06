// SPDX-FileCopyrightText: 2018 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network

import net.minecraft.network.FriendlyByteBuf

/**
 * The base interface for any message which will be sent to the client or server.
 *
 * @param <T> The context under which packets are evaluated.
 * @see ClientNetworkContext
 *
 * @see ServerNetworkContext
</T> */
interface NetworkMessage<T> {
    /**
     * Get the type of this message.
     *
     * @return The type of this message.
     */
    fun type(): MessageType<*>

    /**
     * Write this packet to a buffer.
     *
     *
     * This may be called on any thread, so this should be a pure operation.
     *
     * @param buf The buffer to write data to.
     */
    fun write(buf: FriendlyByteBuf)

    /**
     * Handle this [NetworkMessage].
     *
     * @param context The context with which to handle this message
     */
    fun handle(context: T)
}

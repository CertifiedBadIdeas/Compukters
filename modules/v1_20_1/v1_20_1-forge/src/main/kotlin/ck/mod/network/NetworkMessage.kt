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

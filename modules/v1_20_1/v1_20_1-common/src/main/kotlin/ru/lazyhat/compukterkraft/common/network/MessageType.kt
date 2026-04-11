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
package ru.lazyhat.compukterkraft.common.network

import net.minecraft.network.FriendlyByteBuf
import java.util.function.Function

/**
 * A type of message to send over the network.
 *
 *
 * Much like recipe or argument serialisers, each type of [NetworkMessage] should have a unique type associated
 * with it. This holds platform-specific information about how the packet should be sent over the network.
 *
 * @param <T> The type of message to send
 * @see NetworkMessages
 *
 * @see NetworkMessage.type
</T> */
interface MessageType<T : NetworkMessage<*>>

class MessageTypeImpl<T : NetworkMessage<*>>(
    val id: Int,
    val klass: Class<T>,
    val reader: Function<FriendlyByteBuf, T?>,
) : MessageType<T>

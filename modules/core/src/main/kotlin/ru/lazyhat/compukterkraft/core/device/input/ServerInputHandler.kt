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
package ru.lazyhat.compukterkraft.core.device.input

import ru.lazyhat.compukterkraft.core.device.input.InputEvent

/**
 * Handles user-provided input on the server, receiving data from the client over the network.
 *
 * @see ServerInputState The default implementation of this interface.
 *
 * @see ComputerServerMessage Packets which consume this interface.
 *
 * @see ComputerMenu
 */
interface ServerInputHandler {
    /**
     * Accept a unified [InputEvent], dispatching it to the VM and tracking input state.
     */
    fun accept(event: InputEvent)
}

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
package ru.lazyhat.compukterkraft.common.computer.input

import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.common.computer.input.NetworkComputerInputGateway
import ru.lazyhat.compukterkraft.core.computer.input.TargetInputGateway
import ru.lazyhat.compukterkraft.core.computer.input.InputEvent
import ru.lazyhat.compukterkraft.core.computer.input.InputEventSink

/**
 * Client-side [InputEventSink] that forwards all input to the server via [TargetInputGateway].
 */
class ClientInputHandler(
    menu: AbstractContainerMenu,
) : InputEventSink {
    private val gateway: TargetInputGateway = NetworkComputerInputGateway(menu)

    override fun accept(event: InputEvent) = gateway.send(event)
}

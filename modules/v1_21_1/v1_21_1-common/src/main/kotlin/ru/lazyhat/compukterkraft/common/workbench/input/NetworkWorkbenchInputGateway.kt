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
package ru.lazyhat.compukterkraft.common.workbench.input

import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.common.network.ClientNetworking
import ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchInputServerMessage
import ru.lazyhat.compukterkraft.core.device.input.InputEvent
import ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.device.input.MouseInputEvent
import ru.lazyhat.compukterkraft.core.device.input.PasteInputEvent
import ru.lazyhat.compukterkraft.core.device.input.TargetInputGateway

class NetworkWorkbenchInputGateway(
    private val menu: AbstractContainerMenu,
) : TargetInputGateway {
    override fun send(event: InputEvent) {
        when (event) {
            is KeyInputEvent.Down -> {
                ClientNetworking.sendToServer(
                    WorkbenchInputServerMessage(
                        menu,
                        if (event.repeat) WorkbenchInputServerMessage.Action.KEY_REPEAT else WorkbenchInputServerMessage.Action.KEY_DOWN,
                        arg = event.key,
                    ),
                )
            }

            is KeyInputEvent.Up -> {
                ClientNetworking.sendToServer(
                    WorkbenchInputServerMessage(menu, WorkbenchInputServerMessage.Action.KEY_UP, arg = event.key),
                )
            }

            is KeyInputEvent.Character -> {
                ClientNetworking.sendToServer(
                    WorkbenchInputServerMessage(menu, WorkbenchInputServerMessage.Action.KEY_CHAR, arg = event.value.toInt()),
                )
            }

            is MouseInputEvent.Click -> {
                ClientNetworking.sendToServer(
                    WorkbenchInputServerMessage(
                        menu,
                        WorkbenchInputServerMessage.Action.MOUSE_CLICK,
                        arg = event.button,
                        x = event.x,
                        y = event.y,
                    ),
                )
            }

            is MouseInputEvent.Drag -> {
                ClientNetworking.sendToServer(
                    WorkbenchInputServerMessage(
                        menu,
                        WorkbenchInputServerMessage.Action.MOUSE_DRAG,
                        arg = event.button,
                        x = event.x,
                        y = event.y,
                    ),
                )
            }

            is MouseInputEvent.Up -> {
                ClientNetworking.sendToServer(
                    WorkbenchInputServerMessage(
                        menu,
                        WorkbenchInputServerMessage.Action.MOUSE_UP,
                        arg = event.button,
                        x = event.x,
                        y = event.y,
                    ),
                )
            }

            is MouseInputEvent.Scroll -> {
                ClientNetworking.sendToServer(
                    WorkbenchInputServerMessage(
                        menu,
                        WorkbenchInputServerMessage.Action.MOUSE_SCROLL,
                        arg = event.direction,
                        x = event.x,
                        y = event.y,
                    ),
                )
            }

            is PasteInputEvent -> {
                ClientNetworking.sendToServer(
                    WorkbenchInputServerMessage(menu, WorkbenchInputServerMessage.Action.PASTE, paste = event.contents),
                )
            }

            else -> {
                Unit
            }
        }
    }
}

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
package ru.lazyhat.compukterkraft.common.menu

import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.ints.IntSet
import net.minecraft.world.inventory.AbstractContainerMenu
import ru.lazyhat.compukterkraft.common.computer.ServerComputer
import ru.lazyhat.compukterkraft.core.application.input.ComputerControlAction
import ru.lazyhat.compukterkraft.core.application.input.ControlInputEvent
import ru.lazyhat.compukterkraft.core.application.input.InputEvent
import ru.lazyhat.compukterkraft.core.application.input.KeyInputEvent
import ru.lazyhat.compukterkraft.core.application.input.MouseInputEvent
import ru.lazyhat.compukterkraft.core.application.input.PasteInputEvent
import ru.lazyhat.compukterkraft.core.computer.ComputerEvents
import ru.lazyhat.compukterkraft.core.menu.ServerInputHandler
import ru.lazyhat.compukterkraft.core.utils.StringUtil
import java.nio.ByteBuffer

/**
 * The default concrete implementation of [ServerInputHandler].
 *
 * Accepts a unified [InputEvent], tracks key/mouse state, and dispatches to the VM.
 * On [close], releases all held keys and mouse buttons.
 *
 * @param <T> The type of container this server input belongs to.
</T> */
class ServerInputState<T>(
    private val owner: T,
) : ServerInputHandler
    where T : AbstractContainerMenu, T : ComputerMenu {
    private val keysDown: IntSet = IntOpenHashSet(4)

    private var lastMouseX = 0
    private var lastMouseY = 0
    private var lastMouseDown = -1

    override fun accept(event: InputEvent) {
        val computer = owner.serverSide.computer
        when (event) {
            is KeyInputEvent.Down -> {
                keysDown.add(event.key)
                ComputerEvents.dispatch(computer, event)
            }

            is KeyInputEvent.Up -> {
                keysDown.remove(event.key)
                ComputerEvents.dispatch(computer, event)
            }

            is KeyInputEvent.Character -> {
                if (StringUtil.isTypableChar(event.value)) {
                    ComputerEvents.dispatch(computer, event)
                }
            }

            is PasteInputEvent -> {
                if (event.contents != null && event.contents.remaining() > 0 && isValidClipboard(event.contents)) {
                    ComputerEvents.dispatch(computer, event)
                }
            }

            is MouseInputEvent.Click -> {
                lastMouseX = event.x
                lastMouseY = event.y
                lastMouseDown = event.button
                ComputerEvents.dispatch(computer, event)
            }

            is MouseInputEvent.Up -> {
                lastMouseX = event.x
                lastMouseY = event.y
                lastMouseDown = -1
                ComputerEvents.dispatch(computer, event)
            }

            is MouseInputEvent.Drag -> {
                lastMouseX = event.x
                lastMouseY = event.y
                lastMouseDown = event.button
                ComputerEvents.dispatch(computer, event)
            }

            is MouseInputEvent.Scroll -> {
                lastMouseX = event.x
                lastMouseY = event.y
                ComputerEvents.dispatch(computer, event)
            }

            is ControlInputEvent -> {
                when (event.action) {
                    ComputerControlAction.TERMINATE -> computer.queueEvent("terminate")
                    ComputerControlAction.SHUTDOWN -> computer.shutdown()
                    ComputerControlAction.TURN_ON -> computer.turnOn()
                    ComputerControlAction.REBOOT -> computer.reboot()
                }
            }
        }
    }

    fun close() {
        val computer: ServerComputer = owner.serverSide.computer
        val keys = keysDown.iterator()
        while (keys.hasNext()) ComputerEvents.dispatch(computer, KeyInputEvent.Up(keys.nextInt()))

        if (lastMouseDown != -1) ComputerEvents.dispatch(computer, MouseInputEvent.Up(lastMouseDown, lastMouseX, lastMouseY))

        keysDown.clear()
        lastMouseDown = -1
    }

    companion object {
        private fun isValidClipboard(buffer: ByteBuffer): Boolean {
            var i = buffer.position()
            val max = buffer.limit()
            while (i < max) {
                if (!StringUtil.isTypableChar(buffer.get(i))) return false
                i++
            }
            return true
        }
    }
}

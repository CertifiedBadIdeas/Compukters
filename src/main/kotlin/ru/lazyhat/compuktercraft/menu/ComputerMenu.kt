// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.menu

import ru.lazyhat.compuktercraft.computer.ServerComputer
import ru.lazyhat.compuktercraft.gui.TerminalState

/**
 * An instance of [AbstractContainerMenu] which provides a computer. You should implement this if you provide
 * custom computer GUIs.
 */
interface ComputerMenu {
    /**
     * Get the computer you are interacting with.
     *
     * @return The computer you are interacting with.
     * @throws UnsupportedOperationException When used on the client side.
     */
    fun getComputerPublic(): ServerComputer

    /**
     * Get the input controller for this container. This should be used when receiving events from the client.
     *
     * @return This container's input.
     * @throws UnsupportedOperationException When used on the client side.
     */
    fun getInputPublic(): ServerInputHandler

    /**
     * Set the current terminal state. This is called on the client when the server syncs a computer's terminal
     * contents.
     *
     * @param state The new terminal state.
     * @throws UnsupportedOperationException When used on the server.
     */
    fun updateTerminal(state: TerminalState)
}

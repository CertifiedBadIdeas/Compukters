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

import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

/**
 * An instance of [AbstractContainerMenu] which provides a computer. You should implement this if you provide
 * custom computer GUIs.
 *
 * Server-only and client-only operations are accessed through [side]:
 * - `menu.serverSide.computer` / `menu.serverSide.input` — server-side only
 * - `menu.clientSide.screenSnapshot` — client-side only, nullable until the first terminal sync arrives
 */
interface ComputerMenu {
    /** Type-safe side discriminator. */
    val side: MenuSide

    /** The computer family. */
    val family: ComputerFamily

    /**
     * Convenience accessor for the server side.
     * @throws ClassCastException When called on the client.
     */
    val serverSide: MenuSide.Server
        get() = side as MenuSide.Server

    /**
     * Convenience accessor for the client side.
     * @throws ClassCastException When called on the server.
     */
    val clientSide: MenuSide.Client
        get() = side as MenuSide.Client

    /**
     * Set the current terminal screen snapshot. Called on the client when the server syncs.
     */
    fun updateTerminal(snapshot: ScreenBufferSnapshot)

    fun updateWorkspaceEntries(entries: List<ComputerWorkspaceEntry>)

    fun updateWorkspaceDocument(document: ComputerWorkspaceDocument?)
}

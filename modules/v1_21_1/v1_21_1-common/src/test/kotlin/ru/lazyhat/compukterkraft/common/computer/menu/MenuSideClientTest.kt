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

package ru.lazyhat.compukterkraft.common.computer.menu

import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MenuSideClientTest {
    @Test
    fun clientSideCanStartWithoutSnapshotAndLaterReceiveOne() {
        val client = MenuSide.Client(initialSnapshot = null)

        assertNull(client.screenSnapshot)

        val snapshot = ScreenBufferSnapshot.empty(width = 12, height = 6, colour = true)
        client.updateScreenSnapshot(snapshot)

        assertEquals(snapshot, client.screenSnapshot)
    }

    @Test
    fun abstractComputerMenuDoesNotExposeWorkspaceAuthoringApi() {
        val methodNames = AbstractComputerMenu::class.java.methods.map { it.name }.toSet()
        val fieldNames = AbstractComputerMenu::class.java.declaredFields.map { it.name }.toSet()

        assertFalse("updateWorkspaceEntries" in methodNames)
        assertFalse("updateWorkspaceDocument" in methodNames)
        assertFalse("getWorkspaceEntries" in methodNames)
        assertFalse("getWorkspaceDocument" in methodNames)
        assertFalse("workspaceStateFlow" in methodNames)
        assertFalse("_workspaceStateFlow" in fieldNames)
    }
}

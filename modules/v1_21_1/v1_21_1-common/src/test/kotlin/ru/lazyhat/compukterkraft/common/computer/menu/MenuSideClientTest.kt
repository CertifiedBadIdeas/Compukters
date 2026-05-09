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

import ru.lazyhat.compukterkraft.common.computer.client.ClientDisplayBuffer
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayFrameDelta
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayPixelFormat
import ru.lazyhat.compukterkraft.lang.runtime.display.DisplayTile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MenuSideClientTest {
    @Test
    fun clientSideAttachAndDetachDisplayBuffer() {
        val client = MenuSide.Client()

        assertNull(client.displayBuffer)

        val buffer = ClientDisplayBuffer(displayId = 1, width = 64, height = 48)
        client.attachDisplayBuffer(buffer)

        assertSame(buffer, client.displayBuffer)

        client.detachDisplayBuffer()
        assertNull(client.displayBuffer)
    }

    @Test
    fun clientSideRetainsDisplayBufferWhenReattachedWithSameGeometry() {
        val client = MenuSide.Client()
        val firstBuffer = ClientDisplayBuffer(displayId = 1, width = 64, height = 48)
        val secondBuffer = ClientDisplayBuffer(displayId = 1, width = 64, height = 48)
        val frame =
            DisplayFrameDelta(
                displayId = 1,
                sequence = 1,
                width = 64,
                height = 48,
                pixelFormat = DisplayPixelFormat.RGB565,
                fullRefresh = true,
                tiles = listOf(DisplayTile(0, 0, 0, 0, 1, 1, byteArrayOf(0, 0))),
            )

        client.attachDisplayBuffer(firstBuffer)
        assertTrue(firstBuffer.apply(frame))
        assertSame(firstBuffer, client.displayBuffer)

        client.attachDisplayBuffer(secondBuffer)

        assertSame(firstBuffer, client.displayBuffer)
        assertTrue(client.displayBuffer?.hasReceivedFrames == true)
    }

    @Test
    fun abstractComputerMenuDoesNotExposeWorkspaceAuthoringApi() {
        val methodNames =
            AbstractComputerMenu::class.java.methods
                .map { it.name }
                .toSet()
        val fieldNames =
            AbstractComputerMenu::class.java.declaredFields
                .map { it.name }
                .toSet()

        assertFalse("updateWorkspaceEntries" in methodNames)
        assertFalse("updateWorkspaceDocument" in methodNames)
        assertFalse("getWorkspaceEntries" in methodNames)
        assertFalse("getWorkspaceDocument" in methodNames)
        assertFalse("workspaceStateFlow" in methodNames)
        assertFalse("_workspaceStateFlow" in fieldNames)
    }
}

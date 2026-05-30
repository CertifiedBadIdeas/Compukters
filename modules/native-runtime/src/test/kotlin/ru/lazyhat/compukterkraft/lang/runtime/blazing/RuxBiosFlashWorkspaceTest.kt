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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuxBiosFlashWorkspaceTest {
    @Test
    fun preparesBiosFlashFromRawResourceBytes() {
        val workspace = createTempDirectory("rux-bios-workspace-")
        val loader = resourceClassLoader("firmware/test-bios.kflash", byteArrayOf(0x01, 0x10, 0x01, 0xe0.toByte()))

        val path =
            RuxBiosFlashWorkspace.prepareBiosFlash(
                workspace = workspace,
                resourcePath = "firmware/test-bios.kflash",
                classLoader = loader,
            )

        assertEquals(workspace.resolve("bios.kflash"), path)
        assertTrue(path.exists())
        assertContentEquals(byteArrayOf(0x01, 0x10, 0x01, 0xe0.toByte()), path.readBytes())
    }

    @Test
    fun preservesExistingPerComputerBiosFlash() {
        val workspace = createTempDirectory("rux-bios-workspace-")
        val existing = workspace.resolve("bios.kflash")
        existing.writeBytes(byteArrayOf(7, 8, 9))
        val loader = resourceClassLoader("firmware/test-bios.kflash", byteArrayOf(1, 2, 3))

        val path =
            RuxBiosFlashWorkspace.prepareBiosFlash(
                workspace = workspace,
                resourcePath = "firmware/test-bios.kflash",
                classLoader = loader,
            )

        assertEquals(existing, path)
        assertContentEquals(byteArrayOf(7, 8, 9), path.readBytes())
    }

    @Test
    fun missingBiosFlashResourceFailsFast() {
        val workspace = createTempDirectory("rux-bios-workspace-")

        assertFailsWith<IllegalStateException> {
            RuxBiosFlashWorkspace.prepareBiosFlash(
                workspace = workspace,
                resourcePath = "firmware/missing-bios.kflash",
                classLoader = resourceClassLoader("firmware/other.kflash", byteArrayOf(1, 2)),
            )
        }
    }

    @Test
    fun defaultBiosFlashResourceUsesKflashExtension() {
        assertEquals("firmware/rux16-bios.kflash", RuxBiosFlashWorkspace.DEFAULT_BIOS_FLASH_RESOURCE)
    }

    private fun resourceClassLoader(
        path: String,
        content: ByteArray,
    ): ClassLoader =
        object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): InputStream? =
                if (name == path) ByteArrayInputStream(content) else null
        }
}

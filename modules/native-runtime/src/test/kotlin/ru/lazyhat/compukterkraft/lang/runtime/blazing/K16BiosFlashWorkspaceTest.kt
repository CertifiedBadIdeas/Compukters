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

class K16BiosFlashWorkspaceTest {
    @Test
    fun preparesBiosFlashFromRawResourceBytes() {
        val workspace = createTempDirectory("k16-bios-workspace-")
        val loader = resourceClassLoader("firmware/test-bios.kflash", byteArrayOf(0x01, 0x10, 0x01, 0xe0.toByte()))

        val path =
            K16BiosFlashWorkspace.prepareBiosFlash(
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
        val workspace = createTempDirectory("k16-bios-workspace-")
        val existing = workspace.resolve("bios.kflash")
        existing.writeBytes(byteArrayOf(7, 8, 9, 10))
        val loader = resourceClassLoader("firmware/test-bios.kflash", byteArrayOf(1, 2, 3))

        val path =
            K16BiosFlashWorkspace.prepareBiosFlash(
                workspace = workspace,
                resourcePath = "firmware/test-bios.kflash",
                classLoader = loader,
            )

        assertEquals(existing, path)
        assertContentEquals(byteArrayOf(7, 8, 9, 10), path.readBytes())
    }

    @Test
    fun rejectsMalformedExistingPerComputerBiosFlash() {
        val workspace = createTempDirectory("k16-bios-workspace-")
        workspace.resolve("bios.kflash").writeBytes(byteArrayOf(7, 8, 9))

        val error =
            assertFailsWith<IllegalStateException> {
                K16BiosFlashWorkspace.prepareBiosFlash(workspace = workspace)
            }

        assertTrue(error.message.orEmpty().contains("must contain 16-bit instruction bytes"))
    }

    @Test
    fun flashesReplacementBiosFromExplicitSourcePath() {
        val workspace = createTempDirectory("k16-bios-workspace-")
        val source = workspace.resolve("replacement.kflash")
        source.writeBytes(byteArrayOf(1, 2, 3, 4))

        val path = K16BiosFlashWorkspace.flashBiosFlash(workspace = workspace, source = source)

        assertEquals(workspace.resolve("bios.kflash"), path)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), path.readBytes())
    }

    @Test
    fun preparesPreviouslyFlashedBiosOnSubsequentBoots() {
        val workspace = createTempDirectory("k16-bios-workspace-")
        val source = workspace.resolve("replacement.kflash")
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        K16BiosFlashWorkspace.flashBiosFlash(workspace = workspace, source = source)
        val loader = resourceClassLoader("firmware/test-bios.kflash", byteArrayOf(7, 8, 9, 10))

        val path =
            K16BiosFlashWorkspace.prepareBiosFlash(
                workspace = workspace,
                resourcePath = "firmware/test-bios.kflash",
                classLoader = loader,
            )

        assertEquals(workspace.resolve("bios.kflash"), path)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), path.readBytes())
    }

    @Test
    fun rejectsMissingReplacementBiosWithoutChangingExistingFlash() {
        val workspace = createTempDirectory("k16-bios-workspace-")
        val existing = workspace.resolve("bios.kflash")
        existing.writeBytes(byteArrayOf(7, 8, 9, 10))

        val error =
            assertFailsWith<IllegalStateException> {
                K16BiosFlashWorkspace.flashBiosFlash(
                    workspace = workspace,
                    source = workspace.resolve("missing.kflash"),
                )
            }

        assertTrue(error.message.orEmpty().contains("K16 BIOS flash source not found"))
        assertContentEquals(byteArrayOf(7, 8, 9, 10), existing.readBytes())
    }

    @Test
    fun rejectsMalformedReplacementBiosWithoutChangingExistingFlash() {
        val workspace = createTempDirectory("k16-bios-workspace-")
        val existing = workspace.resolve("bios.kflash")
        val source = workspace.resolve("replacement.kflash")
        existing.writeBytes(byteArrayOf(7, 8, 9, 10))
        source.writeBytes(byteArrayOf(1, 2, 3))

        val error =
            assertFailsWith<IllegalStateException> {
                K16BiosFlashWorkspace.flashBiosFlash(workspace = workspace, source = source)
            }

        assertTrue(error.message.orEmpty().contains("must contain 16-bit instruction bytes"))
        assertContentEquals(byteArrayOf(7, 8, 9, 10), existing.readBytes())
    }

    @Test
    fun restoresBundledBiosFlashForRecovery() {
        val workspace = createTempDirectory("k16-bios-workspace-")
        val existing = workspace.resolve("bios.kflash")
        existing.writeBytes(byteArrayOf(7, 8, 9, 10))
        val loader = resourceClassLoader("firmware/test-bios.kflash", byteArrayOf(1, 2, 3, 4))

        val path =
            K16BiosFlashWorkspace.restoreBundledBiosFlash(
                workspace = workspace,
                resourcePath = "firmware/test-bios.kflash",
                classLoader = loader,
            )

        assertEquals(existing, path)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), path.readBytes())
    }

    @Test
    fun missingBiosFlashResourceFailsFast() {
        val workspace = createTempDirectory("k16-bios-workspace-")

        assertFailsWith<IllegalStateException> {
            K16BiosFlashWorkspace.prepareBiosFlash(
                workspace = workspace,
                resourcePath = "firmware/missing-bios.kflash",
                classLoader = resourceClassLoader("firmware/other.kflash", byteArrayOf(1, 2)),
            )
        }
    }

    @Test
    fun defaultBiosFlashResourceUsesKflashExtension() {
        assertEquals("firmware/k16-bios.kflash", K16BiosFlashWorkspace.DEFAULT_BIOS_FLASH_RESOURCE)
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

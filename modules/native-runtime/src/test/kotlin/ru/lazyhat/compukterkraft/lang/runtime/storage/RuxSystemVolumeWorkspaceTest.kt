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

package ru.lazyhat.compukterkraft.lang.runtime.storage

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuxSystemVolumeWorkspaceTest {
    @Test
    fun preparesStorage0VolumeFromBundledResourceBytes() {
        val workspace = createTempDirectory("rux-system-volume-workspace-")
        val bytes = "RUXVOL".encodeToByteArray() + byteArrayOf(1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val loader = resourceClassLoader("firmware/test-storage0.ruxvol", bytes)

        val path =
            RuxSystemVolumeWorkspace.prepareStorage0Volume(
                workspace = workspace,
                resourcePath = "firmware/test-storage0.ruxvol",
                classLoader = loader,
            )

        assertEquals(workspace.resolve("volumes/storage0.ruxvol"), path)
        assertTrue(path.exists())
        assertContentEquals(bytes, path.readBytes())
    }

    @Test
    fun preservesExistingPerComputerStorage0Volume() {
        val workspace = createTempDirectory("rux-system-volume-workspace-")
        val existing = workspace.resolve("volumes/storage0.ruxvol")
        existing.parent.createDirectories()
        existing.writeBytes(byteArrayOf(7, 8, 9))
        val loader = resourceClassLoader("firmware/test-storage0.ruxvol", byteArrayOf(1, 2, 3))

        val path =
            RuxSystemVolumeWorkspace.prepareStorage0Volume(
                workspace = workspace,
                resourcePath = "firmware/test-storage0.ruxvol",
                classLoader = loader,
            )

        assertEquals(existing, path)
        assertContentEquals(byteArrayOf(7, 8, 9), path.readBytes())
    }

    @Test
    fun missingStorage0VolumeResourceFailsFast() {
        val workspace = createTempDirectory("rux-system-volume-workspace-")

        assertFailsWith<IllegalStateException> {
            RuxSystemVolumeWorkspace.prepareStorage0Volume(
                workspace = workspace,
                resourcePath = "firmware/missing-storage0.ruxvol",
                classLoader = resourceClassLoader("firmware/other.ruxvol", byteArrayOf(1, 2)),
            )
        }
    }

    @Test
    fun defaultStorage0VolumeResourceIsRuxvol() {
        assertEquals("firmware/rux16-system-storage0.ruxvol", RuxSystemVolumeWorkspace.DEFAULT_STORAGE0_VOLUME_RESOURCE)
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

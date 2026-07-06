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

package ru.lazyhat.compukterkraft.lang.runtime.kraftos

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KraftOsArtifactsTest {
    @Test
    fun exposesBundledRuntimeArtifactContract() {
        assertEquals("firmware/k16-bios.kflash", KraftOsArtifacts.BIOS_FLASH_RESOURCE)
        assertEquals("firmware/k16-system-storage0.kv", KraftOsArtifacts.STORAGE0_VOLUME_RESOURCE)
        assertEquals("bios.kflash", KraftOsArtifacts.BIOS_FLASH_FILENAME)
        assertEquals("storage0.kv", KraftOsArtifacts.STORAGE0_VOLUME_FILENAME)
    }

    @Test
    fun preparesBundledArtifactsIntoRuntimeWorkspace() {
        val workspace = createTempDirectory("kraftos-artifacts-")
        val biosBytes = byteArrayOf(0x01, 0x10, 0x01, 0xe0.toByte())
        val storageBytes = "K16VOL".encodeToByteArray() + byteArrayOf(1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val loader =
            resourceClassLoader(
                mapOf(
                    "firmware/test-bios.kflash" to biosBytes,
                    "firmware/test-storage0.kv" to storageBytes,
                ),
            )

        val paths =
            KraftOsArtifacts.prepareWorkspace(
                workspace = workspace,
                biosFlashResource = "firmware/test-bios.kflash",
                storage0VolumeResource = "firmware/test-storage0.kv",
                classLoader = loader,
            )

        assertEquals(workspace.resolve("bios.kflash"), paths.biosFlashPath)
        assertEquals(workspace.resolve("volumes/storage0.kv"), paths.storage0VolumePath)
        assertTrue(paths.biosFlashPath.exists())
        assertTrue(paths.storage0VolumePath.exists())
        assertContentEquals(biosBytes, paths.biosFlashPath.readBytes())
        assertContentEquals(storageBytes, paths.storage0VolumePath.readBytes())
    }

    @Test
    fun preparesDefaultWorkspaceArtifactsFromBundledManifest() {
        val workspace = createTempDirectory("kraftos-artifacts-")
        val biosBytes = byteArrayOf(0x01, 0x10, 0x01, 0xe0.toByte())
        val storageBytes = "K16VOL".encodeToByteArray() + byteArrayOf(1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val loader =
            resourceClassLoader(
                mapOf(
                    KraftOsArtifactManifest.DEFAULT_RESOURCE to
                        """
                        schema=1
                        target=k16
                        profile=production
                        artifact.biosFlash.resource=firmware/manifest-bios.kflash
                        artifact.biosFlash.format=kflash
                        artifact.systemStorage0.resource=firmware/manifest-storage0.kv
                        artifact.systemStorage0.format=kfs-kv
                        """.trimIndent().encodeToByteArray(),
                    "firmware/manifest-bios.kflash" to biosBytes,
                    "firmware/manifest-storage0.kv" to storageBytes,
                ),
            )

        val paths =
            KraftOsArtifacts.prepareWorkspace(
                workspace = workspace,
                classLoader = loader,
            )

        assertContentEquals(biosBytes, paths.biosFlashPath.readBytes())
        assertContentEquals(storageBytes, paths.storage0VolumePath.readBytes())
    }

    private fun resourceClassLoader(resources: Map<String, ByteArray>): ClassLoader =
        object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): InputStream? =
                resources[name]?.let(::ByteArrayInputStream)
        }
}

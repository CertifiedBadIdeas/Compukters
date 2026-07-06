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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KraftOsArtifactManifestTest {
    @Test
    fun parsesSupportedK16Manifest() {
        val manifest =
            KraftOsArtifactManifest.parse(
                text =
                    """
                    schema=1
                    target=k16
                    artifact.biosFlash.resource=firmware/k16-bios.kflash
                    artifact.biosFlash.format=kflash
                    artifact.systemStorage0.resource=firmware/k16-system-storage0.kv
                    artifact.systemStorage0.format=kfs-kv
                    """.trimIndent(),
                source = "test manifest",
            )

        assertEquals(1, manifest.schema)
        assertEquals("k16", manifest.target)
        assertEquals("firmware/k16-bios.kflash", manifest.biosFlash.resource)
        assertEquals("kflash", manifest.biosFlash.format)
        assertEquals("firmware/k16-system-storage0.kv", manifest.systemStorage0.resource)
        assertEquals("kfs-kv", manifest.systemStorage0.format)
    }

    @Test
    fun loadsManifestFromClasspathResource() {
        val loader =
            resourceClassLoader(
                "firmware/test-kraftos-artifacts.properties",
                """
                schema=1
                target=k16
                artifact.biosFlash.resource=firmware/test-bios.kflash
                artifact.biosFlash.format=kflash
                artifact.systemStorage0.resource=firmware/test-storage0.kv
                artifact.systemStorage0.format=kfs-kv
                """.trimIndent().encodeToByteArray(),
            )

        val manifest =
            KraftOsArtifactManifest.load(
                resourcePath = "firmware/test-kraftos-artifacts.properties",
                classLoader = loader,
            )

        assertEquals("firmware/test-bios.kflash", manifest.biosFlash.resource)
        assertEquals("firmware/test-storage0.kv", manifest.systemStorage0.resource)
    }

    @Test
    fun rejectsUnsupportedSchema() {
        val error =
            assertFailsWith<IllegalStateException> {
                KraftOsArtifactManifest.parse(
                    text =
                        """
                        schema=2
                        target=k16
                        artifact.biosFlash.resource=firmware/k16-bios.kflash
                        artifact.biosFlash.format=kflash
                        artifact.systemStorage0.resource=firmware/k16-system-storage0.kv
                        artifact.systemStorage0.format=kfs-kv
                        """.trimIndent(),
                    source = "test manifest",
                )
            }

        assertTrue(error.message.orEmpty().contains("unsupported KraftOS artifact manifest schema"))
    }

    @Test
    fun rejectsMissingRequiredResource() {
        val error =
            assertFailsWith<IllegalStateException> {
                KraftOsArtifactManifest.parse(
                    text =
                        """
                        schema=1
                        target=k16
                        artifact.biosFlash.format=kflash
                        artifact.systemStorage0.resource=firmware/k16-system-storage0.kv
                        artifact.systemStorage0.format=kfs-kv
                        """.trimIndent(),
                    source = "test manifest",
                )
            }

        assertTrue(error.message.orEmpty().contains("missing artifact.biosFlash.resource"))
    }

    @Test
    fun rejectsUnsupportedArtifactFormat() {
        val error =
            assertFailsWith<IllegalStateException> {
                KraftOsArtifactManifest.parse(
                    text =
                        """
                        schema=1
                        target=k16
                        artifact.biosFlash.resource=firmware/k16-bios.kflash
                        artifact.biosFlash.format=elf
                        artifact.systemStorage0.resource=firmware/k16-system-storage0.kv
                        artifact.systemStorage0.format=kfs-kv
                        """.trimIndent(),
                    source = "test manifest",
                )
            }

        assertTrue(error.message.orEmpty().contains("unsupported artifact.biosFlash.format"))
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

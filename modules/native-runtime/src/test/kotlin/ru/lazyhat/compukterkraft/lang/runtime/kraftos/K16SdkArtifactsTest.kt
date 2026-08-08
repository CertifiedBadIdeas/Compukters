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

import ru.lazyhat.compukterkraft.lang.runtime.storage.K16ImmutableArtifactWorkspace
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class K16SdkArtifactsTest {
    @Test
    fun resolvesOnlyManifestOwnedSdkResources() {
        val bytes = "K16VOL-sdk-fixture".encodeToByteArray()
        val manifest =
            KraftOsArtifactManifest.parse(
                text =
                    """
                    schema=1
                    target=k16
                    profile=development
                    artifact.biosFlash.resource=firmware/k16-bios.kflash
                    artifact.biosFlash.format=kflash
                    artifact.systemStorage0.resource=firmware/k16-system-storage0-dev.kv
                    artifact.systemStorage0.format=kfs-kv
                    artifact.sdk.sdk_fixture_v1.resource=firmware/sdk-fixture-v1.kv
                    artifact.sdk.sdk_fixture_v1.format=kfs-kv
                    """.trimIndent(),
                source = "test SDK manifest",
            )
        val loader = resourceClassLoader(mapOf("firmware/sdk-fixture-v1.kv" to bytes))
        val artifacts =
            K16SdkArtifacts(
                manifest = manifest,
                workspace = K16ImmutableArtifactWorkspace(createTempDirectory("k16-sdk-artifacts-")),
                classLoader = loader,
            )

        assertContentEquals(bytes, artifacts.resolve("sdk_fixture_v1").readBytes())
        assertFailsWith<IllegalArgumentException> { artifacts.resolve("missing") }
    }

    @Test
    fun missingBundledSdkResourceIsAHardError() {
        val manifest =
            KraftOsArtifactManifest.parse(
                text =
                    """
                    schema=1
                    target=k16
                    profile=development
                    artifact.biosFlash.resource=firmware/k16-bios.kflash
                    artifact.biosFlash.format=kflash
                    artifact.systemStorage0.resource=firmware/k16-system-storage0-dev.kv
                    artifact.systemStorage0.format=kfs-kv
                    artifact.sdk.sdk_fixture_v1.resource=firmware/missing.kv
                    artifact.sdk.sdk_fixture_v1.format=kfs-kv
                    """.trimIndent(),
                source = "test SDK manifest",
            )
        val artifacts =
            K16SdkArtifacts(
                manifest = manifest,
                workspace = K16ImmutableArtifactWorkspace(createTempDirectory("k16-sdk-artifacts-")),
                classLoader = resourceClassLoader(emptyMap()),
            )

        assertFailsWith<IllegalStateException> { artifacts.resolve("sdk_fixture_v1") }
    }

    private fun resourceClassLoader(resources: Map<String, ByteArray>): ClassLoader =
        object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): InputStream? =
                resources[name]?.let(::ByteArrayInputStream)
        }
}

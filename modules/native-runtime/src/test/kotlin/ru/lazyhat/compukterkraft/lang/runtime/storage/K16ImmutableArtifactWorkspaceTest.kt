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

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class K16ImmutableArtifactWorkspaceTest {
    @Test
    fun materializedArtifactIsReadOnlyIncludingARepeatedResolution() {
        val workspace = K16ImmutableArtifactWorkspace(createTempDirectory("k16-sdk-artifacts-"))
        val bytes = "fixture".encodeToByteArray()
        val artifact = workspace.materialize("sdk_fixture_v1", bytes)

        artifact.toFile().setWritable(true)
        workspace.materialize("sdk_fixture_v1", bytes)

        assertTrue(
            PosixFilePermission.OWNER_WRITE !in Files.getPosixFilePermissions(artifact),
            "resolved immutable artifact must not retain owner write permission",
        )
    }

    @Test
    fun existingIdentityCannotMaterializeDifferentBytes() {
        val workspace = K16ImmutableArtifactWorkspace(createTempDirectory("k16-sdk-artifacts-"))
        workspace.materialize("sdk_fixture_v1", "first".encodeToByteArray())

        assertFailsWith<IllegalStateException> {
            workspace.materialize("sdk_fixture_v1", "different".encodeToByteArray())
        }
    }

    @Test
    fun concurrentMaterializationPublishesOneCompleteArtifact() {
        val root = createTempDirectory("k16-sdk-artifacts-")
        val workspace = K16ImmutableArtifactWorkspace(root)
        val bytes = ByteArray(32 * 1024) { index -> (index * 31).toByte() }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures =
                List(2) {
                    executor.submit<java.nio.file.Path> {
                        ready.countDown()
                        start.await()
                        workspace.materialize("sdk_fixture_v1", bytes)
                    }
                }
            ready.await()
            start.countDown()
            val paths = futures.map { it.get() }

            assertEquals(paths[0], paths[1])
            assertContentEquals(bytes, paths[0].readBytes())
            val artifactDirectory = root.resolve("compukterkraft/artifacts")
            assertEquals(listOf(paths[0]), artifactDirectory.listDirectoryEntries("*.kv"))
            assertTrue(artifactDirectory.listDirectoryEntries("*.tmp").isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }
}

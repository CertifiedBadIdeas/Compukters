/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkerPayloadTest {
    @Test
    fun `validated payload is published once and then reused`() =
        withTempDirectory { root ->
            val files = linkedMapOf("lib/compiler-k2.jar" to byteArrayOf(1, 2, 3), "lib/compiler.jar" to byteArrayOf(4, 5))
            val manifest = WorkerPayloadManifest.create(testIdentity(), "example.WorkerMain", files)
            val source = MapPayloadSource(files)

            assertEquals(manifest.payloadHash, manifest.identity.payloadHash)

            val first = WorkerPayloadPublisher.publish(manifest, source, root.resolve("cache"))
            val second = WorkerPayloadPublisher.publish(manifest, source, root.resolve("cache"))

            assertEquals(first.root, second.root)
            assertEquals(listOf("compiler-k2.jar", "compiler.jar"), first.classpath.map { it.fileName.toString() })
            assertContentEquals(files.getValue("lib/compiler.jar"), first.root.resolve("lib/compiler.jar").readBytes())
            assertTrue(
                first.root
                    .resolve("worker.payload")
                    .toFile()
                    .isFile,
            )
        }

    @Test
    fun `hash mismatch and traversal fail without publishing a payload`() =
        withTempDirectory { root ->
            val admitted = linkedMapOf("lib/compiler.jar" to byteArrayOf(1))
            val manifest = WorkerPayloadManifest.create(testIdentity(), "example.WorkerMain", admitted)

            assertFailsWith<WorkerPayloadException> {
                WorkerPayloadPublisher.publish(manifest, MapPayloadSource(mapOf("lib/compiler.jar" to byteArrayOf(2))), root.resolve("bad"))
            }
            assertEquals(false, Files.exists(root.resolve("bad").resolve(manifest.payloadHash.hex())))

            assertFailsWith<IllegalArgumentException> {
                WorkerPayloadManifest.create(testIdentity(), "example.WorkerMain", mapOf("../escape.jar" to byteArrayOf(1)))
            }
        }

    @Test
    fun `forged manifests are rejected before publication`() =
        withTempDirectory { root ->
            val file = WorkerPayloadFile("lib/compiler.jar", 1, Hash256.zero())
            val duplicate = WorkerPayloadManifest(testIdentity(), "example.WorkerMain", listOf(file, file), Hash256.zero())

            assertFailsWith<WorkerPayloadException> {
                WorkerPayloadPublisher.publish(duplicate, MapPayloadSource(emptyMap()), root.resolve("cache"))
            }
        }

    @Test
    fun `missing file removes only the failed staging directory`() =
        withTempDirectory { root ->
            val files = linkedMapOf("lib/a.jar" to byteArrayOf(1), "lib/b.jar" to byteArrayOf(2))
            val manifest = WorkerPayloadManifest.create(testIdentity(), "example.WorkerMain", files)
            val cache = root.resolve("cache")
            Files.createDirectories(cache)
            Files.writeString(cache.resolve("owned-by-someone-else"), "keep")

            assertFailsWith<WorkerPayloadException> {
                WorkerPayloadPublisher.publish(manifest, MapPayloadSource(mapOf("lib/a.jar" to byteArrayOf(1))), cache)
            }

            assertEquals("keep", Files.readString(cache.resolve("owned-by-someone-else")))
            Files.list(cache).use { paths ->
                assertEquals(listOf("owned-by-someone-else"), paths.map { it.fileName.toString() }.sorted().toList())
            }
        }

    @Test
    fun `concurrent publication converges on one content directory`() =
        withTempDirectory { root ->
            val files = linkedMapOf("lib/compiler.jar" to byteArrayOf(1, 2, 3))
            val manifest = WorkerPayloadManifest.create(testIdentity(), "example.WorkerMain", files)
            val bothOpened = CountDownLatch(2)
            val release = CountDownLatch(1)
            val source =
                WorkerPayloadSource { path ->
                    bothOpened.countDown()
                    release.await()
                    ByteArrayInputStream(files.getValue(path))
                }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first =
                    executor.submit<PublishedWorkerPayload> {
                        WorkerPayloadPublisher.publish(
                            manifest,
                            source,
                            root.resolve("cache"),
                        )
                    }
                val second =
                    executor.submit<PublishedWorkerPayload> {
                        WorkerPayloadPublisher.publish(
                            manifest,
                            source,
                            root.resolve("cache"),
                        )
                    }
                bothOpened.await()
                release.countDown()

                assertEquals(first.get().root, second.get().root)
                Files.list(root.resolve("cache")).use { paths -> assertEquals(1, paths.count()) }
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }

    private fun testIdentity(): WorkerIdentity = WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero())

    private inline fun withTempDirectory(block: (java.nio.file.Path) -> Unit) {
        val directory = createTempDirectory("compukters-worker-payload-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

private class MapPayloadSource(
    private val files: Map<String, ByteArray>,
) : WorkerPayloadSource {
    override fun open(path: String) = ByteArrayInputStream(files.getValue(path))
}

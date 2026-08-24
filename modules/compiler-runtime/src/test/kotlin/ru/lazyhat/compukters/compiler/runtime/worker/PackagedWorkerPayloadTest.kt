/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.compiler.runtime.worker

import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadException
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadManifest
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadPublisher
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackagedWorkerPayloadTest {
    @Test
    fun `validated package is published once and reused by content hash`() =
        withPackage { archive, root, expected ->
            val first = PackagedWorkerPayload.publish(ByteArrayInputStream(archive), root)
            val second = PackagedWorkerPayload.publish(ByteArrayInputStream(archive), root)

            assertEquals(first.root, second.root)
            assertEquals(expected.identity, first.manifest.identity)
            assertContentEquals(WORKER_BYTES, first.classpath.single().readBytes())
        }

    @Test
    fun `package accepts bounded license metadata without adding it to the worker classpath`() =
        withPackage { archive, root, expected ->
            val licensed =
                appendZipEntries(
                    archive,
                    "META-INF/licenses/Compukters-Apache-2.0.txt" to "Apache License 2.0".toByteArray(),
                    "META-INF/licenses/kotlin/v2.4.10/NOTICE.txt" to "Kotlin Compiler".toByteArray(),
                    "META-INF/NOTICE.txt" to "Compukters".toByteArray(),
                    "META-INF/THIRD-PARTY-NOTICES.md" to "Third-party notices".toByteArray(),
                )

            val published = PackagedWorkerPayload.publish(ByteArrayInputStream(licensed), root)

            assertEquals(expected.identity, published.manifest.identity)
            assertContentEquals(WORKER_BYTES, published.classpath.single().readBytes())
        }

    @Test
    fun `package rejects unsafe duplicate and over-budget entries`() {
        val root = createTempDirectory("compukters-packaged-worker-reject-").toAbsolutePath().normalize()
        try {
            listOf(
                "../escape.jar",
                "/absolute.jar",
                "lib/../escape.jar",
                "lib\\escape.jar",
                "META-INF/arbitrary.txt",
                "META-INF/licenses/../escape.txt",
                "licenses/Compukters.txt",
            ).forEach { entry ->
                assertFailsWith<PackagedWorkerPayloadException> {
                    PackagedWorkerPayload.publish(ByteArrayInputStream(zip(entry to byteArrayOf(1))), root)
                }
            }
            assertFailsWith<PackagedWorkerPayloadException> {
                PackagedWorkerPayload.publish(
                    ByteArrayInputStream(duplicateZip()),
                    root,
                )
            }
            assertFailsWith<PackagedWorkerPayloadException> {
                PackagedWorkerPayload.publish(
                    ByteArrayInputStream(zip("worker.payload" to ByteArray(5))),
                    root,
                    PackagedWorkerPayloadLimits(entries = 1, bytes = 4),
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `package and reused publication reject corrupt payload bytes`() =
        withPackage { archive, root, _ ->
            val published = PackagedWorkerPayload.publish(ByteArrayInputStream(archive), root)
            published.classpath.single().writeBytes(byteArrayOf(9, 9, 9))

            assertFailsWith<WorkerPayloadException> {
                PackagedWorkerPayload.publish(ByteArrayInputStream(archive), root)
            }
        }

    private fun withPackage(block: (ByteArray, Path, WorkerPayloadManifest) -> Unit) {
        val root = createTempDirectory("compukters-packaged-worker-").toAbsolutePath().normalize()
        val source = root.resolve("source").createDirectories()
        try {
            val manifest = WorkerPayloadManifest.create(identity(), MAIN_CLASS, mapOf("lib/worker.jar" to WORKER_BYTES))
            val published =
                WorkerPayloadPublisher.publish(
                    manifest,
                    { path -> ByteArrayInputStream(if (path == "lib/worker.jar") WORKER_BYTES else error(path)) },
                    source,
                )
            block(zipDirectory(published.root), root.resolve("published"), manifest)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun zipDirectory(root: Path): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { path ->
                    zip.putNextEntry(ZipEntry(root.relativize(path).joinToString("/")))
                    zip.write(path.readBytes())
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun appendZipEntries(
        archive: ByteArray,
        vararg entries: Pair<String, ByteArray>,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { target ->
            ZipInputStream(ByteArrayInputStream(archive)).use { source ->
                while (true) {
                    val entry = source.nextEntry ?: break
                    target.putNextEntry(ZipEntry(entry.name))
                    source.copyTo(target)
                    target.closeEntry()
                    source.closeEntry()
                }
            }
            entries.forEach { (name, bytes) ->
                target.putNextEntry(ZipEntry(name))
                target.write(bytes)
                target.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun duplicateZip(): ByteArray {
        val bytes = zip("lib/a.jar" to byteArrayOf(1), "lib/b.jar" to byteArrayOf(2))
        val original = "lib/b.jar".encodeToByteArray()
        val duplicate = "lib/a.jar".encodeToByteArray()
        var index = 0
        while (index <= bytes.size - original.size) {
            if (bytes.copyOfRange(index, index + original.size).contentEquals(original)) {
                duplicate.copyInto(bytes, index)
                index += original.size
            } else {
                index++
            }
        }
        return bytes
    }

    private fun identity() =
        WorkerIdentity(
            compilerVersion = "2.4.10",
            languageVersion = "2.4",
            codegenAbi = 1u,
            artifactWriterVersion = 1u,
            payloadHash = Hash256.zero(),
            standardLibraryAbi = Hash256.zero(),
        )

    private companion object {
        const val MAIN_CLASS = "compukter.Worker"
        val WORKER_BYTES = byteArrayOf(1, 2, 3, 4)
    }
}

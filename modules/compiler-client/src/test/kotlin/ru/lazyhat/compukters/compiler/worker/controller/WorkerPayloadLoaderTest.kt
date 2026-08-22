/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.compiler.worker.controller

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerPayloadLoaderTest {
    @Test
    fun `loads canonical payload and validates every published jar`() =
        withPayload { root, manifest ->
            val loaded = WorkerPayloadLoader.load(root)

            assertEquals(manifest, loaded.manifest)
            assertEquals(listOf(root.resolve("lib/worker.jar")), loaded.classpath)
        }

    @Test
    fun `rejects malformed or noncanonical manifests`() {
        val mutations =
            listOf<(String) -> String>(
                { it.replace("format=1", "format=2") },
                { it.replace("compiler=", "unknown=x\ncompiler=") },
                { it.replace("compiler=", "compiler=duplicate\ncompiler=") },
                { it.replace("compiler=2.3.20\nlanguage=2.4", "language=2.4\ncompiler=2.3.20") },
                { it.replace("file=lib/worker.jar", "file=../worker.jar") },
                { it.replace("\t3\t", "\tbad\t") },
                { it.replace(Regex("file=.*"), "file=broken") },
            )
        mutations.forEach { mutate ->
            withPayload { root, _ ->
                val path = root.resolve("worker.payload")
                path.writeBytes(mutate(Files.readString(path)).encodeToByteArray())
                assertFailsWith<WorkerPayloadException> { WorkerPayloadLoader.load(root) }
            }
        }
        withPayload { root, _ ->
            root.resolve("worker.payload").writeBytes(byteArrayOf(0xc3.toByte()))
            assertFailsWith<WorkerPayloadException> { WorkerPayloadLoader.load(root) }
        }
        withPayload { root, _ ->
            assertFailsWith<WorkerPayloadException> {
                WorkerPayloadLoader.load(root, WorkerPayloadLoadLimits(manifestBytes = 1))
            }
        }
    }

    @Test
    fun `rejects missing changed and symbolic-link payload files`() {
        withPayload { root, _ ->
            Files.delete(root.resolve("lib/worker.jar"))
            assertFailsWith<WorkerPayloadException> { WorkerPayloadLoader.load(root) }
        }
        withPayload { root, _ ->
            root.resolve("lib/worker.jar").writeBytes(byteArrayOf(9, 8, 7))
            assertFailsWith<WorkerPayloadException> { WorkerPayloadLoader.load(root) }
        }
        withPayload { root, _ ->
            val jar = root.resolve("lib/worker.jar")
            val target = root.resolve("real.jar").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            Files.delete(jar)
            Files.createSymbolicLink(jar, target)
            assertFailsWith<WorkerPayloadException> { WorkerPayloadLoader.load(root) }
        }
    }

    @Test
    fun `enforces file count and checked total payload bytes`() =
        withPayload { root, _ ->
            assertFailsWith<WorkerPayloadException> {
                WorkerPayloadLoader.load(root, WorkerPayloadLoadLimits(files = 0))
            }
            assertFailsWith<WorkerPayloadException> {
                WorkerPayloadLoader.load(root, WorkerPayloadLoadLimits(payloadBytes = 2))
            }
        }

    private fun withPayload(block: (Path, WorkerPayloadManifest) -> Unit) {
        val root = createTempDirectory("compukters-payload-loader-")
        try {
            val bytes = byteArrayOf(1, 2, 3)
            val manifest =
                WorkerPayloadManifest.create(
                    WorkerIdentity("2.3.20", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero()),
                    "worker.MainKt",
                    mapOf("lib/worker.jar" to bytes),
                )
            root.resolve("lib").createDirectories()
            root.resolve("lib/worker.jar").writeBytes(bytes)
            root.resolve("worker.payload").writeBytes(render(manifest).encodeToByteArray())
            block(root, manifest)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun render(manifest: WorkerPayloadManifest): String =
        buildString {
            appendLine("format=1")
            appendLine("compiler=${manifest.identity.compilerVersion}")
            appendLine("language=${manifest.identity.languageVersion}")
            appendLine("codegenAbi=${manifest.identity.codegenAbi}")
            appendLine("artifactWriter=${manifest.identity.artifactWriterVersion}")
            appendLine("mainClass=${manifest.mainClass}")
            appendLine("payloadSha256=${manifest.payloadHash.hex()}")
            manifest.files.forEach { file ->
                appendLine("file=${file.path}\t${file.bytes}\t${file.sha256.hex()}")
            }
        }
}

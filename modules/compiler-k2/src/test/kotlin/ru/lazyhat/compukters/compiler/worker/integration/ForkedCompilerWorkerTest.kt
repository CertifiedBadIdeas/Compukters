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

package ru.lazyhat.compukters.compiler.worker.integration

import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerController
import ru.lazyhat.compukters.compiler.worker.controller.CompilerWorkerPolicy
import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.PublishedWorkerPayload
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadFile
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadManifest
import ru.lazyhat.compukters.compiler.worker.controller.WorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.DiagnosticCategory
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ForkedCompilerWorkerTest {
    @Test
    fun `real worker is deterministic and remains healthy after compiler failures`() {
        val payload = payload(Path.of(checkNotNull(System.getProperty("compukters.worker.payload"))))
        val temporaryRoot = createTempDirectory("compukters-forked-worker-")
        var starts = 0
        val jdk = JdkWorkerProcessFactory()
        val factory = WorkerProcessFactory { published, launch -> starts++.let { jdk.start(published, launch) } }
        val limits = WorkerLimits()
        val launch =
            WorkerLaunch(
                Path.of(checkNotNull(System.getProperty("compukters.worker.java"))),
                512,
                256,
                temporaryRoot.resolve("child"),
                payload.manifest.identity,
                limits.frameBytes,
                limits.stderrBytes,
            )
        try {
            CompilerWorkerController(
                payload,
                launch,
                limits,
                factory,
                CompilerWorkerPolicy(startupTimeoutNanos = 30_000_000_000, compilationTimeoutNanos = 60_000_000_000),
            ).use { controller ->
                val first = assertIs<CompileSuccess>(compile(controller, "val answer: Int = 42"))
                val second = assertIs<CompileSuccess>(compile(controller, "val answer: Int = 42"))
                assertContentEquals(first.artifact.toByteArray(), second.artifact.toByteArray())
                assertEquals(first.artifactHash, second.artifactHash)
                assertContentEquals(
                    byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte()),
                    first.artifact.toByteArray().copyOf(4),
                )

                val syntax = assertIs<CompilerFailure>(compile(controller, "val answer = )"))
                assertTrue(syntax.diagnostics.any { it.category == DiagnosticCategory.SYNTAX })
                val type = assertIs<CompilerFailure>(compile(controller, "val answer: Missing = 42"))
                assertTrue(type.diagnostics.any { it.category == DiagnosticCategory.TYPE })
                assertIs<CompileSuccess>(compile(controller, "val answer: Int = 42"))
                assertEquals(1, starts, "compiler failures must not restart a healthy worker")
            }
        } finally {
            temporaryRoot.toFile().deleteRecursively()
        }
    }

    private fun compile(
        controller: CompilerWorkerController,
        source: String,
    ) = controller.compile(BinaryValue.of(source.encodeToByteArray())).get(90, TimeUnit.SECONDS)

    private fun payload(root: Path): PublishedWorkerPayload {
        val lines = Files.readAllLines(root.resolve("worker.payload"))
        val properties =
            lines.filterNot { it.startsWith("file=") }.associate { line ->
                line.substringBefore('=') to
                    line.substringAfter('=')
            }
        val records =
            lines.filter { it.startsWith("file=") }.map { line ->
                val fields = line.removePrefix("file=").split('\t')
                WorkerPayloadFile(fields[0], fields[1].toLong(), Hash256.of(fields[2].decodeHex()))
            }
        val identity =
            WorkerIdentity(
                properties.getValue("compiler"),
                properties.getValue("language"),
                properties.getValue("codegenAbi").toUInt(),
                properties.getValue("artifactWriter").toUInt(),
                Hash256.of(properties.getValue("payloadSha256").decodeHex()),
                Hash256.zero(),
            )
        val manifest =
            WorkerPayloadManifest(
                identity,
                properties.getValue("mainClass"),
                records,
                identity.payloadHash,
            )
        return PublishedWorkerPayload(root, manifest, records.map { root.resolve(it.path) })
    }
}

private fun String.decodeHex(): ByteArray = ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }

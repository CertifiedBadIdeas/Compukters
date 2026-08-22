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

import ru.lazyhat.compukters.compiler.worker.controller.JdkWorkerProcessFactory
import ru.lazyhat.compukters.compiler.worker.controller.PublishedWorkerPayload
import ru.lazyhat.compukters.compiler.worker.controller.WorkerLaunch
import ru.lazyhat.compukters.compiler.worker.controller.WorkerMeasurementReport
import ru.lazyhat.compukters.compiler.worker.controller.WorkerMetrics
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadFile
import ru.lazyhat.compukters.compiler.worker.controller.WorkerPayloadManifest
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerCodec
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerHandshake
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessage
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessageCodec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkerMeasurementTest {
    @Test
    fun `records stable worker measurement fields without performance thresholds`() {
        val payload = measurementPayload(Path.of(checkNotNull(System.getProperty("compukters.worker.payload"))))
        val limits = WorkerLimits()
        val temporary = createTempDirectory("compukters-worker-measurement-")
        val launch =
            WorkerLaunch(
                Path.of(checkNotNull(System.getProperty("compukters.worker.java"))),
                512,
                256,
                temporary.resolve("child"),
                payload.manifest.identity,
                limits.frameBytes,
                limits.stderrBytes,
            )
        val started = System.nanoTime()
        val process = JdkWorkerProcessFactory().start(payload, launch)
        try {
            assertIs<WorkerHandshake>(read(process.readFrame(deadline())!!, limits))
            val startupMillis = elapsedMillis(started)
            val firstRequest = request(1uL, payload.manifest.identity, limits)
            val firstStarted = System.nanoTime()
            val firstFrame = exchange(process, firstRequest, limits)
            val firstMillis = elapsedMillis(firstStarted)
            val first = assertIs<CompileSuccess>(read(firstFrame, limits))
            val warmRequest = request(2uL, payload.manifest.identity, limits)
            val warmStarted = System.nanoTime()
            val warmFrame = exchange(process, warmRequest, limits)
            val warmMillis = elapsedMillis(warmStarted)
            assertIs<CompileSuccess>(read(warmFrame, limits))

            val report =
                WorkerMeasurementReport(
                    payloadBytes = payload.manifest.files.sumOf(WorkerPayloadFile::bytes),
                    payloadSha256 = payload.manifest.payloadHash.hex(),
                    coldStartupMillis = startupMillis,
                    firstCompilationMillis = firstMillis,
                    warmCompilationMillis = warmMillis,
                    workerHeapBytes = first.metrics.heapBytes,
                    workerMetaspaceBytes = first.metrics.metaspaceBytes,
                    peakRssBytes = null,
                    requestBytes = encode(firstRequest).size,
                    resultBytes = firstFrame.size,
                    artifactBytes = first.artifact.size,
                )
            val output = Path.of(checkNotNull(System.getProperty("compukters.worker.measurement-report")))
            WorkerMetrics.write(report, output)
            assertEquals(report.toJson(), Files.readString(output))
            assertTrue(report.artifactBytes > 0)
        } finally {
            process.close()
            temporary.toFile().deleteRecursively()
        }
    }

    private fun exchange(
        process: ru.lazyhat.compukters.compiler.worker.controller.WorkerProcess,
        request: CompileRequest,
        limits: WorkerLimits,
    ): ByteArray {
        process.writeFrame(encode(request))
        return checkNotNull(process.readFrame(deadline())) { "worker exited before result" }.also {
            WorkerCodec.decodeFrame(it, limits.frameBytes)
        }
    }

    private fun request(
        id: ULong,
        identity: WorkerIdentity,
        limits: WorkerLimits,
    ) = CompileRequest(
        RequestId.of(id),
        VirtualSourcePath.of("project/main.kts"),
        BinaryValue.of("val answer: Int = 42".encodeToByteArray()),
        TargetSettings.KOTLIN_2_4_JVM_17,
        identity,
        limits,
    )

    private fun encode(message: WorkerMessage) = WorkerCodec.encodeFrame(WorkerMessageCodec.encode(message))

    private fun read(
        bytes: ByteArray,
        limits: WorkerLimits,
    ) = WorkerMessageCodec.decode(WorkerCodec.decodeFrame(bytes, limits.frameBytes))

    private fun deadline() = System.nanoTime() + 90_000_000_000

    private fun elapsedMillis(started: Long) = (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000
}

private fun measurementPayload(root: Path): PublishedWorkerPayload {
    val lines = Files.readAllLines(root.resolve("worker.payload"))
    val properties = lines.filterNot { it.startsWith("file=") }.associate { it.substringBefore('=') to it.substringAfter('=') }
    val files =
        lines.filter { it.startsWith("file=") }.map { line ->
            val fields = line.removePrefix("file=").split('\t')
            WorkerPayloadFile(fields[0], fields[1].toLong(), Hash256.of(fields[2].measurementHex()))
        }
    val identity =
        WorkerIdentity(
            properties.getValue("compiler"),
            properties.getValue("language"),
            properties.getValue("codegenAbi").toUInt(),
            properties.getValue("artifactWriter").toUInt(),
            Hash256.of(properties.getValue("payloadSha256").measurementHex()),
            Hash256.zero(),
        )
    return PublishedWorkerPayload(
        root,
        WorkerPayloadManifest(identity, properties.getValue("mainClass"), files, identity.payloadHash),
        files.map { root.resolve(it.path) },
    )
}

private fun String.measurementHex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

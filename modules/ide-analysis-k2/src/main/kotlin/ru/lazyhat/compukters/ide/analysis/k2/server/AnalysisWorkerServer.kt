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

package ru.lazyhat.compukters.ide.analysis.k2.server

import ru.lazyhat.compukters.ide.analysis.k2.standalone.AdmittedK2Snapshot
import ru.lazyhat.compukters.ide.analysis.k2.standalone.SnapshotAdmission
import ru.lazyhat.compukters.ide.analysis.protocol.ANALYSIS_PROTOCOL_VERSION
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisCancelled
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailure
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFailureKind
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFeature
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisFrameCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisHandshake
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisLimits
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessage
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisMessageCodec
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisProtocolContext
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisQueryRequest
import ru.lazyhat.compukters.ide.analysis.protocol.AnalysisWorkerIdentity
import ru.lazyhat.compukters.ide.analysis.protocol.CancelAnalysisRequest
import ru.lazyhat.compukters.ide.analysis.protocol.CloseSnapshotRequest
import ru.lazyhat.compukters.ide.analysis.protocol.OpenSnapshotRequest
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotClosed
import ru.lazyhat.compukters.ide.analysis.protocol.SnapshotReady
import java.io.InputStream
import java.io.OutputStream

internal enum class AnalysisServerExit { CleanEof, ProtocolError }

internal fun interface AnalysisQueryHandler {
    fun execute(
        request: AnalysisQueryRequest,
        snapshot: AdmittedK2Snapshot,
        cancellation: AnalysisCancellation,
    ): AnalysisMessage
}

internal class AnalysisWorkerServer(
    private val identity: AnalysisWorkerIdentity,
    private val limits: AnalysisLimits,
    private val input: InputStream,
    private val output: OutputStream,
    private val admission: SnapshotAdmission,
    private val queryHandler: AnalysisQueryHandler = UNSUPPORTED_QUERY_HANDLER,
) : AutoCloseable {
    private val outputLock = Any()
    private val execution = AnalysisExecutionQueue(MAXIMUM_QUEUED_REQUESTS)
    private var active: AdmittedK2Snapshot? = null

    @Volatile private var activeIdentity: ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity? = null

    fun run(): AnalysisServerExit {
        write(AnalysisHandshake(ANALYSIS_PROTOCOL_VERSION, identity, AnalysisFeature.entries.toSet(), limits))
        while (true) {
            val frame =
                try {
                    readFrame() ?: return AnalysisServerExit.CleanEof
                } catch (_: IllegalArgumentException) {
                    return AnalysisServerExit.ProtocolError
                }
            val message =
                try {
                    AnalysisMessageCodec.decode(
                        AnalysisFrameCodec.decode(frame, limits.frameBytes),
                        AnalysisProtocolContext.unbound(limits),
                    )
                } catch (_: IllegalArgumentException) {
                    return AnalysisServerExit.ProtocolError
                }
            if (!accept(message)) return AnalysisServerExit.ProtocolError
        }
    }

    internal fun accept(message: AnalysisMessage): Boolean =
        when (message) {
            is OpenSnapshotRequest -> {
                submitOpen(message)
            }

            is CloseSnapshotRequest -> {
                submitClose(message)
            }

            is AnalysisQueryRequest -> {
                submitQuery(message)
            }

            is CancelAnalysisRequest -> {
                if (!execution.cancel(message.requestId)) write(AnalysisCancelled(message.requestId, activeIdentity))
                true
            }

            else -> {
                false
            }
        }

    override fun close() {
        execution.close()
        active?.close()
        active = null
        activeIdentity = null
    }

    private fun submitOpen(request: OpenSnapshotRequest): Boolean =
        submit(
            request.requestId,
            request.identity,
            task = { cancellation -> open(request, cancellation) },
        )

    private fun submitClose(request: CloseSnapshotRequest): Boolean =
        submit(
            request.requestId,
            request.identity,
            task = {
                if (active?.identity == request.identity) {
                    active?.close()
                    active = null
                    activeIdentity = null
                }
                write(SnapshotClosed(request.requestId, request.identity))
            },
        )

    private fun submitQuery(request: AnalysisQueryRequest): Boolean =
        submit(
            request.requestId,
            request.query.identity,
            task = { cancellation ->
                val snapshot = active
                if (snapshot == null || snapshot.identity != request.query.identity) {
                    write(
                        AnalysisFailure(
                            request.requestId,
                            request.query.identity,
                            AnalysisFailureKind.InvalidSnapshot,
                            "analysis snapshot is not active",
                        ),
                    )
                    return@submit
                }
                val response =
                    K2ProgressCancellation.run(cancellation) {
                        queryHandler.execute(request, snapshot, cancellation)
                    }
                if (!cancellation.isCancelled) write(response)
            },
        )

    private fun submit(
        requestId: ru.lazyhat.compukters.compiler.worker.protocol.RequestId,
        requestIdentity: ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity,
        task: (AnalysisCancellation) -> Unit,
    ): Boolean {
        val accepted =
            execution.submit(
                requestId,
                task,
                onCancelled = { write(AnalysisCancelled(requestId, requestIdentity)) },
                onFailure = { throwable ->
                    write(
                        AnalysisFailure(
                            requestId,
                            requestIdentity,
                            AnalysisFailureKind.InternalAnalysis,
                            bounded(throwable.message ?: "analysis request failed", limits.detailTextBytes),
                        ),
                    )
                },
            )
        if (!accepted) {
            write(
                AnalysisFailure(
                    requestId,
                    requestIdentity,
                    AnalysisFailureKind.OutputLimit,
                    "analysis request queue is full or request ID is duplicated",
                ),
            )
        }
        return true
    }

    private fun open(
        request: OpenSnapshotRequest,
        cancellation: AnalysisCancellation,
    ) {
        try {
            val candidate = admission.admit(request)
            if (cancellation.isCancelled) {
                candidate.close()
                return
            }
            val previous = active
            active = candidate
            activeIdentity = candidate.identity
            previous?.close()
            write(SnapshotReady(request.requestId, request.identity))
        } catch (exception: Exception) {
            if (!cancellation.isCancelled) {
                write(
                    AnalysisFailure(
                        request.requestId,
                        request.identity,
                        AnalysisFailureKind.InvalidSnapshot,
                        bounded(exception.message ?: "snapshot admission failed", request.limits.detailTextBytes),
                    ),
                )
            }
        }
    }

    private fun readFrame(): ByteArray? {
        val header = input.readExactlyOrEof(FRAME_HEADER_BYTES) ?: return null
        val size = (0 until 4).fold(0L) { value, index -> value or ((header[8 + index].toLong() and 0xff) shl (index * 8)) }
        require(size <= limits.frameBytes && size <= Int.MAX_VALUE) { "analysis frame exceeds worker limit" }
        return header + input.readExactly(size.toInt())
    }

    private fun write(message: AnalysisMessage) {
        val frame = AnalysisFrameCodec.encode(AnalysisMessageCodec.encode(message, AnalysisProtocolContext.unbound(limits)))
        require(frame.size <= limits.frameBytes + FRAME_HEADER_BYTES) { "analysis output frame exceeds worker limit" }
        synchronized(outputLock) {
            output.write(frame)
            output.flush()
        }
    }

    private fun bounded(
        value: String,
        maximumBytes: Int,
    ): String {
        val result = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val text = String(Character.toChars(codePoint))
            val encoded = text.encodeToByteArray().size
            if (bytes + encoded > maximumBytes) break
            result.append(text)
            bytes += encoded
            index += Character.charCount(codePoint)
        }
        return result.toString().ifEmpty { "invalid snapshot" }
    }

    private companion object {
        const val FRAME_HEADER_BYTES = 12
        const val MAXIMUM_QUEUED_REQUESTS = 2
        val UNSUPPORTED_QUERY_HANDLER =
            AnalysisQueryHandler { request, _, _ ->
                AnalysisFailure(
                    request.requestId,
                    request.query.identity,
                    AnalysisFailureKind.UnsupportedFeature,
                    "semantic queries are not implemented",
                )
            }
    }
}

private fun InputStream.readExactlyOrEof(count: Int): ByteArray? {
    val first = read()
    if (first < 0) return null
    val bytes = ByteArray(count)
    bytes[0] = first.toByte()
    var offset = 1
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        require(read >= 0) { "analysis frame is truncated" }
        if (read > 0) offset += read
    }
    return bytes
}

private fun InputStream.readExactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = read(bytes, offset, count - offset)
        require(read >= 0) { "analysis frame is truncated" }
        if (read > 0) offset += read
    }
    return bytes
}

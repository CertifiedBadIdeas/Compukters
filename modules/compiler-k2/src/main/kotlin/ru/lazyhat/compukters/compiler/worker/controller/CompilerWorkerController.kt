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
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.CompilerFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerCodec
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerFeature
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerHandshake
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessage
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessageCodec
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

enum class CompilerWorkerState { STOPPED, STARTING, IDLE, COMPILING, INVALID }

class WorkerQueueFullException : IllegalStateException("compiler worker queue is full")

class CompilerWorkerController(
    private val payload: PublishedWorkerPayload,
    private val launch: WorkerLaunch,
    private val limits: WorkerLimits,
    private val processFactory: WorkerProcessFactory,
) : AutoCloseable {
    private val lock = Any()
    private val executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "compukter-compiler-controller").apply { isDaemon = true }
        }
    private var process: WorkerProcess? = null
    private var active: Pending? = null
    private var queued: Pending? = null
    private var nextRequestId = 1uL
    private var closed = false
    private var state = CompilerWorkerState.STOPPED

    init {
        require(launch.expectedIdentity == payload.manifest.identity) { "launch and payload identities must match" }
    }

    val currentState: CompilerWorkerState
        get() = synchronized(lock) { state }

    fun compile(
        source: BinaryValue,
        path: VirtualSourcePath = VirtualSourcePath.of("project/main.kts"),
        target: TargetSettings = TargetSettings.KOTLIN_2_4_JVM_17,
    ): CompletableFuture<CompileResult> {
        val future = CompletableFuture<CompileResult>()
        var startDrain = false
        synchronized(lock) {
            if (closed) {
                future.completeExceptionally(IllegalStateException("compiler worker controller is closed"))
                return future
            }
            if (active != null && queued != null) {
                future.completeExceptionally(WorkerQueueFullException())
                return future
            }
            check(nextRequestId != 0uL) { "request ID space exhausted" }
            val requestId = RequestId.of(nextRequestId)
            nextRequestId++
            val pending = Pending(CompileRequest(requestId, path, source, target, launch.expectedIdentity, limits), future)
            if (active == null) {
                active = pending
                startDrain = true
            } else {
                queued = pending
            }
        }
        if (startDrain) executor.execute(::drain)
        return future
    }

    override fun close() {
        val pending: List<Pending>
        val child: WorkerProcess?
        synchronized(lock) {
            if (closed) return
            closed = true
            state = CompilerWorkerState.INVALID
            pending = listOfNotNull(active, queued)
            active = null
            queued = null
            child = process
            process = null
        }
        child?.terminate(0)
        pending.forEach { item ->
            item.future.complete(PlatformFailure(item.request.requestId, PlatformFailureClass.CANCELLED, "controller closed"))
        }
        executor.shutdownNow()
    }

    private fun drain() {
        while (true) {
            val pending = synchronized(lock) { active } ?: return
            val result = runRequest(pending.request)
            pending.future.complete(result)
            synchronized(lock) {
                if (active === pending) {
                    active = queued
                    queued = null
                }
                if (active == null) return
            }
        }
    }

    private fun runRequest(request: CompileRequest): CompileResult =
        try {
            val child = ensureWorker()
            setState(CompilerWorkerState.COMPILING)
            val outbound = encode(request)
            try {
                child.writeFrame(outbound)
            } catch (exception: Exception) {
                throw ControllerFault(PlatformFailureClass.WORKER_EXIT, exception.message ?: "worker input failed")
            }
            val frame =
                try {
                    child.readFrame(Long.MAX_VALUE)
                } catch (exception: Exception) {
                    throw ControllerFault(PlatformFailureClass.WORKER_EXIT, exception.message ?: "worker output failed")
                } ?: throw ControllerFault(PlatformFailureClass.WORKER_EXIT, "worker closed stdout")
            val message = decode(frame)
            val result =
                message as? CompileResult ?: throw ControllerFault(PlatformFailureClass.PROTOCOL, "unexpected message while compiling")
            if (result.requestId != request.requestId) throw ControllerFault(PlatformFailureClass.PROTOCOL, "terminal request ID mismatch")
            validateResult(result)
            setState(CompilerWorkerState.IDLE)
            result
        } catch (fault: ControllerFault) {
            invalidate()
            PlatformFailure(request.requestId, fault.failureClass, fault.message.orEmpty().take(MAX_FAILURE_DETAIL_CHARS))
        } catch (exception: Exception) {
            invalidate()
            PlatformFailure(request.requestId, PlatformFailureClass.PROTOCOL, exception.message.orEmpty().take(MAX_FAILURE_DETAIL_CHARS))
        }

    private fun ensureWorker(): WorkerProcess {
        synchronized(lock) {
            process?.takeIf(WorkerProcess::isAlive)?.let { return it }
            state = CompilerWorkerState.STARTING
        }
        val child =
            try {
                processFactory.start(payload, launch)
            } catch (exception: Exception) {
                throw ControllerFault(PlatformFailureClass.WORKER_STARTUP, exception.message ?: "worker failed to start")
            }
        synchronized(lock) { process = child }
        val frame =
            try {
                child.readFrame(Long.MAX_VALUE)
            } catch (exception: Exception) {
                throw ControllerFault(PlatformFailureClass.WORKER_STARTUP, exception.message ?: "worker handshake failed")
            } ?: throw ControllerFault(PlatformFailureClass.WORKER_STARTUP, "worker exited before handshake")
        val handshake =
            decode(frame) as? WorkerHandshake ?: throw ControllerFault(PlatformFailureClass.PROTOCOL, "expected worker handshake")
        if (handshake.identity != launch.expectedIdentity) throw ControllerFault(PlatformFailureClass.PROTOCOL, "worker identity mismatch")
        if (!handshake.features.containsAll(
                REQUIRED_FEATURES,
            )
        ) {
            throw ControllerFault(PlatformFailureClass.PROTOCOL, "worker features mismatch")
        }
        if (handshake.limits != limits) throw ControllerFault(PlatformFailureClass.PROTOCOL, "worker limits mismatch")
        setState(CompilerWorkerState.IDLE)
        return child
    }

    private fun validateResult(result: CompileResult) {
        when (result) {
            is CompileSuccess -> {
                if (result.artifact.size >
                    limits.artifactBytes
                ) {
                    throw ControllerFault(PlatformFailureClass.PROTOCOL, "artifact exceeds limit")
                }
                val actualHash = Hashing.sha256(result.artifact.toByteArray())
                if (actualHash != result.artifactHash) throw ControllerFault(PlatformFailureClass.PROTOCOL, "artifact hash mismatch")
                validateDiagnostics(result.warnings)
            }

            is CompilerFailure -> {
                validateDiagnostics(result.diagnostics)
            }

            is PlatformFailure -> {
                throw ControllerFault(result.failureClass, result.detail)
            }
        }
    }

    private fun validateDiagnostics(diagnostics: List<ru.lazyhat.compukters.compiler.worker.protocol.WorkerDiagnostic>) {
        if (diagnostics.size > limits.diagnostics) throw ControllerFault(PlatformFailureClass.PROTOCOL, "diagnostic count exceeds limit")
        val bytes =
            diagnostics.fold(0L) { total, diagnostic ->
                Math.addExact(
                    total,
                    diagnostic.message
                        .encodeToByteArray()
                        .size
                        .toLong(),
                )
            }
        if (bytes > limits.diagnosticTextBytes) throw ControllerFault(PlatformFailureClass.PROTOCOL, "diagnostic text exceeds limit")
    }

    private fun encode(message: WorkerMessage): ByteArray {
        val frame = WorkerCodec.encodeFrame(WorkerMessageCodec.encode(message))
        if (frame.size > limits.frameBytes) throw ControllerFault(PlatformFailureClass.PROTOCOL, "request frame exceeds limit")
        return frame
    }

    private fun decode(frame: ByteArray): WorkerMessage =
        try {
            WorkerMessageCodec.decode(WorkerCodec.decodeFrame(frame, limits.frameBytes))
        } catch (exception: IllegalArgumentException) {
            throw ControllerFault(PlatformFailureClass.PROTOCOL, exception.message ?: "malformed worker frame")
        }

    private fun invalidate() {
        val child =
            synchronized(lock) {
                state = CompilerWorkerState.INVALID
                process.also { process = null }
            }
        child?.terminate(0)
        synchronized(lock) {
            if (!closed) state = CompilerWorkerState.STOPPED
        }
    }

    private fun setState(value: CompilerWorkerState) {
        synchronized(lock) { state = value }
    }

    private data class Pending(
        val request: CompileRequest,
        val future: CompletableFuture<CompileResult>,
    )

    private class ControllerFault(
        val failureClass: PlatformFailureClass,
        message: String,
    ) : IllegalStateException(message)

    private object Hashing {
        fun sha256(bytes: ByteArray) =
            ru.lazyhat.compukters.compiler.worker.protocol.Hash256
                .of(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private companion object {
        const val MAX_FAILURE_DETAIL_CHARS = 1024
        val REQUIRED_FEATURES = setOf(WorkerFeature.SINGLE_SCRIPT, WorkerFeature.KOTLIN_IR)
    }
}

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

package ru.lazyhat.compukters.ide.compiler

import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.LibraryPreparationRequest
import ru.lazyhat.compukters.compiler.worker.protocol.LibraryPreparationResult
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun interface CompilerIdleTask {
    fun cancel()
}

interface CompilerIdleScheduler : AutoCloseable {
    fun schedule(
        delayNanos: Long,
        action: () -> Unit,
    ): CompilerIdleTask
}

/** Client-owned backend; IDE sessions retain their own compilation services and cache handles. */
class ClientCompilerBackendLifetime(
    private val idleDurationNanos: Long,
    private val scheduler: CompilerIdleScheduler = ExecutorCompilerIdleScheduler(),
    private val backendFactory: () -> ClientCompilerBackend,
) : AutoCloseable {
    private val lock = Any()
    private val sessions = mutableSetOf<Session>()
    private var backend: ClientCompilerBackend? = null
    private val preparations = mutableMapOf<LibraryPreparationRequest, CompletableFuture<LibraryPreparationResult>>()
    private var idleTask: CompilerIdleTask? = null
    private var generation = 0L
    private var closed = false

    init {
        require(idleDurationNanos >= 0) { "compiler idle duration must not be negative" }
    }

    fun openSession(): ClientCompilerBackend =
        synchronized(lock) {
            check(!closed) { "compiler backend lifetime is closed" }
            invalidateIdleTask()
            Session().also(sessions::add)
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            invalidateIdleTask()
            sessions.forEach { it.sessionClosed = true }
            sessions.clear()
            try {
                closeBackend()
            } finally {
                scheduler.close()
            }
        }
    }

    private fun invalidateIdleTask() {
        generation++
        idleTask?.cancel()
        idleTask = null
    }

    private fun closeBackend() {
        // Serialize shutdown with acquisition: a replacement uses the same worker temporary root.
        try {
            backend?.close()
        } finally {
            backend = null
            preparations.clear()
        }
    }

    private fun expire(expectedGeneration: Long) {
        synchronized(lock) {
            if (closed || sessions.isNotEmpty() || expectedGeneration != generation) return
            idleTask = null
            closeBackend()
        }
    }

    private inner class Session : ClientCompilerBackend {
        var sessionClosed = false
        private val requests = mutableSetOf<CompletableFuture<CompileResult>>()

        override fun prepareLibraries(request: LibraryPreparationRequest): CompletableFuture<LibraryPreparationResult> =
            synchronized(lock) {
                check(!closed && !sessionClosed) { "compiler backend session is closed" }
                val key =
                    request.copy(
                        requestId =
                            RequestId.of(1uL),
                    )
                val shared =
                    preparations[key] ?: run {
                        val delegate = backend ?: backendFactory().also { backend = it }
                        delegate.prepareLibraries(request).also { future ->
                            preparations[key] = future
                            future.whenComplete { _, _ ->
                                synchronized(lock) {
                                    if (preparations[key] === future) preparations.remove(key)
                                }
                            }
                        }
                    }
                // A screen may detach or cancel its observer without cancelling client-owned preparation.
                shared.thenApply { it }
            }

        override fun compile(request: CompileRequest): CompletableFuture<CompileResult> =
            synchronized(lock) {
                check(!closed && !sessionClosed) { "compiler backend session is closed" }
                val delegate = backend ?: backendFactory().also { backend = it }
                delegate.compile(request).also { future ->
                    requests += future
                    future.whenComplete { _, _ -> synchronized(lock) { requests -= future } }
                }
            }

        override fun cancel(future: CompletableFuture<CompileResult>): Boolean =
            synchronized(lock) {
                if (closed || sessionClosed || future !in requests) return false
                backend?.cancel(future) == true
            }

        override fun close() {
            synchronized(lock) {
                if (closed || sessionClosed) return
                sessionClosed = true
                try {
                    requests.toList().forEach { backend?.cancel(it) }
                } finally {
                    requests.clear()
                    sessions.remove(this)
                    if (sessions.isEmpty() && backend != null) {
                        val expectedGeneration = ++generation
                        idleTask = scheduler.schedule(idleDurationNanos) { expire(expectedGeneration) }
                    }
                }
            }
        }
    }
}

private class ExecutorCompilerIdleScheduler : CompilerIdleScheduler {
    private val executor =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "compukters-client-compiler-idle").apply { isDaemon = true }
        }

    override fun schedule(
        delayNanos: Long,
        action: () -> Unit,
    ): CompilerIdleTask {
        val future = executor.schedule(action, delayNanos, TimeUnit.NANOSECONDS)
        return CompilerIdleTask { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

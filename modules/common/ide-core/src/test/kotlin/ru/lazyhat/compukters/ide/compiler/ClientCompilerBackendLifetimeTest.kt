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

import ru.lazyhat.compukters.compiler.project.ProjectSource
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompilationMetrics
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileResult
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.LibrariesPrepared
import ru.lazyhat.compukters.compiler.worker.protocol.LibraryPreparationRequest
import ru.lazyhat.compukters.compiler.worker.protocol.LibraryPreparationResult
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailure
import ru.lazyhat.compukters.compiler.worker.protocol.PlatformFailureClass
import ru.lazyhat.compukters.compiler.worker.protocol.RequestId
import ru.lazyhat.compukters.compiler.worker.protocol.TargetSettings
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientCompilerBackendLifetimeTest {
    @Test
    fun `preparation is shared across sessions and survives observer cancellation and reopen`() {
        val timer = ManualCompilerIdleScheduler()
        val backend = RecordingCompilerBackend()
        val owner = ClientCompilerBackendLifetime(50, timer) { backend }
        try {
            val request = LibraryPreparationRequest(RequestId.of(1uL), request().expectedIdentity, WorkerLimits())
            val first = owner.openSession()
            val observer = first.prepareLibraries(request)
            assertTrue(observer.cancel(false))
            first.close()
            assertTrue(backend.cancelled.isEmpty())
            assertFalse(backend.preparationFutures.single().isDone)
            val reopened = owner.openSession()
            val next = reopened.prepareLibraries(request.copy(requestId = RequestId.of(2uL)))
            assertEquals(1, backend.preparationRequests.size)
            backend.preparationFutures.single().complete(LibrariesPrepared(RequestId.of(1uL), CompilationMetrics(1uL, 2uL, 3uL)))
            assertTrue(next.get(5, TimeUnit.SECONDS) is LibrariesPrepared)
            reopened.prepareLibraries(request)
            assertEquals(2, backend.preparationRequests.size, "completed acknowledgements must not hide worker restart")
            reopened.close()
            timer.tasks.last().action()
            assertEquals(1, backend.closes)
            assertTrue(backend.preparationFutures.last().isDone)
        } finally {
            owner.close()
        }
    }

    @Test
    fun `closed and reopened sessions retain a lazy backend until idle expiry`() {
        val timer = ManualCompilerIdleScheduler()
        val created = mutableListOf<RecordingCompilerBackend>()
        val owner = ClientCompilerBackendLifetime(50, timer) { RecordingCompilerBackend().also(created::add) }
        owner.openSession().close()
        assertEquals(0, created.size)
        assertTrue(timer.tasks.isEmpty())
        val first = owner.openSession()
        first.compile(request()).complete(result())
        first.close()
        first.close()
        assertEquals(1, timer.tasks.size)
        val oldTimer = timer.tasks.single()
        val reopened = owner.openSession()
        oldTimer.action() // A cancelled timer may already have started on another thread.
        reopened.compile(request()).complete(result())
        assertEquals(1, created.size)
        assertEquals(0, created.single().closes)
        reopened.close()
        timer.tasks.last().action()
        assertEquals(1, created.single().closes)
        owner.openSession().use { it.compile(request()).complete(result()) }
        assertEquals(2, created.size)
        owner.close()
        assertEquals(1, created.last().closes)
        assertTrue(timer.closed)
        assertFailsWith<IllegalStateException> { owner.openSession() }
    }

    @Test
    fun `session close cancels only owned work and rejects late submissions`() {
        val timer = ManualCompilerIdleScheduler()
        val backend = RecordingCompilerBackend()
        val owner = ClientCompilerBackendLifetime(50, timer) { backend }
        val first = owner.openSession()
        val second = owner.openSession()
        val firstFuture = first.compile(request())
        val secondFuture = second.compile(request())
        assertFalse(first.cancel(secondFuture))
        first.close()
        assertEquals(listOf(firstFuture), backend.cancelled)
        assertTrue(firstFuture.isDone)
        assertFalse(secondFuture.isDone)
        assertFalse(first.cancel(secondFuture))
        assertFailsWith<IllegalStateException> { first.compile(request()) }
        assertEquals(0, backend.closes)
        assertTrue(timer.tasks.isEmpty())
        second.close()
        assertTrue(secondFuture.isDone)
        assertEquals(1, timer.tasks.size)
        owner.close()
        assertEquals(1, backend.closes)
    }

    @Test
    fun `shutdown closes active backend and stale idle callbacks cannot revive it`() {
        val timer = ManualCompilerIdleScheduler()
        val backend = RecordingCompilerBackend()
        val owner = ClientCompilerBackendLifetime(0, timer) { backend }
        val session = owner.openSession()
        session.compile(request()).complete(result())
        session.close()
        val task = timer.tasks.single()
        val reopened = owner.openSession()
        val pending = reopened.compile(request())
        owner.close()
        owner.close()
        reopened.close()
        task.action()
        assertTrue(pending.isDone)
        assertEquals(1, backend.closes)
        assertFailsWith<IllegalStateException> { reopened.compile(request()) }
    }

    @Test
    fun `failed backend creation can recover in the same session`() {
        val timer = ManualCompilerIdleScheduler()
        var attempts = 0
        val backend = RecordingCompilerBackend()
        val owner =
            ClientCompilerBackendLifetime(50, timer) {
                if (++attempts == 1) error("fixture unavailable")
                backend
            }
        owner.openSession().use {
            assertFailsWith<IllegalStateException> { it.compile(request()) }
            it.compile(request()).complete(result())
        }
        assertEquals(2, attempts)
        owner.close()
    }

    @Test
    fun `expiry finishes backend shutdown before a replacement can be acquired`() {
        val timer = ManualCompilerIdleScheduler()
        val closing = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val reopened = CountDownLatch(1)
        var created = 0
        val owner =
            ClientCompilerBackendLifetime(0, timer) {
                created++
                RecordingCompilerBackend(onClose = {
                    closing.countDown()
                    assertTrue(allowClose.await(5, TimeUnit.SECONDS))
                })
            }
        owner.openSession().use { it.compile(request()).complete(result()) }
        val expiry = thread { timer.tasks.single().action() }
        assertTrue(closing.await(5, TimeUnit.SECONDS))
        val acquisition =
            thread {
                owner.openSession().use { it.compile(request()).complete(result()) }
                reopened.countDown()
            }
        try {
            assertFalse(reopened.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1, created)
        } finally {
            allowClose.countDown()
            expiry.join(5000)
            acquisition.join(5000)
            owner.close()
        }
        assertEquals(2, created)
        assertEquals(0, reopened.count)
    }

    private fun request() =
        CompileRequest(
            RequestId.of(1uL),
            listOf(ProjectSource(VirtualSourcePath.kotlin("src/main.kt"), BinaryValue.of("fun main() {}".encodeToByteArray()))),
            TargetSettings.KOTLIN_2_4_JVM_17,
            WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero()),
            WorkerLimits(),
        )

    private fun result() = PlatformFailure(RequestId.of(1uL), PlatformFailureClass.CANCELLED, "fixture cancelled")
}

private class ManualCompilerIdleScheduler : CompilerIdleScheduler {
    class Task(
        val delay: Long,
        val action: () -> Unit,
    ) : CompilerIdleTask {
        var cancelled = false

        override fun cancel() {
            cancelled = true
        }
    }

    val tasks = mutableListOf<Task>()
    var closed = false

    override fun schedule(
        delayNanos: Long,
        action: () -> Unit,
    ): CompilerIdleTask = Task(delayNanos, action).also(tasks::add)

    override fun close() {
        closed = true
    }
}

private class RecordingCompilerBackend(
    private val onClose: () -> Unit = {},
) : ClientCompilerBackend {
    val preparationRequests = mutableListOf<LibraryPreparationRequest>()
    val preparationFutures = mutableListOf<CompletableFuture<LibraryPreparationResult>>()
    val pending = mutableListOf<CompletableFuture<CompileResult>>()
    val cancelled = mutableListOf<CompletableFuture<CompileResult>>()
    var closes = 0

    override fun prepareLibraries(request: LibraryPreparationRequest): CompletableFuture<LibraryPreparationResult> =
        CompletableFuture<LibraryPreparationResult>().also {
            preparationRequests += request
            preparationFutures += it
        }

    override fun compile(request: CompileRequest): CompletableFuture<CompileResult> = CompletableFuture<CompileResult>().also(pending::add)

    override fun cancel(future: CompletableFuture<CompileResult>): Boolean {
        cancelled += future
        return future.complete(PlatformFailure(RequestId.of(1uL), PlatformFailureClass.CANCELLED, "cancelled"))
    }

    override fun close() {
        closes++
        onClose()
        preparationFutures.forEach { it.complete(PlatformFailure(RequestId.of(1uL), PlatformFailureClass.CANCELLED, "closed")) }
        pending.forEach { it.complete(PlatformFailure(RequestId.of(1uL), PlatformFailureClass.CANCELLED, "closed")) }
    }
}

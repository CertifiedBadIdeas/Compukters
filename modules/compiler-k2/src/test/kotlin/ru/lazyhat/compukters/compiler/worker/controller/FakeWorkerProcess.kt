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

import ru.lazyhat.compukters.compiler.worker.protocol.WorkerCodec
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessage
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessageCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class FakeWorkerProcess : WorkerProcess {
    private val reads = LinkedBlockingQueue<Read>()
    private val writes = LinkedBlockingQueue<WorkerMessage>()
    val operations = mutableListOf<String>()
    val readDeadlines = mutableListOf<Long>()
    var terminationCount = 0
    val terminationGraces = mutableListOf<Long>()
    var stderr = ByteArray(0)
    override var exitCode: Int? = null
    override var isAlive = true

    fun enqueue(message: WorkerMessage) {
        reads.put(Read.Frame(encode(message)))
    }

    fun enqueueEof() {
        reads.put(Read.Eof)
    }

    fun enqueueTimeout() {
        reads.put(Read.Timeout)
    }

    fun awaitWrite(): WorkerMessage = checkNotNull(writes.poll(5, TimeUnit.SECONDS)) { "worker request was not written" }

    override fun writeFrame(frame: ByteArray) {
        val message = decode(frame)
        synchronized(operations) { operations += "write:${message.type}" }
        writes.put(message)
    }

    override fun readFrame(deadlineNanos: Long): ByteArray? {
        readDeadlines += deadlineNanos
        return when (val read = reads.take()) {
            is Read.Frame -> {
                val message = decode(read.bytes)
                synchronized(operations) { operations += "read:${message.type}" }
                read.bytes
            }

            Read.Eof -> {
                null
            }

            Read.Timeout -> {
                throw WorkerDeadlineExceededException()
            }
        }
    }

    override fun stderrSnapshot(): ByteArray = stderr.copyOf()

    override fun terminate(graceMillis: Long) {
        terminationCount++
        terminationGraces += graceMillis
        isAlive = false
        reads.offer(Read.Eof)
    }

    override fun close() {
        terminate(0)
    }

    private sealed interface Read {
        data class Frame(
            val bytes: ByteArray,
        ) : Read

        data object Eof : Read

        data object Timeout : Read
    }
}

internal class FakeWorkerProcessFactory(
    processes: List<FakeWorkerProcess>,
) : WorkerProcessFactory {
    private val available = ArrayDeque(processes)
    val starts = mutableListOf<WorkerLaunch>()

    override fun start(
        payload: PublishedWorkerPayload,
        launch: WorkerLaunch,
    ): WorkerProcess {
        starts += launch
        return available.removeFirst()
    }
}

private fun encode(message: WorkerMessage): ByteArray = WorkerCodec.encodeFrame(WorkerMessageCodec.encode(message))

private fun decode(frame: ByteArray): WorkerMessage = WorkerMessageCodec.decode(WorkerCodec.decodeFrame(frame, Int.MAX_VALUE))

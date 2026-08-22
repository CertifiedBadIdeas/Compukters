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

import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.CompilationMetrics
import ru.lazyhat.compukters.compiler.worker.protocol.CompileRequest
import ru.lazyhat.compukters.compiler.worker.protocol.CompileSuccess
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerCodec
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerFeature
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerHandshake
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerIdentity
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerLimits
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessage
import ru.lazyhat.compukters.compiler.worker.protocol.WorkerMessageCodec
import java.security.MessageDigest
import kotlin.system.exitProcess

fun main() {
    write(WorkerHandshake(hostileIdentity(), setOf(WorkerFeature.PROJECT_SNAPSHOT, WorkerFeature.KOTLIN_IR), WorkerLimits()))
    val request = WorkerMessageCodec.decode(WorkerCodec.decodeFrame(readFrame(), WorkerLimits().frameBytes)) as CompileRequest
    when (
        request.sources
            .single()
            .content
            .toByteArray()
            .decodeToString()
    ) {
        "SLEEP" -> {
            Thread.sleep(10_000)
        }

        "TRUNCATED" -> {
            System.out.write(byteArrayOf(0x43, 0x50, 0x4b))
        }

        "OVERSIZED" -> {
            System.out.write(byteArrayOf(0x43, 0x50, 0x4b, 0x57, 2, 0, 3, 0, -1, -1, -1, 127))
        }

        "STDERR" -> {
            System.err.write(ByteArray(128 * 1024) { 'x'.code.toByte() })
            exitProcess(7)
        }

        "OOM" -> {
            val retained = mutableListOf<ByteArray>()
            while (true) retained += ByteArray(1024 * 1024)
        }

        "WRONG_ID" -> {
            write(success(request.requestId.value + 1uL))
        }

        "DUPLICATE" -> {
            val result = success(request.requestId.value)
            write(result)
            write(result)
        }

        else -> {
            write(success(request.requestId.value))
        }
    }
    System.out.flush()
}

private fun success(id: ULong): CompileSuccess {
    val artifact = BinaryValue.of(byteArrayOf(1, 2, 3))
    val hash = Hash256.of(MessageDigest.getInstance("SHA-256").digest(artifact.toByteArray()))
    return CompileSuccess(
        ru.lazyhat.compukters.compiler.worker.protocol.RequestId
            .of(id),
        artifact,
        hash,
        emptyList(),
        CompilationMetrics(1uL, 1uL, 1uL),
    )
}

private fun write(message: WorkerMessage) {
    System.out.write(WorkerCodec.encodeFrame(WorkerMessageCodec.encode(message)))
    System.out.flush()
}

private fun readFrame(): ByteArray {
    val header = System.`in`.readNBytes(12)
    val size = (0 until 4).fold(0) { value, index -> value or ((header[8 + index].toInt() and 0xff) shl (index * 8)) }
    return header + System.`in`.readNBytes(size)
}

internal fun hostileIdentity() = WorkerIdentity("2.4.10", "2.4", 1u, 1u, Hash256.zero(), Hash256.zero())

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

package ru.lazyhat.compukters.compiler.worker.protocol

import java.io.ByteArrayOutputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val MAX_WIRE_DIAGNOSTICS = 4096

object WorkerMessageCodec {
    fun encode(message: WorkerMessage): WorkerFrame {
        val sink = MessageSink()
        when (message) {
            is WorkerHandshake -> {
                sink.identity(message.identity)
                sink.u64(message.features.fold(0uL) { bits, feature -> bits or (1uL shl feature.ordinal) })
                sink.limits(message.limits)
            }

            is CompileRequest -> {
                sink.u64(message.requestId.value)
                sink.string(message.path.value)
                sink.bytes(message.source)
                sink.u16(message.target.ordinal)
                sink.identity(message.expectedIdentity)
                sink.limits(message.limits)
            }

            is CompileSuccess -> {
                sink.u64(message.requestId.value)
                sink.bytes(message.artifact)
                sink.hash(message.artifactHash)
                sink.diagnostics(message.warnings)
                sink.metrics(message.metrics)
            }

            is CompilerFailure -> {
                sink.u64(message.requestId.value)
                sink.diagnostics(message.diagnostics)
                sink.metrics(message.metrics)
            }

            is PlatformFailure -> {
                sink.u64(message.requestId.value)
                sink.u16(message.failureClass.ordinal)
                sink.string(message.detail)
            }
        }
        return WorkerFrame(message.type, sink.result())
    }

    fun decode(frame: WorkerFrame): WorkerMessage {
        val source = MessageSource(frame.payload)
        val message =
            when (frame.type) {
                WorkerMessageType.HANDSHAKE -> {
                    WorkerHandshake(source.identity(), source.features(), source.limits())
                }

                WorkerMessageType.COMPILE_REQUEST -> {
                    source.compileRequest()
                }

                WorkerMessageType.COMPILE_SUCCESS -> {
                    CompileSuccess(RequestId.of(source.u64()), source.bytes(), source.hash(), source.diagnostics(), source.metrics())
                }

                WorkerMessageType.COMPILER_FAILURE -> {
                    CompilerFailure(RequestId.of(source.u64()), source.diagnostics(), source.metrics())
                }

                WorkerMessageType.PLATFORM_FAILURE -> {
                    PlatformFailure(RequestId.of(source.u64()), source.enumValue<PlatformFailureClass>(), source.string())
                }
            }
        source.requireEnd()
        return message
    }
}

private class MessageSink {
    private val output = ByteArrayOutputStream()

    fun result(): ByteArray = output.toByteArray()

    fun u16(value: Int) {
        repeat(2) { output.write(value ushr (it * 8)) }
    }

    fun u32(value: Int) {
        require(value >= 0)
        u32(value.toUInt())
    }

    fun u32(value: UInt) {
        repeat(4) { output.write((value shr (it * 8)).toInt()) }
    }

    fun u64(value: ULong) {
        repeat(8) { output.write((value shr (it * 8)).toInt()) }
    }

    fun string(value: String) {
        val encoded =
            try {
                StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value))
            } catch (exception: CharacterCodingException) {
                throw IllegalArgumentException("protocol text must be strict UTF-8", exception)
            }
        val result = ByteArray(encoded.remaining())
        encoded.get(result)
        bytes(BinaryValue.of(result))
    }

    fun bytes(value: BinaryValue) {
        u32(value.size)
        output.write(value.toByteArray())
    }

    fun hash(value: Hash256) {
        output.write(value.toByteArray())
    }

    fun identity(value: WorkerIdentity) {
        string(value.compilerVersion)
        string(value.languageVersion)
        u32(value.codegenAbi)
        u32(value.artifactWriterVersion)
        hash(value.payloadHash)
        hash(value.standardLibraryAbi)
    }

    fun limits(value: WorkerLimits) {
        u32(value.sourceBytes)
        u32(value.frameBytes)
        u32(value.artifactBytes)
        u32(value.diagnostics)
        u32(value.diagnosticTextBytes)
        u32(value.stderrBytes)
        u64(value.temporaryBytes.toULong())
        u32(value.temporaryFiles)
    }

    fun diagnostics(values: List<WorkerDiagnostic>) {
        require(values.size <= MAX_WIRE_DIAGNOSTICS) { "diagnostic count exceeds wire limit" }
        u32(values.size)
        values.forEach(::diagnostic)
    }

    fun diagnostic(value: WorkerDiagnostic) {
        u16(value.severity.ordinal)
        u16(value.category.ordinal)
        nullableString(value.code)
        string(value.message)
        nullableString(value.path?.value)
        nullableUInt(value.startUtf16)
        nullableUInt(value.endUtf16)
    }

    fun nullableString(value: String?) {
        output.write(if (value == null) 0 else 1)
        if (value != null) string(value)
    }

    fun nullableUInt(value: UInt?) {
        output.write(if (value == null) 0 else 1)
        if (value != null) u32(value)
    }

    fun metrics(value: CompilationMetrics) {
        u64(value.wallNanos)
        u64(value.heapBytes)
        u64(value.metaspaceBytes)
    }
}

private class MessageSource(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun requireEnd() {
        if (offset != bytes.size) fail(WorkerProtocolError.TRAILING_MESSAGE_BYTES, "message contains trailing bytes")
    }

    fun u8(): Int {
        requireBytes(1)
        return bytes[offset++].toInt() and 0xff
    }

    fun u16(): Int = read(2).toInt()

    fun u32(): Int {
        val value = u32Bits()
        if (value >
            Int.MAX_VALUE.toUInt()
        ) {
            fail(WorkerProtocolError.FRAME_TOO_LARGE, "length exceeds JVM range")
        }
        return value.toInt()
    }

    fun u32Bits(): UInt = read(4).toUInt()

    fun u64(): ULong {
        requireBytes(8)
        var result = 0uL
        repeat(8) {
            result =
                result or ((bytes[offset++].toULong() and 0xffu) shl (it * 8))
        }
        return result
    }

    fun bytes(): BinaryValue {
        val size = u32()
        requireBytes(size)
        return BinaryValue.of(bytes.copyOfRange(offset, offset + size)).also {
            offset +=
                size
        }
    }

    fun string(): String =
        try {
            bytes().decodeUtf8()
        } catch (_: CharacterCodingException) {
            fail(WorkerProtocolError.INVALID_UTF8, "invalid UTF-8")
        }

    fun hash(): Hash256 {
        requireBytes(32)
        return Hash256.of(bytes.copyOfRange(offset, offset + 32)).also { offset += 32 }
    }

    fun identity() = WorkerIdentity(string(), string(), u32Bits(), u32Bits(), hash(), hash())

    fun limits() = WorkerLimits(u32(), u32(), u32(), u32(), u32(), u32(), u64().toLongChecked(), u32())

    fun features(): Set<WorkerFeature> {
        val bits = u64()
        val known = WorkerFeature.entries.fold(0uL) { value, feature -> value or (1uL shl feature.ordinal) }
        if (bits and known.inv() != 0uL) fail(WorkerProtocolError.INVALID_MESSAGE_VALUE, "unknown worker feature")
        return WorkerFeature.entries.filterTo(linkedSetOf()) { bits and (1uL shl it.ordinal) != 0uL }
    }

    fun compileRequest(): CompileRequest {
        val requestId = RequestId.of(u64())
        val path =
            try {
                VirtualSourcePath.of(string())
            } catch (exception: IllegalArgumentException) {
                fail(WorkerProtocolError.INVALID_PATH, exception.message ?: "invalid virtual path")
            }
        val sourceBytes = bytes()
        try {
            sourceBytes.decodeUtf8()
        } catch (_: CharacterCodingException) {
            fail(WorkerProtocolError.INVALID_UTF8, "source is not strict UTF-8")
        }
        val target = enumValue<TargetSettings>()
        val identity = identity()
        val limits = limits()
        return try {
            CompileRequest(requestId, path, sourceBytes, target, identity, limits)
        } catch (exception: IllegalArgumentException) {
            fail(WorkerProtocolError.INVALID_MESSAGE_VALUE, exception.message ?: "invalid compile request")
        }
    }

    fun diagnostics(): List<WorkerDiagnostic> {
        val count = u32()
        if (count > MAX_WIRE_DIAGNOSTICS) {
            fail(WorkerProtocolError.COUNT_LIMIT, "diagnostic count exceeds wire limit")
        }
        return List(count) { diagnostic() }
    }

    fun diagnostic() =
        WorkerDiagnostic(
            enumValue(),
            enumValue(),
            nullableString(),
            string(),
            nullableString()?.let(VirtualSourcePath::of),
            nullableUInt(),
            nullableUInt(),
        )

    fun nullableString(): String? =
        when (u8()) {
            0 -> null
            1 -> string()
            else -> fail(WorkerProtocolError.INVALID_MESSAGE_VALUE, "non-canonical optional value")
        }

    fun nullableUInt(): UInt? =
        when (u8()) {
            0 -> null
            1 -> u32Bits()
            else -> fail(WorkerProtocolError.INVALID_MESSAGE_VALUE, "non-canonical optional value")
        }

    fun metrics() = CompilationMetrics(u64(), u64(), u64())

    inline fun <reified T : Enum<T>> enumValue(): T {
        val index = u16()
        return enumValues<T>().getOrNull(index)
            ?: fail(WorkerProtocolError.UNKNOWN_ENUM_VALUE, "unknown enum value")
    }

    private fun read(count: Int): Long {
        requireBytes(count)
        var result = 0L
        repeat(count) {
            result =
                result or ((bytes[offset++].toLong() and 0xff) shl (it * 8))
        }
        return result
    }

    private fun requireBytes(count: Int) {
        if (count < 0 ||
            offset > bytes.size - count
        ) {
            fail(WorkerProtocolError.TRUNCATED_MESSAGE, "message is truncated")
        }
    }

    fun fail(
        error: WorkerProtocolError,
        message: String,
    ): Nothing = throw WorkerProtocolException(error, message)
}

private fun ULong.toLongChecked(): Long {
    if (this > Long.MAX_VALUE.toULong()) throw WorkerProtocolException(WorkerProtocolError.FRAME_TOO_LARGE, "value exceeds JVM range")
    return toLong()
}

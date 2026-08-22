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

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class BinaryValue private constructor(
    bytes: ByteArray,
) {
    private val bytes = bytes.copyOf()
    val size: Int get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    internal fun decodeUtf8(): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    override fun equals(other: Any?): Boolean = other is BinaryValue && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        fun of(bytes: ByteArray): BinaryValue = BinaryValue(bytes)
    }
}

class Hash256 private constructor(
    private val value: BinaryValue,
) {
    fun toByteArray(): ByteArray = value.toByteArray()

    fun hex(): String = toByteArray().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    override fun equals(other: Any?): Boolean = other is Hash256 && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun of(bytes: ByteArray): Hash256 {
            require(bytes.size == 32) { "SHA-256 value must contain 32 bytes" }
            return Hash256(BinaryValue.of(bytes))
        }

        fun zero(): Hash256 = of(ByteArray(32))
    }
}

@JvmInline
value class RequestId private constructor(
    val value: ULong,
) {
    companion object {
        fun of(value: ULong): RequestId {
            require(value != 0uL) { "request ID zero is reserved" }
            return RequestId(value)
        }
    }
}

data class WorkerLimits(
    val sourceBytes: Int = 256 * 1024,
    val frameBytes: Int = 20 * 1024 * 1024,
    val artifactBytes: Int = 16 * 1024 * 1024,
    val diagnostics: Int = 64,
    val diagnosticTextBytes: Int = 64 * 1024,
    val stderrBytes: Int = 64 * 1024,
    val temporaryBytes: Long = 64L * 1024 * 1024,
    val temporaryFiles: Int = 128,
) {
    init {
        require(
            sourceBytes >= 0 && frameBytes >= 0 && artifactBytes >= 0 && diagnostics >= 0 &&
                diagnosticTextBytes >= 0 && stderrBytes >= 0 && temporaryBytes >= 0 && temporaryFiles >= 0,
        ) { "worker limits must be non-negative" }
    }
}

data class WorkerIdentity(
    val compilerVersion: String,
    val languageVersion: String,
    val codegenAbi: UInt,
    val artifactWriterVersion: UInt,
    val payloadHash: Hash256,
    val standardLibraryAbi: Hash256,
)

enum class WorkerFeature { SINGLE_SCRIPT, KOTLIN_IR }

enum class TargetSettings { KOTLIN_2_4_JVM_17 }

enum class DiagnosticSeverity { INFO, WARNING, ERROR }

enum class DiagnosticCategory { SYNTAX, TYPE, TARGET, INTERNAL }

enum class PlatformFailureClass {
    WORKER_STARTUP,
    PROTOCOL,
    TIMEOUT,
    MEMORY_LIMIT,
    CANCELLED,
    INTERNAL_COMPILER,
    OUTPUT_LIMIT,
    WORKER_EXIT,
}

data class WorkerDiagnostic(
    val severity: DiagnosticSeverity,
    val category: DiagnosticCategory,
    val code: String?,
    val message: String,
    val path: VirtualSourcePath?,
    val startUtf16: UInt?,
    val endUtf16: UInt?,
)

data class CompilationMetrics(
    val wallNanos: ULong,
    val heapBytes: ULong,
    val metaspaceBytes: ULong,
)

sealed interface WorkerMessage {
    val type: WorkerMessageType
}

data class WorkerHandshake(
    val identity: WorkerIdentity,
    val features: Set<WorkerFeature>,
    val limits: WorkerLimits,
) : WorkerMessage {
    override val type = WorkerMessageType.HANDSHAKE
}

sealed interface CompileResult : WorkerMessage {
    val requestId: RequestId
}

data class CompileRequest(
    val requestId: RequestId,
    val path: VirtualSourcePath,
    val source: BinaryValue,
    val target: TargetSettings,
    val expectedIdentity: WorkerIdentity,
    val limits: WorkerLimits,
) : WorkerMessage {
    override val type = WorkerMessageType.COMPILE_REQUEST

    init {
        try {
            source.decodeUtf8()
        } catch (exception: Exception) {
            throw IllegalArgumentException("source must be strict UTF-8", exception)
        }
        require(source.size <= limits.sourceBytes) { "source exceeds request limit" }
    }
}

data class CompileSuccess(
    override val requestId: RequestId,
    val artifact: BinaryValue,
    val artifactHash: Hash256,
    val warnings: List<WorkerDiagnostic>,
    val metrics: CompilationMetrics,
) : CompileResult {
    override val type = WorkerMessageType.COMPILE_SUCCESS
}

data class CompilerFailure(
    override val requestId: RequestId,
    val diagnostics: List<WorkerDiagnostic>,
    val metrics: CompilationMetrics,
) : CompileResult {
    override val type = WorkerMessageType.COMPILER_FAILURE
}

data class PlatformFailure(
    override val requestId: RequestId,
    val failureClass: PlatformFailureClass,
    val detail: String,
) : CompileResult {
    override val type = WorkerMessageType.PLATFORM_FAILURE
}

@JvmInline
value class VirtualSourcePath private constructor(
    val value: String,
) {
    companion object {
        fun of(value: String): VirtualSourcePath {
            require(value.isNotEmpty()) { "virtual source path must not be empty" }
            require(!value.startsWith('/')) { "virtual source path must be relative" }
            require('\\' !in value && '\u0000' !in value) { "virtual source path contains a forbidden character" }
            require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "virtual source path contains a non-canonical segment"
            }
            return VirtualSourcePath(value)
        }
    }
}

enum class WorkerMessageType(
    internal val wireValue: Int,
) {
    HANDSHAKE(1),
    COMPILE_REQUEST(2),
    COMPILE_SUCCESS(3),
    COMPILER_FAILURE(4),
    PLATFORM_FAILURE(5),
    ;

    companion object {
        internal fun fromWire(value: Int): WorkerMessageType? = entries.firstOrNull { it.wireValue == value }
    }
}

class WorkerFrame(
    val type: WorkerMessageType,
    payload: ByteArray,
) {
    val payload: ByteArray = payload.copyOf()

    override fun equals(other: Any?): Boolean = other is WorkerFrame && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

enum class WorkerProtocolError {
    TRUNCATED_FRAME,
    FRAME_TOO_LARGE,
    TRAILING_BYTES,
    BAD_MAGIC,
    WRONG_VERSION,
    UNKNOWN_MESSAGE_TYPE,
    INVALID_UTF8,
    INVALID_PATH,
    UNKNOWN_ENUM_VALUE,
    INVALID_MESSAGE_VALUE,
    TRUNCATED_MESSAGE,
    TRAILING_MESSAGE_BYTES,
    COUNT_LIMIT,
}

class WorkerProtocolException(
    val error: WorkerProtocolError,
    message: String,
) : IllegalArgumentException(message)

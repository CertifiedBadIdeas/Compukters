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
 */

package ru.lazyhat.compukters.impl.ide.target

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import ru.lazyhat.compukters.compiler.worker.protocol.BinaryValue
import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.ide.client.target.IdeDeploymentPath
import ru.lazyhat.compukters.ide.client.target.IdeExecutableRevision
import ru.lazyhat.compukters.ide.client.target.IdeTargetId
import ru.lazyhat.compukters.ide.client.target.IdeTargetProfileId

internal data class IdeTargetReference(
    val id: IdeTargetId,
    val profile: IdeTargetProfileId,
)

internal class IdeCanonicalLine private constructor(
    value: CharArray,
) {
    private val value = value.copyOf()

    fun chars(): CharArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is IdeCanonicalLine && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    companion object {
        fun of(value: CharArray): IdeCanonicalLine {
            require(value.size <= IdeTargetWireProtocol.MAXIMUM_CANONICAL_LINE_CODE_UNITS) { "canonical line is too long" }
            return IdeCanonicalLine(value)
        }
    }
}

internal sealed interface IdeTargetRequest {
    data class Attach(
        val claim: BinaryValue,
    ) : IdeTargetRequest

    data class BeginUpload(
        val target: IdeTargetReference,
        val artifactHash: Hash256,
        val bytes: Int,
    ) : IdeTargetRequest {
        init {
            require(bytes > 0) { "artifact size must be positive" }
        }
    }

    data class UploadChunk(
        val target: IdeTargetReference,
        val offset: Int,
        val bytes: BinaryValue,
    ) : IdeTargetRequest {
        init {
            require(offset >= 0) { "artifact chunk offset must not be negative" }
        }
    }

    data class Verify(
        val target: IdeTargetReference,
    ) : IdeTargetRequest

    data class ExecutableRevision(
        val target: IdeTargetReference,
        val path: IdeDeploymentPath,
    ) : IdeTargetRequest

    data class Deploy(
        val target: IdeTargetReference,
        val ticket: BinaryValue,
        val artifactHash: Hash256,
        val artifactBytes: Int,
        val path: IdeDeploymentPath,
        val expected: IdeExecutableRevision,
    ) : IdeTargetRequest {
        init {
            require(artifactBytes > 0) { "ticket artifact size must be positive" }
        }
    }

    data class SubmitCanonicalLine(
        val target: IdeTargetReference,
        val line: IdeCanonicalLine,
    ) : IdeTargetRequest

    data class Heartbeat(
        val target: IdeTargetReference,
    ) : IdeTargetRequest

    data class Detach(
        val target: IdeTargetReference,
    ) : IdeTargetRequest
}

internal data class IdeTargetRequestPayload(
    val requestId: Long,
    val request: IdeTargetRequest,
) : CustomPacketPayload {
    init {
        require(requestId > 0) { "request ID zero and negative IDs are reserved" }
    }

    override fun type(): CustomPacketPayload.Type<IdeTargetRequestPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<IdeTargetRequestPayload>(Identifier.fromNamespaceAndPath(MOD_ID, "ide_target_request"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, IdeTargetRequestPayload> =
            StreamCodec.of(
                StreamEncoder { buffer, payload -> IdeTargetWireProtocol.writeRequest(buffer, payload) },
                StreamDecoder { buffer -> IdeTargetWireProtocol.readRequest(buffer) },
            )
    }
}

internal object IdeTargetWireProtocol {
    const val MAXIMUM_CHUNK_BYTES = 32 * 1024
    const val MAXIMUM_CANONICAL_LINE_CODE_UNITS = 4_096
    private const val MAXIMUM_CLAIM_BYTES = 256
    private const val MAXIMUM_TICKET_BYTES = 256
    const val MAXIMUM_TARGET_ID_CODE_UNITS = 128
    const val MAXIMUM_PATH_CODE_UNITS = 134

    fun writeRequest(
        buffer: RegistryFriendlyByteBuf,
        payload: IdeTargetRequestPayload,
    ) {
        buffer.writeVarLong(payload.requestId)
        when (val request = payload.request) {
            is IdeTargetRequest.Attach -> {
                buffer.writeByte(ATTACH)
                buffer.writeBounded(request.claim, MAXIMUM_CLAIM_BYTES, "target claim")
            }

            is IdeTargetRequest.BeginUpload -> {
                buffer.writeByte(BEGIN_UPLOAD)
                buffer.writeTarget(request.target)
                buffer.writeHash(request.artifactHash)
                buffer.writeVarInt(request.bytes)
            }

            is IdeTargetRequest.UploadChunk -> {
                buffer.writeByte(UPLOAD_CHUNK)
                buffer.writeTarget(request.target)
                buffer.writeVarInt(request.offset)
                buffer.writeBounded(request.bytes, MAXIMUM_CHUNK_BYTES, "artifact chunk")
            }

            is IdeTargetRequest.Verify -> {
                buffer.writeByte(VERIFY)
                buffer.writeTarget(request.target)
            }

            is IdeTargetRequest.ExecutableRevision -> {
                buffer.writeByte(EXECUTABLE_REVISION)
                buffer.writeTarget(request.target)
                buffer.writePath(request.path)
            }

            is IdeTargetRequest.Deploy -> {
                buffer.writeByte(DEPLOY)
                buffer.writeTarget(request.target)
                buffer.writeBounded(request.ticket, MAXIMUM_TICKET_BYTES, "verification ticket")
                buffer.writeHash(request.artifactHash)
                buffer.writeVarInt(request.artifactBytes)
                buffer.writePath(request.path)
                buffer.writeRevision(request.expected)
            }

            is IdeTargetRequest.SubmitCanonicalLine -> {
                buffer.writeByte(SUBMIT_CANONICAL_LINE)
                buffer.writeTarget(request.target)
                buffer.writeCanonicalLine(request.line)
            }

            is IdeTargetRequest.Heartbeat -> {
                buffer.writeByte(HEARTBEAT)
                buffer.writeTarget(request.target)
            }

            is IdeTargetRequest.Detach -> {
                buffer.writeByte(DETACH)
                buffer.writeTarget(request.target)
            }
        }
    }

    fun readRequest(buffer: RegistryFriendlyByteBuf): IdeTargetRequestPayload {
        val requestId = buffer.readVarLong()
        val request =
            when (val kind = buffer.readUnsignedByte().toInt()) {
                ATTACH -> IdeTargetRequest.Attach(buffer.readBounded(MAXIMUM_CLAIM_BYTES, "target claim"))
                BEGIN_UPLOAD -> IdeTargetRequest.BeginUpload(buffer.readTarget(), buffer.readHash(), buffer.readVarInt())
                UPLOAD_CHUNK -> IdeTargetRequest.UploadChunk(
                    buffer.readTarget(),
                    buffer.readVarInt(),
                    buffer.readBounded(MAXIMUM_CHUNK_BYTES, "artifact chunk"),
                )
                VERIFY -> IdeTargetRequest.Verify(buffer.readTarget())
                EXECUTABLE_REVISION -> IdeTargetRequest.ExecutableRevision(buffer.readTarget(), buffer.readPath())
                DEPLOY -> IdeTargetRequest.Deploy(
                    buffer.readTarget(),
                    buffer.readBounded(MAXIMUM_TICKET_BYTES, "verification ticket"),
                    buffer.readHash(),
                    buffer.readVarInt(),
                    buffer.readPath(),
                    buffer.readRevision(),
                )
                SUBMIT_CANONICAL_LINE -> IdeTargetRequest.SubmitCanonicalLine(buffer.readTarget(), buffer.readCanonicalLine())
                HEARTBEAT -> IdeTargetRequest.Heartbeat(buffer.readTarget())
                DETACH -> IdeTargetRequest.Detach(buffer.readTarget())
                else -> throw IllegalArgumentException("unknown IDE target request kind $kind")
            }
        return IdeTargetRequestPayload(requestId, request)
    }

    private const val ATTACH = 0
    private const val BEGIN_UPLOAD = 1
    private const val UPLOAD_CHUNK = 2
    private const val VERIFY = 3
    private const val EXECUTABLE_REVISION = 4
    private const val DEPLOY = 5
    private const val SUBMIT_CANONICAL_LINE = 6
    private const val HEARTBEAT = 7
    private const val DETACH = 8
}

private fun RegistryFriendlyByteBuf.writeTarget(target: IdeTargetReference) {
    writeUtf(target.id.value, IdeTargetWireProtocol.MAXIMUM_TARGET_ID_CODE_UNITS)
    writeHash(target.profile.value)
}

private fun RegistryFriendlyByteBuf.readTarget(): IdeTargetReference =
    IdeTargetReference(
        IdeTargetId(readUtf(IdeTargetWireProtocol.MAXIMUM_TARGET_ID_CODE_UNITS)),
        IdeTargetProfileId(readHash()),
    )

private fun RegistryFriendlyByteBuf.writeHash(hash: Hash256) = writeBytes(hash.toByteArray())

private fun RegistryFriendlyByteBuf.readHash(): Hash256 = Hash256.of(ByteArray(32).also(::readBytes))

private fun RegistryFriendlyByteBuf.writeBounded(
    value: BinaryValue,
    maximum: Int,
    description: String,
) {
    val bytes = value.toByteArray()
    require(bytes.isNotEmpty() && bytes.size <= maximum) { "$description must contain 1..$maximum bytes" }
    writeVarInt(bytes.size)
    writeBytes(bytes)
}

private fun RegistryFriendlyByteBuf.readBounded(
    maximum: Int,
    description: String,
): BinaryValue {
    val size = readVarInt()
    require(size in 1..maximum) { "$description must contain 1..$maximum bytes" }
    return BinaryValue.of(ByteArray(size).also(::readBytes))
}

private fun RegistryFriendlyByteBuf.writePath(path: IdeDeploymentPath) =
    writeUtf(path.value, IdeTargetWireProtocol.MAXIMUM_PATH_CODE_UNITS)

private fun RegistryFriendlyByteBuf.readPath(): IdeDeploymentPath {
    val value = readUtf(IdeTargetWireProtocol.MAXIMUM_PATH_CODE_UNITS)
    require(value.startsWith("/home/")) { "deployment path must be rooted in /home" }
    return IdeDeploymentPath.fromProgramName(value.removePrefix("/home/"))
}

private fun RegistryFriendlyByteBuf.writeRevision(revision: IdeExecutableRevision) {
    when (revision) {
        IdeExecutableRevision.Absent -> writeByte(0)
        is IdeExecutableRevision.Present -> {
            writeByte(1)
            writeVarLong(revision.generation)
        }
    }
}

private fun RegistryFriendlyByteBuf.readRevision(): IdeExecutableRevision =
    when (val kind = readUnsignedByte().toInt()) {
        0 -> IdeExecutableRevision.Absent
        1 -> IdeExecutableRevision.Present(readVarLong())
        else -> throw IllegalArgumentException("unknown executable revision kind $kind")
    }

private fun RegistryFriendlyByteBuf.writeCanonicalLine(line: IdeCanonicalLine) {
    val chars = line.chars()
    require(chars.size <= IdeTargetWireProtocol.MAXIMUM_CANONICAL_LINE_CODE_UNITS) { "canonical line is too long" }
    writeVarInt(chars.size)
    chars.forEach { value -> writeShort(value.code) }
}

private fun RegistryFriendlyByteBuf.readCanonicalLine(): IdeCanonicalLine {
    val size = readVarInt()
    require(size in 0..IdeTargetWireProtocol.MAXIMUM_CANONICAL_LINE_CODE_UNITS) { "canonical line is too long" }
    return IdeCanonicalLine.of(CharArray(size) { readUnsignedShort().toChar() })
}

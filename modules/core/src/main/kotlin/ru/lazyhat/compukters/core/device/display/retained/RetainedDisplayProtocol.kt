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

package ru.lazyhat.compukters.core.device.display.retained

enum class RetainedDisplayResyncReason(
    val code: Int,
) {
    BASE_MISMATCH(1),
    REPLICA_STATE_LOST(2),
    RENDER_RESOURCE_LOST(3),
    MESSAGE_VALIDATION_FAILED(4),
    ATOMIC_INSTALL_FAILED(5),
}

object RetainedDisplayProtocol {
    internal const val MAGIC: UInt = 0x5053_444bu
    internal const val VERSION: Int = 1
    internal const val SNAPSHOT_KIND: Int = 1
    internal const val DELTA_KIND: Int = 2
    internal const val ACK_KIND: Int = 3
    internal const val RESYNC_KIND: Int = 4
    internal const val HEADER_BYTES: Int = 24

    const val MAX_MESSAGE_BYTES: Int = 512 * 1024

    fun encodeAcknowledgement(
        computerId: UInt,
        viewerEpoch: ULong,
        targetSequence: ULong,
    ): ByteArray {
        require(computerId != 0u) { "computerId must be non-zero" }
        require(viewerEpoch != 0uL) { "viewerEpoch must be non-zero" }
        return ByteArray(32).also { bytes ->
            writeHeader(bytes, ACK_KIND, computerId, viewerEpoch)
            writeU64(bytes, 24, targetSequence)
        }
    }

    fun encodeResyncRequest(
        computerId: UInt,
        viewerEpoch: ULong,
        currentSequence: ULong?,
        reason: RetainedDisplayResyncReason,
    ): ByteArray {
        require(computerId != 0u) { "computerId must be non-zero" }
        require(viewerEpoch != 0uL) { "viewerEpoch must be non-zero" }
        return ByteArray(40).also { bytes ->
            writeHeader(bytes, RESYNC_KIND, computerId, viewerEpoch)
            writeU64(bytes, 24, currentSequence ?: 0uL)
            writeU16(bytes, 32, reason.code)
            writeU16(bytes, 34, if (currentSequence == null) 0 else 1)
        }
    }

    private fun writeHeader(
        bytes: ByteArray,
        kind: Int,
        computerId: UInt,
        viewerEpoch: ULong,
    ) {
        writeU32(bytes, 0, MAGIC)
        writeU16(bytes, 4, VERSION)
        writeU16(bytes, 6, kind)
        writeU32(bytes, 8, bytes.size.toUInt())
        writeU32(bytes, 12, computerId)
        writeU64(bytes, 16, viewerEpoch)
    }

    private fun writeU16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeU32(
        bytes: ByteArray,
        offset: Int,
        value: UInt,
    ) {
        repeat(4) { index -> bytes[offset + index] = (value shr (index * 8)).toByte() }
    }

    private fun writeU64(
        bytes: ByteArray,
        offset: Int,
        value: ULong,
    ) {
        repeat(8) { index -> bytes[offset + index] = (value shr (index * 8)).toByte() }
    }
}

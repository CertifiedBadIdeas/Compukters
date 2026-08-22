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

object WorkerCodec {
    private const val HEADER_BYTES = 12
    private const val VERSION = 1
    private val magic = byteArrayOf(0x43, 0x50, 0x4b, 0x57)

    fun encodeFrame(frame: WorkerFrame): ByteArray {
        val result = ByteArray(HEADER_BYTES + frame.payload.size)
        magic.copyInto(result)
        writeU16(result, 4, VERSION)
        writeU16(result, 6, frame.type.wireValue)
        writeU32(result, 8, frame.payload.size)
        frame.payload.copyInto(result, HEADER_BYTES)
        return result
    }

    fun decodeFrame(
        bytes: ByteArray,
        maximumPayloadBytes: Int,
    ): WorkerFrame {
        require(maximumPayloadBytes >= 0) { "maximum payload bytes must be non-negative" }
        if (bytes.size < HEADER_BYTES) fail(WorkerProtocolError.TRUNCATED_FRAME, "frame header is truncated")
        if (!bytes.copyOfRange(0, magic.size).contentEquals(magic)) fail(WorkerProtocolError.BAD_MAGIC, "bad frame magic")
        if (readU16(bytes, 4) != VERSION) fail(WorkerProtocolError.WRONG_VERSION, "unsupported protocol version")
        val type =
            WorkerMessageType.fromWire(readU16(bytes, 6))
                ?: fail(WorkerProtocolError.UNKNOWN_MESSAGE_TYPE, "unknown message type")
        val payloadBytes = readU32(bytes, 8)
        if (payloadBytes > maximumPayloadBytes) fail(WorkerProtocolError.FRAME_TOO_LARGE, "frame exceeds payload limit")
        val expectedBytes = HEADER_BYTES.toLong() + payloadBytes
        if (bytes.size.toLong() < expectedBytes) fail(WorkerProtocolError.TRUNCATED_FRAME, "frame payload is truncated")
        if (bytes.size.toLong() > expectedBytes) fail(WorkerProtocolError.TRAILING_BYTES, "frame contains trailing bytes")
        return WorkerFrame(type, bytes.copyOfRange(HEADER_BYTES, bytes.size))
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
        value: Int,
    ) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun readU16(
        bytes: ByteArray,
        offset: Int,
    ): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        (0 until 4).fold(0L) { value, index ->
            value or ((bytes[offset + index].toLong() and 0xff) shl (index * 8))
        }

    private fun fail(
        error: WorkerProtocolError,
        message: String,
    ): Nothing = throw WorkerProtocolException(error, message)
}

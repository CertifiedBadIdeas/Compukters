/*
 * The Compukter Kraft Developers
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

package ru.lazyhat.compukterkraft.lang.runtime.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.CRC32
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

const val K16_DURABLE_BYTE_STORE_MAGIC = "K16DUR"
val K16_DURABLE_BYTE_STORE_MAGIC_BYTES: ByteArray = K16_DURABLE_BYTE_STORE_MAGIC.encodeToByteArray()
const val K16_DURABLE_BYTE_STORE_VERSION: UShort = 1u
const val K16_DURABLE_BYTE_STORE_HEADER_SIZE: Int = 20

enum class K16DurableByteStoreError {
    Missing,
    InvalidMagic,
    UnsupportedVersion,
    TruncatedHeader,
    TruncatedPayload,
    InvalidPayloadLength,
    ChecksumMismatch,
    IoFailure,
}

class K16DurableByteStoreException(
    val error: K16DurableByteStoreError,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class K16DurableByteStore(
    private val path: Path,
) {
    private val backupPath: Path =
        path.resolveSibling("${path.fileName}.bak")

    fun write(payload: ByteArray) {
        val parent = path.parent ?: Path.of(".")
        parent.createDirectories()
        preserveCurrentAsBackup()
        writeRawAtomically(path, encodeRecord(payload))
    }

    fun read(): ByteArray =
        try {
            readRecord(path).payload
        } catch (currentFailure: K16DurableByteStoreException) {
            val backup =
                try {
                    readRecord(backupPath)
                } catch (backupFailure: K16DurableByteStoreException) {
                    currentFailure.addSuppressed(backupFailure)
                    throw currentFailure
                }
            writeRawAtomically(path, backup.raw)
            backup.payload
        }

    fun delete(): Boolean =
        try {
            val backupDeleted = Files.deleteIfExists(backupPath)
            Files.deleteIfExists(path) || backupDeleted
        } catch (error: IOException) {
            throw K16DurableByteStoreException(
                K16DurableByteStoreError.IoFailure,
                "Cannot delete K16 durable bytes at $path",
                error,
            )
        }

    private fun preserveCurrentAsBackup() {
        if (!path.exists()) return
        val current =
            try {
                readRecord(path)
            } catch (_: K16DurableByteStoreException) {
                return
            }
        writeRawAtomically(backupPath, current.raw)
    }

    private fun writeRawAtomically(
        target: Path,
        raw: ByteArray,
    ) {
        val parent = target.parent ?: Path.of(".")
        parent.createDirectories()
        val temp = Files.createTempFile(parent, "${target.fileName}.", ".tmp")
        try {
            Files.write(temp, raw, CREATE, TRUNCATE_EXISTING, WRITE)
            FileChannel.open(temp, WRITE).use { channel ->
                channel.force(true)
            }
            Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (error: IOException) {
            throw K16DurableByteStoreException(
                K16DurableByteStoreError.IoFailure,
                "Cannot durably write K16 bytes at $target",
                error,
            )
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun readRecord(recordPath: Path): Record {
        val raw =
            try {
                if (!recordPath.exists()) {
                    throw K16DurableByteStoreException(
                        K16DurableByteStoreError.Missing,
                        "K16 durable byte store record is missing at $recordPath",
                    )
                }
                Files.readAllBytes(recordPath)
            } catch (error: K16DurableByteStoreException) {
                throw error
            } catch (error: IOException) {
                throw K16DurableByteStoreException(
                    K16DurableByteStoreError.IoFailure,
                    "Cannot read K16 durable byte store record at $recordPath",
                    error,
                )
            }

        if (raw.size < K16_DURABLE_BYTE_STORE_HEADER_SIZE) {
            throw K16DurableByteStoreException(
                K16DurableByteStoreError.TruncatedHeader,
                "K16 durable byte store header is truncated at $recordPath",
            )
        }

        val magic = raw.copyOfRange(0, K16_DURABLE_BYTE_STORE_MAGIC_BYTES.size)
        if (!magic.contentEquals(K16_DURABLE_BYTE_STORE_MAGIC_BYTES)) {
            throw K16DurableByteStoreException(
                K16DurableByteStoreError.InvalidMagic,
                "K16 durable byte store magic is invalid at $recordPath",
            )
        }

        val header = ByteBuffer.wrap(raw, 0, K16_DURABLE_BYTE_STORE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val version = header.getShort(6).toInt() and 0xffff
        if (version != K16_DURABLE_BYTE_STORE_VERSION.toInt()) {
            throw K16DurableByteStoreException(
                K16DurableByteStoreError.UnsupportedVersion,
                "Unsupported K16 durable byte store version $version at $recordPath",
            )
        }

        val payloadLength = header.getLong(8)
        if (payloadLength < 0 || payloadLength > Int.MAX_VALUE) {
            throw K16DurableByteStoreException(
                K16DurableByteStoreError.InvalidPayloadLength,
                "Invalid K16 durable byte store payload length $payloadLength at $recordPath",
            )
        }
        val expectedSize =
            K16_DURABLE_BYTE_STORE_HEADER_SIZE
                .checkedAdd(payloadLength.toInt())
                ?: throw K16DurableByteStoreException(
                    K16DurableByteStoreError.InvalidPayloadLength,
                    "K16 durable byte store payload length overflows at $recordPath",
                )
        if (raw.size < expectedSize) {
            throw K16DurableByteStoreException(
                K16DurableByteStoreError.TruncatedPayload,
                "K16 durable byte store payload is truncated at $recordPath",
            )
        }
        if (raw.size > expectedSize) {
            throw K16DurableByteStoreException(
                K16DurableByteStoreError.InvalidPayloadLength,
                "K16 durable byte store record has trailing bytes at $recordPath",
            )
        }

        val payload = raw.copyOfRange(K16_DURABLE_BYTE_STORE_HEADER_SIZE, expectedSize)
        val expectedChecksum = header.getInt(16)
        val actualChecksum = checksum(payload)
        if (expectedChecksum != actualChecksum) {
            throw K16DurableByteStoreException(
                K16DurableByteStoreError.ChecksumMismatch,
                "K16 durable byte store checksum mismatch at $recordPath",
            )
        }

        return Record(payload = payload, raw = raw)
    }

    private fun encodeRecord(payload: ByteArray): ByteArray {
        val header =
            ByteBuffer
                .allocate(K16_DURABLE_BYTE_STORE_HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(K16_DURABLE_BYTE_STORE_MAGIC_BYTES)
                .putShort(K16_DURABLE_BYTE_STORE_VERSION.toShort())
                .putLong(payload.size.toLong())
                .putInt(checksum(payload))
                .array()
        return header + payload
    }

    private data class Record(
        val payload: ByteArray,
        val raw: ByteArray,
    )
}

private fun checksum(payload: ByteArray): Int {
    val crc = CRC32()
    crc.update(payload)
    return crc.value.toInt()
}

private fun Int.checkedAdd(other: Int): Int? =
    try {
        Math.addExact(this, other)
    } catch (_: ArithmeticException) {
        null
    }

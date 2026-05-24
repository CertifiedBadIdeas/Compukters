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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

const val RUX_VOLUME_MAGIC = "RUXVOL"
val RUX_VOLUME_MAGIC_BYTES: ByteArray = RUX_VOLUME_MAGIC.encodeToByteArray()
const val RUX_VOLUME_VERSION: UShort = 1u
const val RUX_VOLUME_HEADER_SIZE: Int = 16
const val DEFAULT_STORAGE0_VOLUME_SIZE: Long = 1024L * 1024L

private const val DEFAULT_MAX_VOLUME_SIZE: Long = 64L * 1024L * 1024L
private val VALID_SLOT = Regex("[A-Za-z0-9_-]+")

sealed interface RuxVolumeIdentity {
    data class ComputerOwned(
        val computerId: Int,
        val slot: String,
    ) : RuxVolumeIdentity
}

enum class RuxVolumeError {
    InvalidIdentity,
    InvalidMagic,
    UnsupportedVersion,
    TruncatedHeader,
    TruncatedPayload,
    InvalidLogicalSize,
    OutOfBounds,
    Closed,
    IoFailure,
}

class RuxVolumeException(
    val error: RuxVolumeError,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface RuxVolumeBlob : AutoCloseable {
    val path: Path

    val size: Long

    fun read(
        offset: Long,
        length: Int,
    ): ByteArray

    fun write(
        offset: Long,
        bytes: ByteArray,
    )

    fun resize(newSize: Long)

    fun flush()
}

class FileRuxVolumeStore(
    private val root: Path,
    private val defaultStorage0Size: Long = DEFAULT_STORAGE0_VOLUME_SIZE,
    private val maxVolumeSize: Long = DEFAULT_MAX_VOLUME_SIZE,
) {
    init {
        require(defaultStorage0Size > 0) { "defaultStorage0Size must be positive" }
        require(defaultStorage0Size <= maxVolumeSize) { "defaultStorage0Size must not exceed maxVolumeSize" }
        require(maxVolumeSize > 0) { "maxVolumeSize must be positive" }
    }

    fun openOrCreateComputerVolume(
        computerId: Int,
        slot: String,
    ): RuxVolumeBlob =
        openOrCreate(RuxVolumeIdentity.ComputerOwned(computerId, slot))

    fun openOrCreate(identity: RuxVolumeIdentity): RuxVolumeBlob {
        val path = pathFor(identity)
        path.parent.createDirectories()
        if (!path.exists()) {
            createNewVolume(path, defaultStorage0Size)
        }
        return openExisting(path)
    }

    private fun pathFor(identity: RuxVolumeIdentity): Path =
        when (identity) {
            is RuxVolumeIdentity.ComputerOwned -> {
                if (identity.computerId <= 0 || !VALID_SLOT.matches(identity.slot)) {
                    throw RuxVolumeException(
                        RuxVolumeError.InvalidIdentity,
                        "Invalid computer-owned volume identity: $identity",
                    )
                }
                root
                    .resolve("compukterkraft")
                    .resolve("computers")
                    .resolve(identity.computerId.toString())
                    .resolve("volumes")
                    .resolve("${identity.slot}.ruxvol")
            }
        }

    private fun createNewVolume(
        path: Path,
        logicalSize: Long,
    ) {
        try {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                file.setLength(RUX_VOLUME_HEADER_SIZE.toLong() + logicalSize)
                writeHeader(file, logicalSize)
                file.channel.force(true)
            }
        } catch (error: IOException) {
            throw RuxVolumeException(
                RuxVolumeError.IoFailure,
                "Cannot create Rux volume at $path",
                error,
            )
        }
    }

    private fun openExisting(path: Path): RuxVolumeBlob {
        try {
            val file = RandomAccessFile(path.toFile(), "rw")
            val logicalSize =
                try {
                    readAndValidateHeader(path, file)
                } catch (error: Throwable) {
                    file.close()
                    throw error
                }
            return FileRuxVolumeBlob(path, file, logicalSize, maxVolumeSize)
        } catch (error: RuxVolumeException) {
            throw error
        } catch (error: IOException) {
            throw RuxVolumeException(
                RuxVolumeError.IoFailure,
                "Cannot open Rux volume at $path",
                error,
            )
        }
    }

    private fun readAndValidateHeader(
        path: Path,
        file: RandomAccessFile,
    ): Long {
        if (file.length() < RUX_VOLUME_HEADER_SIZE) {
            throw RuxVolumeException(
                RuxVolumeError.TruncatedHeader,
                "Rux volume header is truncated at $path",
            )
        }

        val header = ByteArray(RUX_VOLUME_HEADER_SIZE)
        file.seek(0)
        file.readFully(header)

        val magic = header.copyOfRange(0, RUX_VOLUME_MAGIC_BYTES.size)
        if (!magic.contentEquals(RUX_VOLUME_MAGIC_BYTES)) {
            throw RuxVolumeException(
                RuxVolumeError.InvalidMagic,
                "Rux volume magic is invalid at $path",
            )
        }

        val version =
            ByteBuffer
                .wrap(header, 6, UShort.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt() and 0xffff
        if (version != RUX_VOLUME_VERSION.toInt()) {
            throw RuxVolumeException(
                RuxVolumeError.UnsupportedVersion,
                "Unsupported Rux volume version $version at $path",
            )
        }

        val logicalSize =
            ByteBuffer
                .wrap(header, 8, Long.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .long
        validateLogicalSize(logicalSize)

        val expectedLength = RUX_VOLUME_HEADER_SIZE.toLong() + logicalSize
        val actualLength = file.length()
        if (actualLength < expectedLength) {
            throw RuxVolumeException(
                RuxVolumeError.TruncatedPayload,
                "Rux volume payload is truncated at $path",
            )
        }
        if (actualLength > expectedLength) {
            throw RuxVolumeException(
                RuxVolumeError.InvalidLogicalSize,
                "Rux volume file is longer than its logical size at $path",
            )
        }
        return logicalSize
    }

    private fun validateLogicalSize(logicalSize: Long) {
        if (logicalSize <= 0 || logicalSize > maxVolumeSize) {
            throw RuxVolumeException(
                RuxVolumeError.InvalidLogicalSize,
                "Invalid Rux volume logical size: $logicalSize",
            )
        }
    }
}

private class FileRuxVolumeBlob(
    override val path: Path,
    private val file: RandomAccessFile,
    initialSize: Long,
    private val maxVolumeSize: Long,
) : RuxVolumeBlob {
    private var closed = false
    private var currentSize = initialSize

    override val size: Long
        get() {
            ensureOpen()
            return currentSize
        }

    override fun read(
        offset: Long,
        length: Int,
    ): ByteArray {
        ensureOpen()
        validateRange(offset, length)
        val bytes = ByteArray(length)
        try {
            file.seek(payloadOffset(offset))
            file.readFully(bytes)
        } catch (error: IOException) {
            throw RuxVolumeException(
                RuxVolumeError.IoFailure,
                "Cannot read Rux volume at $path",
                error,
            )
        }
        return bytes
    }

    override fun write(
        offset: Long,
        bytes: ByteArray,
    ) {
        ensureOpen()
        validateRange(offset, bytes.size)
        try {
            file.seek(payloadOffset(offset))
            file.write(bytes)
        } catch (error: IOException) {
            throw RuxVolumeException(
                RuxVolumeError.IoFailure,
                "Cannot write Rux volume at $path",
                error,
            )
        }
    }

    override fun resize(newSize: Long) {
        ensureOpen()
        if (newSize <= 0 || newSize > maxVolumeSize) {
            throw RuxVolumeException(
                RuxVolumeError.InvalidLogicalSize,
                "Invalid Rux volume logical size: $newSize",
            )
        }
        try {
            file.setLength(RUX_VOLUME_HEADER_SIZE.toLong() + newSize)
            currentSize = newSize
            writeHeader(file, currentSize)
            file.channel.force(true)
        } catch (error: IOException) {
            throw RuxVolumeException(
                RuxVolumeError.IoFailure,
                "Cannot resize Rux volume at $path",
                error,
            )
        }
    }

    override fun flush() {
        ensureOpen()
        try {
            file.channel.force(true)
        } catch (error: IOException) {
            throw RuxVolumeException(
                RuxVolumeError.IoFailure,
                "Cannot flush Rux volume at $path",
                error,
            )
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            file.close()
        }
    }

    private fun validateRange(
        offset: Long,
        length: Int,
    ) {
        if (offset < 0 || length < 0) {
            throw RuxVolumeException(
                RuxVolumeError.OutOfBounds,
                "Rux volume access has negative offset or length",
            )
        }
        val end =
            offset
                .checkedAdd(length.toLong())
                ?: throw RuxVolumeException(
                    RuxVolumeError.OutOfBounds,
                    "Rux volume access overflows",
                )
        if (end > currentSize) {
            throw RuxVolumeException(
                RuxVolumeError.OutOfBounds,
                "Rux volume access [$offset, $end) exceeds size $currentSize",
            )
        }
    }

    private fun payloadOffset(offset: Long): Long =
        RUX_VOLUME_HEADER_SIZE.toLong() + offset

    private fun ensureOpen() {
        if (closed) {
            throw RuxVolumeException(
                RuxVolumeError.Closed,
                "Rux volume is closed",
            )
        }
    }
}

private fun writeHeader(
    file: RandomAccessFile,
    logicalSize: Long,
) {
    val header =
        ByteBuffer
            .allocate(RUX_VOLUME_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(RUX_VOLUME_MAGIC_BYTES)
            .putShort(RUX_VOLUME_VERSION.toShort())
            .putLong(logicalSize)
            .array()
    file.seek(0)
    file.write(header)
}

private fun Long.checkedAdd(other: Long): Long? =
    try {
        Math.addExact(this, other)
    } catch (_: ArithmeticException) {
        null
    }

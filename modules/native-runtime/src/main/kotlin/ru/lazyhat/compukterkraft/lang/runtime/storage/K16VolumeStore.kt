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
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

const val K16_VOLUME_MAGIC = "K16VOL"
val K16_VOLUME_MAGIC_BYTES: ByteArray = K16_VOLUME_MAGIC.encodeToByteArray()
const val K16_VOLUME_VERSION: UShort = 1u
const val K16_VOLUME_HEADER_SIZE: Int = 16
const val DEFAULT_STORAGE0_VOLUME_SIZE: Long = 1024L * 1024L

private const val DEFAULT_MAX_VOLUME_SIZE: Long = 64L * 1024L * 1024L
private val VALID_SLOT = Regex("[A-Za-z0-9_-]+")

sealed interface K16VolumeIdentity {
    data class ComputerOwned(
        val computerId: Int,
        val slot: String,
    ) : K16VolumeIdentity
}

enum class K16VolumeError {
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

class K16VolumeException(
    val error: K16VolumeError,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface K16VolumeBlob : AutoCloseable {
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

class FileK16VolumeStore(
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
    ): K16VolumeBlob =
        openOrCreate(K16VolumeIdentity.ComputerOwned(computerId, slot))

    fun openOrCreate(identity: K16VolumeIdentity): K16VolumeBlob {
        val path = pathFor(identity)
        val backupPath = backupPathFor(path)
        path.parent.createDirectories()
        if (!path.exists()) {
            if (backupPath.exists()) {
                restoreVolumeBackup(path, backupPath)
            } else {
                createNewVolume(path, defaultStorage0Size)
            }
        } else {
            recoverVolumeIfNeeded(path, backupPath)
        }
        return openExisting(path, backupPath)
    }

    private fun pathFor(identity: K16VolumeIdentity): Path =
        when (identity) {
            is K16VolumeIdentity.ComputerOwned -> {
                if (identity.computerId <= 0 || !VALID_SLOT.matches(identity.slot)) {
                    throw K16VolumeException(
                        K16VolumeError.InvalidIdentity,
                        "Invalid computer-owned volume identity: $identity",
                    )
                }
                root
                    .resolve("compukterkraft")
                    .resolve("computers")
                    .resolve(identity.computerId.toString())
                    .resolve("volumes")
                    .resolve("${identity.slot}.kv")
            }
        }

    private fun createNewVolume(
        path: Path,
        logicalSize: Long,
    ) {
        try {
            RandomAccessFile(path.toFile(), "rw").use { file ->
                file.setLength(K16_VOLUME_HEADER_SIZE.toLong() + logicalSize)
                writeHeader(file, logicalSize)
                file.channel.force(true)
            }
        } catch (error: IOException) {
            throw K16VolumeException(
                K16VolumeError.IoFailure,
                "Cannot create K16 volume at $path",
                error,
            )
        }
    }

    private fun backupPathFor(path: Path): Path =
        path.resolveSibling("${path.fileName}.bak")

    private fun recoverVolumeIfNeeded(
        path: Path,
        backupPath: Path,
    ) {
        try {
            validateVolume(path)
            return
        } catch (currentFailure: K16VolumeException) {
            if (!backupPath.exists()) throw currentFailure
            try {
                validateVolume(backupPath)
            } catch (backupFailure: K16VolumeException) {
                currentFailure.addSuppressed(backupFailure)
                throw currentFailure
            }
            restoreVolumeBackup(path, backupPath)
        }
    }

    private fun restoreVolumeBackup(
        path: Path,
        backupPath: Path,
    ) {
        try {
            copyAtomically(backupPath, path)
        } catch (error: IOException) {
            throw K16VolumeException(
                K16VolumeError.IoFailure,
                "Cannot restore K16 volume backup from $backupPath to $path",
                error,
            )
        }
    }

    private fun preserveVolumeBackup(
        path: Path,
        backupPath: Path,
    ) {
        validateVolume(path)
        try {
            copyAtomically(path, backupPath)
        } catch (error: IOException) {
            throw K16VolumeException(
                K16VolumeError.IoFailure,
                "Cannot preserve K16 volume backup from $path to $backupPath",
                error,
            )
        }
    }

    private fun copyAtomically(
        source: Path,
        target: Path,
    ) {
        target.parent.createDirectories()
        val temp = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
        try {
            Files.copy(source, temp, REPLACE_EXISTING)
            FileChannel.open(temp, WRITE).use { channel ->
                channel.force(true)
            }
            Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun validateVolume(path: Path): Long =
        try {
            RandomAccessFile(path.toFile(), "r").use { file ->
                readAndValidateHeader(path, file)
            }
        } catch (error: K16VolumeException) {
            throw error
        } catch (error: IOException) {
            throw K16VolumeException(
                K16VolumeError.IoFailure,
                "Cannot validate K16 volume at $path",
                error,
            )
        }

    private fun openExisting(
        path: Path,
        backupPath: Path,
    ): K16VolumeBlob {
        try {
            val file = RandomAccessFile(path.toFile(), "rw")
            val logicalSize =
                try {
                    readAndValidateHeader(path, file)
                } catch (error: Throwable) {
                    file.close()
                    throw error
                }
            return FileK16VolumeBlob(
                path = path,
                file = file,
                initialSize = logicalSize,
                maxVolumeSize = maxVolumeSize,
                preserveBackup = { preserveVolumeBackup(path, backupPath) },
            )
        } catch (error: K16VolumeException) {
            throw error
        } catch (error: IOException) {
            throw K16VolumeException(
                K16VolumeError.IoFailure,
                "Cannot open K16 volume at $path",
                error,
            )
        }
    }

    private fun readAndValidateHeader(
        path: Path,
        file: RandomAccessFile,
    ): Long {
        if (file.length() < K16_VOLUME_HEADER_SIZE) {
            throw K16VolumeException(
                K16VolumeError.TruncatedHeader,
                "K16 volume header is truncated at $path",
            )
        }

        val header = ByteArray(K16_VOLUME_HEADER_SIZE)
        file.seek(0)
        file.readFully(header)

        val magic = header.copyOfRange(0, K16_VOLUME_MAGIC_BYTES.size)
        if (!magic.contentEquals(K16_VOLUME_MAGIC_BYTES)) {
            throw K16VolumeException(
                K16VolumeError.InvalidMagic,
                "K16 volume magic is invalid at $path",
            )
        }

        val version =
            ByteBuffer
                .wrap(header, 6, UShort.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt() and 0xffff
        if (version != K16_VOLUME_VERSION.toInt()) {
            throw K16VolumeException(
                K16VolumeError.UnsupportedVersion,
                "Unsupported K16 volume version $version at $path",
            )
        }

        val logicalSize =
            ByteBuffer
                .wrap(header, 8, Long.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .long
        validateLogicalSize(logicalSize)

        val expectedLength = K16_VOLUME_HEADER_SIZE.toLong() + logicalSize
        val actualLength = file.length()
        if (actualLength < expectedLength) {
            throw K16VolumeException(
                K16VolumeError.TruncatedPayload,
                "K16 volume payload is truncated at $path",
            )
        }
        if (actualLength > expectedLength) {
            throw K16VolumeException(
                K16VolumeError.InvalidLogicalSize,
                "K16 volume file is longer than its logical size at $path",
            )
        }
        return logicalSize
    }

    private fun validateLogicalSize(logicalSize: Long) {
        if (logicalSize <= 0 || logicalSize > maxVolumeSize) {
            throw K16VolumeException(
                K16VolumeError.InvalidLogicalSize,
                "Invalid K16 volume logical size: $logicalSize",
            )
        }
    }
}

private class FileK16VolumeBlob(
    override val path: Path,
    private val file: RandomAccessFile,
    initialSize: Long,
    private val maxVolumeSize: Long,
    private val preserveBackup: () -> Unit,
) : K16VolumeBlob {
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
            throw K16VolumeException(
                K16VolumeError.IoFailure,
                "Cannot read K16 volume at $path",
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
            throw K16VolumeException(
                K16VolumeError.IoFailure,
                "Cannot write K16 volume at $path",
                error,
            )
        }
    }

    override fun resize(newSize: Long) {
        ensureOpen()
        if (newSize <= 0 || newSize > maxVolumeSize) {
            throw K16VolumeException(
                K16VolumeError.InvalidLogicalSize,
                "Invalid K16 volume logical size: $newSize",
            )
        }
        try {
            file.setLength(K16_VOLUME_HEADER_SIZE.toLong() + newSize)
            currentSize = newSize
            writeHeader(file, currentSize)
            file.channel.force(true)
        } catch (error: IOException) {
            throw K16VolumeException(
                K16VolumeError.IoFailure,
                "Cannot resize K16 volume at $path",
                error,
            )
        }
    }

    override fun flush() {
        ensureOpen()
        try {
            file.channel.force(true)
        } catch (error: IOException) {
            throw K16VolumeException(
                K16VolumeError.IoFailure,
                "Cannot flush K16 volume at $path",
                error,
            )
        }
        preserveBackup()
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
            throw K16VolumeException(
                K16VolumeError.OutOfBounds,
                "K16 volume access has negative offset or length",
            )
        }
        val end =
            offset
                .checkedAdd(length.toLong())
                ?: throw K16VolumeException(
                    K16VolumeError.OutOfBounds,
                    "K16 volume access overflows",
                )
        if (end > currentSize) {
            throw K16VolumeException(
                K16VolumeError.OutOfBounds,
                "K16 volume access [$offset, $end) exceeds size $currentSize",
            )
        }
    }

    private fun payloadOffset(offset: Long): Long =
        K16_VOLUME_HEADER_SIZE.toLong() + offset

    private fun ensureOpen() {
        if (closed) {
            throw K16VolumeException(
                K16VolumeError.Closed,
                "K16 volume is closed",
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
            .allocate(K16_VOLUME_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(K16_VOLUME_MAGIC_BYTES)
            .putShort(K16_VOLUME_VERSION.toShort())
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

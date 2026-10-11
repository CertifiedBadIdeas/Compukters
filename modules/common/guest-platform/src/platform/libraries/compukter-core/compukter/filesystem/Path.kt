/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package compukter.filesystem

import compukter.io.Closeable
import compukter.io.IOException

/** An exact absolute path in the computer's virtual filesystem. */
public data class Path(
    public val value: String,
) {
    init {
        if (value.length > 4096) fail(FileSystemError.INVALID_PATH, value, "path")
        check(FileSystemBindings.validate(value), value, "path")
    }

    public val name: String?
        get() = if (value == "/") null else value.substring(value.lastIndexOf('/') + 1, value.length)

    public val parent: Path?
        get() {
            if (value == "/") return null
            val end = value.lastIndexOf('/')
            return Path(if (end == 0) "/" else value.substring(0, end))
        }

    /** Resolves a relative name against this explicit base; dot traversal is rejected. */
    public fun resolve(relative: String): Path {
        if (relative.startsWith("/")) fail(FileSystemError.INVALID_PATH, relative, "resolve")
        if (relative.isEmpty()) return this
        return Path(if (value == "/") "/$relative" else "$value/$relative")
    }

    public fun stat(): FileInfo {
        val bytes = FileSystemBindings.stat(value)
        checkBytes(bytes, value, "stat")
        val kind =
            when (unsigned(bytes[1])) {
                1 -> FileKind.FILE
                2 -> FileKind.DIRECTORY
                else -> fail(FileSystemError.STORAGE_FAULTED, value, "stat")
            }
        return FileInfo(kind, decodeLong(bytes, 3), decodeLong(bytes, 11), bytes[2] != 0.toByte())
    }

    /** Only a missing entry returns null. Permission and storage failures still throw. */
    public fun statOrNull(): FileInfo? =
        try {
            stat()
        } catch (failure: FileSystemException) {
            if (failure.reason == FileSystemError.NOT_FOUND) null else throw failure
        }

    public fun openRead(): FileReader = FileReader(this, check(FileSystemBindings.openRead(value), value, "openRead"))

    /** Starts a private replacement draft. Closing it aborts; only commit publishes. */
    public fun openWrite(): AtomicFileWriter = AtomicFileWriter(this, check(FileSystemBindings.openWrite(value), value, "openWrite"))

    public fun openDirectory(): DirectoryReader =
        DirectoryReader(this, check(FileSystemBindings.openDirectory(value), value, "openDirectory"))

    public fun readBytes(maximumBytes: Int = 65536): ByteArray {
        require(maximumBytes >= 0)
        val reader = openRead()
        try {
            val length = reader.sizeBytes
            if (length > maximumBytes) fail(FileSystemError.QUOTA_EXCEEDED, value, "readBytes")
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val chunk = reader.read(if (length - offset < 4095) length - offset else 4095)
                if (chunk.size == 0) fail(FileSystemError.STORAGE_FAULTED, value, "readBytes")
                chunk.copyInto(bytes, offset)
                offset += chunk.size
            }
            return bytes
        } finally {
            reader.close()
        }
    }

    /** Strict UTF-8; the bound is the encoded byte size, independently of Guest heap quotas. */
    public fun readText(maximumBytes: Int = 65536): String = decodeUtf8(readBytes(maximumBytes), value)

    public fun writeBytes(contents: ByteArray) {
        val writer = openWrite()
        try {
            writer.write(contents)
            writer.commit()
        } finally {
            writer.close()
        }
    }

    /** Strict UTF-8, atomically replacing the entire file. Invalid surrogates never publish. */
    public fun writeText(contents: String) {
        writeBytes(encodeUtf8(contents, value))
    }

    /** A bounded collection of sorted child paths from one directory snapshot. */
    public fun list(maximumEntries: Int = 256): List<Path> {
        require(maximumEntries >= 0)
        val reader = openDirectory()
        try {
            val result = ArrayList<Path>()
            while (true) {
                val entry = reader.read() ?: return result
                if (result.size >= maximumEntries) fail(FileSystemError.QUOTA_EXCEEDED, value, "list")
                result.add(entry)
            }
        } finally {
            reader.close()
        }
    }

    public fun createDirectory() {
        check(FileSystemBindings.createDirectory(value), value, "createDirectory")
    }

    /** Creates missing ancestors. Each directory creation is a separate atomic operation. */
    public fun createDirectories() {
        val info = statOrNull()
        if (info != null) {
            if (info.kind != FileKind.DIRECTORY) fail(FileSystemError.NOT_DIRECTORY, value, "createDirectories")
            return
        }
        parent?.createDirectories()
        try {
            createDirectory()
        } catch (failure: FileSystemException) {
            if (failure.reason != FileSystemError.ALREADY_EXISTS) throw failure
            if (stat().kind != FileKind.DIRECTORY) fail(FileSystemError.NOT_DIRECTORY, value, "createDirectories")
        }
    }

    /** Deletes a file or empty directory; never recursively removes children. */
    public fun delete() {
        check(FileSystemBindings.delete(value), value, "delete")
    }

    public fun deleteIfExists(): Boolean =
        try {
            delete()
            true
        } catch (failure: FileSystemException) {
            if (failure.reason == FileSystemError.NOT_FOUND) false else throw failure
        }

    public fun moveTo(destination: Path) {
        moveTo(destination, false)
    }

    public fun moveTo(
        destination: Path,
        replaceExisting: Boolean,
    ) {
        val source = check(FileSystemBindings.openMove(value), value, "moveTo")
        try {
            check(FileSystemBindings.move(source, destination.value, replaceExisting), value, "moveTo")
        } finally {
            check(FileSystemBindings.close(source), value, "close")
        }
    }

    override fun toString(): String = value
}

public enum class FileKind { FILE, DIRECTORY }

public data class FileInfo(
    public val kind: FileKind,
    public val sizeBytes: Long,
    public val revision: Long,
    public val isExecutable: Boolean,
)

public enum class FileSystemError {
    INVALID_PATH,
    NOT_FOUND,
    ALREADY_EXISTS,
    NOT_DIRECTORY,
    IS_DIRECTORY,
    NOT_EMPTY,
    READ_ONLY,
    PERMISSION_DENIED,
    STALE_HANDLE,
    QUOTA_EXCEEDED,
    BUSY,
    STORAGE_FAULTED,
    CLOSED,
    NOT_EXECUTABLE,
    INVALID_UTF8,
}

public class FileSystemException(
    public val reason: FileSystemError,
    public val path: String,
    public val operation: String,
) : IOException("$operation: ${errorMessage(reason)}: $path")

/** Sequential reads of the immutable content captured at open time. Empty bytes mean EOF. */
public class FileReader internal constructor(
    public val path: Path,
    private val handle: Int,
) : Closeable {
    private var closed = false

    public val sizeBytes: Int
        get() {
            ensureOpen(closed, path.value, "sizeBytes")
            return check(FileSystemBindings.size(handle), path.value, "sizeBytes")
        }

    public fun read(maximumBytes: Int = 4095): ByteArray {
        require(maximumBytes > 0 && maximumBytes <= 4095)
        ensureOpen(closed, path.value, "read")
        val response = FileSystemBindings.read(handle, maximumBytes)
        checkBytes(response, path.value, "read")
        return byteRange(response, 1, response.size)
    }

    override fun close() {
        if (!closed) {
            check(FileSystemBindings.close(handle), path.value, "close")
            closed = true
        }
    }
}

/** Append-only private draft. A successful commit consumes the resource. */
public class AtomicFileWriter internal constructor(
    public val path: Path,
    private val handle: Int,
) : Closeable {
    private var closed = false

    public fun write(bytes: ByteArray) {
        ensureOpen(closed, path.value, "write")
        var offset = 0
        while (offset < bytes.size) {
            val end = if (bytes.size - offset > 4095) offset + 4095 else bytes.size
            check(FileSystemBindings.write(handle, byteRange(bytes, offset, end)), path.value, "write")
            offset = end
        }
    }

    public fun commit() {
        ensureOpen(closed, path.value, "commit")
        check(FileSystemBindings.commit(handle), path.value, "commit")
        closed = true
    }

    override fun close() {
        if (!closed) {
            check(FileSystemBindings.close(handle), path.value, "close")
            closed = true
        }
    }
}

public class DirectoryReader internal constructor(
    public val path: Path,
    private val handle: Int,
) : Closeable {
    private var closed = false

    public fun read(): Path? {
        ensureOpen(closed, path.value, "readDirectory")
        val response = FileSystemBindings.nextEntry(handle)
        if (response.isEmpty()) fail(FileSystemError.STORAGE_FAULTED, path.value, "readDirectory")
        check(-response[0].code, path.value, "readDirectory")
        return if (response.length == 1) null else path.resolve(response.substring(1, response.length))
    }

    override fun close() {
        if (!closed) {
            check(FileSystemBindings.close(handle), path.value, "close")
            closed = true
        }
    }
}

private object FileSystemBindings {
    external fun validate(path: String): Int

    external fun stat(path: String): ByteArray

    external fun openRead(path: String): Int

    external fun read(
        handle: Int,
        maximumBytes: Int,
    ): ByteArray

    external fun size(handle: Int): Int

    external fun openWrite(path: String): Int

    external fun write(
        handle: Int,
        bytes: ByteArray,
    ): Int

    external fun commit(handle: Int): Int

    external fun close(handle: Int): Int

    external fun openDirectory(path: String): Int

    external fun nextEntry(handle: Int): String

    external fun createDirectory(path: String): Int

    external fun delete(path: String): Int

    external fun openMove(path: String): Int

    external fun move(
        handle: Int,
        destination: String,
        replaceExisting: Boolean,
    ): Int
}

private fun ensureOpen(
    closed: Boolean,
    path: String,
    operation: String,
) {
    if (closed) fail(FileSystemError.CLOSED, path, operation)
}

private fun checkBytes(
    bytes: ByteArray,
    path: String,
    operation: String,
) {
    if (bytes.size == 0) fail(FileSystemError.STORAGE_FAULTED, path, operation)
    check(-unsigned(bytes[0]), path, operation)
}

private fun check(
    status: Int,
    path: String,
    operation: String,
): Int {
    if (status >= 0) return status
    val reason =
        when (-status) {
            1 -> FileSystemError.INVALID_PATH
            2 -> FileSystemError.NOT_FOUND
            3 -> FileSystemError.ALREADY_EXISTS
            4 -> FileSystemError.NOT_DIRECTORY
            5 -> FileSystemError.IS_DIRECTORY
            6 -> FileSystemError.NOT_EMPTY
            7 -> FileSystemError.READ_ONLY
            8 -> FileSystemError.PERMISSION_DENIED
            9 -> FileSystemError.STALE_HANDLE
            10 -> FileSystemError.QUOTA_EXCEEDED
            11 -> FileSystemError.BUSY
            12 -> FileSystemError.STORAGE_FAULTED
            13 -> FileSystemError.CLOSED
            14 -> FileSystemError.NOT_EXECUTABLE
            else -> FileSystemError.STORAGE_FAULTED
        }
    fail(reason, path, operation)
}

private fun fail(
    reason: FileSystemError,
    path: String,
    operation: String,
): Nothing = throw FileSystemException(reason, path, operation)

private fun unsigned(byte: Byte): Int = if (byte < 0) byte.toInt() + 256 else byte.toInt()

private fun decodeLong(
    bytes: ByteArray,
    start: Int,
): Long {
    var result = 0L
    var index = 0
    while (index < 8) {
        result = result or (unsigned(bytes[start + index]).toLong() shl (index * 8))
        index++
    }
    return result
}

private fun errorMessage(reason: FileSystemError): String =
    when (reason) {
        FileSystemError.INVALID_PATH -> "invalid path"
        FileSystemError.NOT_FOUND -> "not found"
        FileSystemError.ALREADY_EXISTS -> "already exists"
        FileSystemError.NOT_DIRECTORY -> "not a directory"
        FileSystemError.IS_DIRECTORY -> "is a directory"
        FileSystemError.NOT_EMPTY -> "directory not empty"
        FileSystemError.READ_ONLY -> "read-only filesystem"
        FileSystemError.PERMISSION_DENIED -> "permission denied"
        FileSystemError.STALE_HANDLE -> "stale file handle"
        FileSystemError.QUOTA_EXCEEDED -> "filesystem quota exceeded"
        FileSystemError.BUSY -> "filesystem busy or entry changed"
        FileSystemError.STORAGE_FAULTED -> "filesystem unavailable"
        FileSystemError.CLOSED -> "filesystem closed"
        FileSystemError.NOT_EXECUTABLE -> "not executable"
        FileSystemError.INVALID_UTF8 -> "invalid UTF-8 text"
    }

private fun utf8CodePoint(
    bytes: ByteArray,
    index: Int,
    path: String,
): Int {
    val first = unsigned(bytes[index])
    if (first < 128) return first
    val count =
        if (first in 194..223) {
            2
        } else if (first in 224..239) {
            3
        } else if (first in 240..244) {
            4
        } else {
            0
        }
    if (count == 0 || bytes.size - index < count) fail(FileSystemError.INVALID_UTF8, path, "readText")
    var value = first and (127 shr count)
    var part = 1
    while (part < count) {
        val next = unsigned(bytes[index + part])
        if (next < 128 || next > 191) fail(FileSystemError.INVALID_UTF8, path, "readText")
        value = value * 64 + next - 128
        part++
    }
    if ((count == 2 && value < 128) || (count == 3 && value < 2048) || (count == 4 && value < 65536) ||
        value > 1114111 || value in 55296..57343
    ) {
        fail(FileSystemError.INVALID_UTF8, path, "readText")
    }
    return value
}

private fun utf8Width(first: Int): Int =
    if (first < 128) {
        1
    } else if (first < 224) {
        2
    } else if (first < 240) {
        3
    } else {
        4
    }

private fun decodeUtf8(
    bytes: ByteArray,
    path: String,
): String {
    var length = 0
    var index = 0
    while (index < bytes.size) {
        val point = utf8CodePoint(bytes, index, path)
        length += if (point > 65535) 2 else 1
        index += utf8Width(unsigned(bytes[index]))
    }
    val chars = CharArray(length)
    var output = 0
    index = 0
    while (index < bytes.size) {
        val point = utf8CodePoint(bytes, index, path)
        if (point < 65536) {
            chars[output++] = point.toChar()
        } else {
            chars[output++] = (55296 + (point - 65536) / 1024).toChar()
            chars[output++] = (56320 + (point - 65536) % 1024).toChar()
        }
        index += utf8Width(unsigned(bytes[index]))
    }
    return String(chars, 0, chars.size)
}

private fun textCodePoint(
    text: String,
    index: Int,
    path: String,
): Int {
    val first = text[index].code
    if (first in 55296..56319) {
        if (index + 1 >= text.length || text[index + 1].code !in 56320..57343) {
            fail(FileSystemError.INVALID_UTF8, path, "writeText")
        }
        return 65536 + (first - 55296) * 1024 + text[index + 1].code - 56320
    }
    if (first in 56320..57343) fail(FileSystemError.INVALID_UTF8, path, "writeText")
    return first
}

private fun encodeUtf8(
    text: String,
    path: String,
): ByteArray {
    var length = 0
    var index = 0
    while (index < text.length) {
        val point = textCodePoint(text, index, path)
        val width =
            if (point < 128) {
                1
            } else if (point < 2048) {
                2
            } else if (point < 65536) {
                3
            } else {
                4
            }
        if (length > Int.MAX_VALUE - width) fail(FileSystemError.QUOTA_EXCEEDED, path, "writeText")
        length += width
        index += if (point > 65535) 2 else 1
    }
    val bytes = ByteArray(length)
    var output = 0
    index = 0
    while (index < text.length) {
        val point = textCodePoint(text, index, path)
        if (point < 128) {
            bytes[output++] = point.toByte()
        } else {
            val width =
                if (point < 2048) {
                    2
                } else if (point < 65536) {
                    3
                } else {
                    4
                }
            val prefix =
                if (width == 2) {
                    192
                } else if (width == 3) {
                    224
                } else {
                    240
                }
            bytes[output++] = (prefix + (point shr ((width - 1) * 6))).toByte()
            var shift = (width - 2) * 6
            while (shift >= 0) {
                bytes[output++] = (128 + ((point shr shift) and 63)).toByte()
                shift -= 6
            }
        }
        index += if (point > 65535) 2 else 1
    }
    return bytes
}

private fun byteRange(
    bytes: ByteArray,
    start: Int,
    end: Int,
): ByteArray {
    val result = ByteArray(end - start)
    bytes.copyInto(result, 0, start, end)
    return result
}

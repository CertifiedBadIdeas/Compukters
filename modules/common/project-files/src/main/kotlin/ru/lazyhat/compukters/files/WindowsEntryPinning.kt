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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.files

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** All child operations are relative to an already opened handle, never a reconstructed pathname. */
internal object WindowsEntryPinning {
    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface Kernel32 : StdCallLibrary {
        fun CreateFileW(
            name: WString,
            access: Int,
            sharing: Int,
            security: Pointer?,
            disposition: Int,
            flags: Int,
            template: Pointer?,
        ): Pointer?

        fun GetFileInformationByHandle(
            handle: Pointer,
            information: Pointer,
        ): Int

        fun GetFileInformationByHandleEx(
            handle: Pointer,
            informationClass: Int,
            information: Pointer,
            bytes: Int,
        ): Int

        fun ReadFile(
            handle: Pointer,
            buffer: Pointer,
            bytes: Int,
            read: IntByReference,
            overlapped: Pointer?,
        ): Int

        fun WriteFile(
            handle: Pointer,
            buffer: Pointer,
            bytes: Int,
            written: IntByReference,
            overlapped: Pointer?,
        ): Int

        fun SetFilePointerEx(
            handle: Pointer,
            distance: Long,
            position: LongByReference?,
            method: Int,
        ): Int

        fun GetFileSizeEx(
            handle: Pointer,
            size: LongByReference,
        ): Int

        fun SetEndOfFile(handle: Pointer): Int

        fun FlushFileBuffers(handle: Pointer): Int

        fun CloseHandle(handle: Pointer): Int
    }

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface NtDll : StdCallLibrary {
        fun NtCreateFile(
            handle: Pointer,
            access: Int,
            attributes: Pointer,
            status: Pointer,
            allocation: Pointer?,
            fileAttributes: Int,
            sharing: Int,
            disposition: Int,
            options: Int,
            ea: Pointer?,
            eaLength: Int,
        ): Int

        fun NtQueryDirectoryFile(
            handle: Pointer,
            event: Pointer?,
            routine: Pointer?,
            context: Pointer?,
            status: Pointer,
            information: Pointer,
            length: Int,
            informationClass: Int,
            single: Byte,
            pattern: Pointer?,
            restart: Byte,
        ): Int

        fun NtSetInformationFile(
            handle: Pointer,
            status: Pointer,
            information: Pointer,
            bytes: Int,
            informationClass: Int,
        ): Int

        fun RtlNtStatusToDosError(status: Int): Int
    }

    private val kernel: Kernel32 = Native.load("kernel32", Kernel32::class.java)
    private val nt: NtDll = Native.load("ntdll", NtDll::class.java)
    private val pointerBytes = Native.POINTER_SIZE.toLong()
    private val widePointerOffset = if (pointerBytes == 8L) 8L else 4L
    private val rootPointerOffset = if (pointerBytes == 8L) 8L else 4L
    private val namePointerOffset = rootPointerOffset + pointerBytes
    private val attributesOffset = namePointerOffset + pointerBytes
    private val objectBytes = if (pointerBytes == 8L) 48L else 24L
    private const val READ_DATA = 0x1
    private const val WRITE_DATA = 0x2
    private const val TRAVERSE = 0x20
    private const val READ_ATTRIBUTES = 0x80
    private const val DELETE = 0x10000
    private const val SYNCHRONIZE = 0x100000
    private const val OPEN_REPARSE = 0x200000
    private const val BACKUP_INTENT = 0x4000
    private const val SYNCHRONOUS = 0x20
    private const val DIRECTORY = 0x1
    private const val NON_DIRECTORY = 0x40
    private const val SHARE_READ_WRITE = 0x3
    private const val SHARE_ALL = 0x7
    private const val OPEN = 1
    private const val CREATE = 2
    private const val ATTR_DIRECTORY = 0x10
    private const val ATTR_REPARSE = 0x400
    private const val NO_MORE_FILES = -2147483642
    private const val BUFFER_OVERFLOW = -2147483643

    private data class FileIdentity(
        val volume: Long,
        val low: Long,
        val high: Long,
    )

    internal class Handle(
        val pointer: Pointer,
        val path: Path,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true) && kernel.CloseHandle(pointer) == 0) fail(path, Native.getLastError())
        }
    }

    fun openRoot(path: Path): Handle {
        val value = path.toAbsolutePath().normalize().toString()
        val nativeName = if (value.startsWith("\\\\")) "\\\\?\\UNC\\" + value.substring(2) else "\\\\?\\" + value
        val pointer =
            kernel.CreateFileW(
                WString(nativeName),
                READ_DATA or TRAVERSE or READ_ATTRIBUTES or SYNCHRONIZE,
                SHARE_READ_WRITE,
                null,
                3,
                0x02000000 or OPEN_REPARSE,
                null,
            )
        if (pointer == null || Pointer.nativeValue(pointer) == -1L ||
            Pointer.nativeValue(pointer) == 0xffffffffL
        ) {
            fail(path, Native.getLastError())
        }
        return Handle(pointer, path)
    }

    fun child(
        parent: Handle,
        name: Path,
        directory: Boolean? = null,
        create: Boolean = false,
        deleting: Boolean = false,
        writing: Boolean = false,
    ): Handle {
        val bytes = name.toString().toByteArray(Charsets.UTF_16LE)
        require(bytes.size <= 65532) { "Windows entry name exceeds native length" }
        val access =
            READ_ATTRIBUTES or SYNCHRONIZE or (
                if (deleting) {
                    DELETE
                } else if (writing) {
                    WRITE_DATA
                } else {
                    READ_DATA
                }
            ) or
                (if (directory == true) TRAVERSE else 0)
        val options =
            OPEN_REPARSE or BACKUP_INTENT or SYNCHRONOUS or
                (
                    if (directory == true) {
                        DIRECTORY
                    } else if (directory == false) {
                        NON_DIRECTORY
                    } else {
                        0
                    }
                )
        Memory(bytes.size.toLong() + 2).use { text ->
            text.clear()
            text.write(0, bytes, 0, bytes.size)
            Memory(widePointerOffset + pointerBytes).use { unicode ->
                unicode.clear()
                unicode.setShort(0, bytes.size.toShort())
                unicode.setShort(2, (bytes.size + 2).toShort())
                unicode.setPointer(widePointerOffset, text)
                Memory(objectBytes).use { attributes ->
                    attributes.clear()
                    attributes.setInt(0, objectBytes.toInt())
                    attributes.setPointer(rootPointerOffset, parent.pointer)
                    attributes.setPointer(namePointerOffset, unicode)
                    attributes.setInt(attributesOffset, 0x40)
                    Memory(pointerBytes * 2).use { status ->
                        Memory(pointerBytes).use { result ->
                            result.clear()
                            val code =
                                nt.NtCreateFile(
                                    result,
                                    access,
                                    attributes,
                                    status,
                                    null,
                                    0x80,
                                    if (directory ==
                                        true
                                    ) {
                                        SHARE_READ_WRITE
                                    } else {
                                        SHARE_ALL
                                    },
                                    if (create) CREATE else OPEN,
                                    options,
                                    null,
                                    0,
                                )
                            if (code < 0) fail(parent.path.resolve(name), nt.RtlNtStatusToDosError(code))
                            return Handle(checkNotNull(result.getPointer(0)), parent.path.resolve(name))
                        }
                    }
                }
            }
        }
    }

    fun attributes(handle: Handle): BasicFileAttributes {
        Memory(52).use { data ->
            if (kernel.GetFileInformationByHandle(handle.pointer, data) == 0) fail(handle.path, Native.getLastError())
            val flags = data.getInt(0)
            val reparse = flags and ATTR_REPARSE != 0
            val directory = flags and ATTR_DIRECTORY != 0
            val creation = time(data.getLong(4))
            val access = time(data.getLong(12))
            val modified = time(data.getLong(20))
            val size = (data.getInt(32).toLong() and 0xffffffffL shl 32) or (data.getInt(36).toLong() and 0xffffffffL)
            val identity =
                Memory(24).use { id ->
                    if (kernel.GetFileInformationByHandleEx(handle.pointer, 18, id, 24) == 0) fail(handle.path, Native.getLastError())
                    FileIdentity(id.getLong(0), id.getLong(8), id.getLong(16))
                }
            return object : BasicFileAttributes {
                override fun lastModifiedTime(): FileTime = modified

                override fun lastAccessTime(): FileTime = access

                override fun creationTime(): FileTime = creation

                override fun isRegularFile(): Boolean = !directory && !reparse

                override fun isDirectory(): Boolean = directory && !reparse

                override fun isSymbolicLink(): Boolean = false

                override fun isOther(): Boolean = reparse

                override fun size(): Long = size

                override fun fileKey(): Any = identity
            }
        }
    }

    private fun time(ticks: Long): FileTime {
        val epoch = ticks - 116444736000000000L
        return FileTime.from(Instant.ofEpochSecond(Math.floorDiv(epoch, 10000000L), Math.floorMod(epoch, 10000000L) * 100))
    }

    fun names(
        handle: Handle,
        restart: Boolean,
    ): List<String>? {
        Memory(65536).use { information ->
            Memory(pointerBytes * 2).use { io ->
                io.clear()
                val status =
                    nt.NtQueryDirectoryFile(
                        handle.pointer,
                        null,
                        null,
                        null,
                        io,
                        information,
                        65536,
                        12,
                        0,
                        null,
                        if (restart) 1 else 0,
                    )
                if (status == NO_MORE_FILES) return null
                if (status < 0 && status != BUFFER_OVERFLOW) fail(handle.path, nt.RtlNtStatusToDosError(status))
                val count = if (pointerBytes == 8L) io.getLong(pointerBytes) else io.getInt(pointerBytes).toLong() and 0xffffffffL
                require(count in 12..65536) { "invalid native directory response" }
                val result = mutableListOf<String>()
                var offset = 0L
                while (true) {
                    require(offset + 12 <= count)
                    val next = information.getInt(offset).toLong() and 0xffffffffL
                    val length = information.getInt(offset + 8).toLong() and 0xffffffffL
                    require(length % 2L == 0L && offset + 12 + length <= count)
                    val name =
                        String(CharArray((length / 2).toInt()) { index -> information.getShort(offset + 12 + index * 2L).toInt().toChar() })
                    if (name != "." && name != "..") result += name
                    if (next == 0L) break
                    require(next >= 12 && offset + next < count)
                    offset += next
                }
                return result
            }
        }
    }

    fun delete(handle: Handle) {
        Memory(4).use { data ->
            Memory(pointerBytes * 2).use { io ->
                data.setInt(0, 3) // DELETE | POSIX_SEMANTICS: unlink the opened entry immediately.
                var status = nt.NtSetInformationFile(handle.pointer, io, data, 4, 64)
                if (status == 0xc0000003.toInt() || status == 0xc000000d.toInt() || status == 0xc00000bb.toInt()) {
                    data.setInt(0, 1)
                    status = nt.NtSetInformationFile(handle.pointer, io, data, 1, 13)
                }
                if (status < 0) fail(handle.path, nt.RtlNtStatusToDosError(status))
            }
        }
    }

    fun move(
        handle: Handle,
        targetParent: Handle,
        name: Path,
        replace: Boolean,
    ) {
        val nameBytes = name.toString().toByteArray(Charsets.UTF_16LE)
        val lengthOffset = rootPointerOffset + pointerBytes
        val nameOffset = lengthOffset + 4
        Memory(nameOffset + nameBytes.size + 2).use { data ->
            data.clear()
            data.setInt(0, if (replace) 1 else 0)
            data.setPointer(rootPointerOffset, targetParent.pointer)
            data.setInt(lengthOffset, nameBytes.size)
            data.write(nameOffset, nameBytes, 0, nameBytes.size)
            Memory(pointerBytes * 2).use { io ->
                val status = nt.NtSetInformationFile(handle.pointer, io, data, data.size().toInt(), 10)
                if (status < 0) fail(handle.path, nt.RtlNtStatusToDosError(status))
            }
        }
    }

    fun seek(
        handle: Handle,
        position: Long,
    ) {
        if (kernel.SetFilePointerEx(handle.pointer, position, null, 0) == 0) fail(handle.path, Native.getLastError())
    }

    fun size(handle: Handle): Long {
        val result = LongByReference()
        if (kernel.GetFileSizeEx(handle.pointer, result) == 0) fail(handle.path, Native.getLastError())
        return result.value
    }

    fun truncate(handle: Handle) {
        if (kernel.SetEndOfFile(handle.pointer) == 0) fail(handle.path, Native.getLastError())
    }

    fun force(handle: Handle) {
        if (kernel.FlushFileBuffers(handle.pointer) == 0) fail(handle.path, Native.getLastError())
    }

    fun read(
        handle: Handle,
        data: Pointer,
        bytes: Int,
    ): Int {
        val read = IntByReference()
        if (kernel.ReadFile(handle.pointer, data, bytes, read, null) == 0) {
            val error = Native.getLastError()
            if (error == 38) return -1
            fail(handle.path, error)
        }
        return if (read.value == 0) -1 else read.value
    }

    fun write(
        handle: Handle,
        data: Pointer,
        bytes: Int,
    ): Int {
        val written = IntByReference()
        if (kernel.WriteFile(handle.pointer, data, bytes, written, null) == 0) fail(handle.path, Native.getLastError())
        return written.value
    }

    private fun fail(
        path: Path,
        error: Int,
    ): Nothing =
        throw when (error) {
            2, 3 -> NoSuchFileException(path.toString())
            5, 32 -> AccessDeniedException(path.toString(), null, "protected project access refused (Windows error $error)")
            80, 183 -> FileAlreadyExistsException(path.toString())
            else -> FileSystemException(path.toString(), null, "protected project access failed (Windows error $error)")
        }
}

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
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Loaded only by the default Windows provider, never during Linux project operations. */
internal object WindowsEntryPinning : EntryPinning {
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

        fun CloseHandle(handle: Pointer): Int
    }

    // Metadata-only handles do not participate in Windows sharing checks. READ_DATA
    // (LIST_DIRECTORY on directories) is necessary for the write/delete sharing veto.
    private const val FILE_READ_DATA = 0x1
    private const val FILE_READ_ATTRIBUTES = 0x80
    private const val FILE_SHARE_READ = 0x1
    private const val OPEN_EXISTING = 3
    private const val FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
    private const val FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
    private const val FILE_ATTRIBUTE_DIRECTORY = 0x10
    private const val FILE_ATTRIBUTE_REPARSE_POINT = 0x400
    private const val FILE_INFORMATION_BYTES = 52L
    private const val FILE_ID_INFO = 18
    private const val FILE_ID_INFO_BYTES = 24

    private data class FileIdentity(
        val volume: Long,
        val low: Long,
        val high: Long,
    )

    private val kernel: Kernel32 = Native.load("kernel32", Kernel32::class.java)

    override fun open(path: Path): EntryPin {
        val value = path.toAbsolutePath().normalize().toString()
        val nativeName = if (value.startsWith("\\\\")) "\\\\?\\UNC\\" + value.substring(2) else "\\\\?\\" + value
        // FILE_SHARE_READ only: neither rename/delete nor writes that install a reparse point
        // can race protected operations. Pre-existing incompatible handles also cause failure.
        val handle =
            kernel.CreateFileW(
                WString(nativeName),
                FILE_READ_ATTRIBUTES or FILE_READ_DATA,
                FILE_SHARE_READ,
                null,
                OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS or FILE_FLAG_OPEN_REPARSE_POINT,
                null,
            )
        if (handle == null || Pointer.nativeValue(handle) == -1L) fail(path, Native.getLastError())
        try {
            val entryFlags =
                Memory(FILE_INFORMATION_BYTES).use { information ->
                    if (kernel.GetFileInformationByHandle(handle, information) == 0) fail(path, Native.getLastError())
                    information.getInt(0)
                }
            val fileIdentity =
                Memory(FILE_ID_INFO_BYTES.toLong()).use { information ->
                    if (kernel.GetFileInformationByHandleEx(handle, FILE_ID_INFO, information, FILE_ID_INFO_BYTES) == 0) {
                        fail(path, Native.getLastError())
                    }
                    FileIdentity(information.getLong(0), information.getLong(8), information.getLong(16))
                }
            return object : EntryPin {
                override val directory: Boolean = entryFlags and FILE_ATTRIBUTE_DIRECTORY != 0
                override val reparse: Boolean = entryFlags and FILE_ATTRIBUTE_REPARSE_POINT != 0
                override val identity: Any = fileIdentity
                private val closed = AtomicBoolean()

                override fun close() {
                    if (closed.compareAndSet(false, true) && kernel.CloseHandle(handle) == 0) fail(path, Native.getLastError())
                }
            }
        } catch (failure: Throwable) {
            kernel.CloseHandle(handle)
            throw failure
        }
    }

    private fun fail(
        path: Path,
        error: Int,
    ): Nothing =
        throw when (error) {
            2, 3 -> NoSuchFileException(path.toString())
            5, 32 -> AccessDeniedException(path.toString(), null, "protected project access refused (Windows error $error)")
            else -> FileSystemException(path.toString(), null, "protected project access failed (Windows error $error)")
        }
}

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
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.ReadOnlyBufferException
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.NonReadableChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/** Synchronous handle-bound channels preserve force(true) without reconstructing a pathname. */
internal class WindowsProjectFileChannel(
    private val handle: WindowsEntryPinning.Handle,
    private val writing: Boolean,
) : FileChannel() {
    private var cursor = 0L

    private fun checkOpen() {
        if (!isOpen) throw ClosedChannelException()
    }

    @Synchronized
    override fun read(dst: ByteBuffer): Int {
        checkOpen()
        if (writing) throw NonReadableChannelException()
        if (dst.isReadOnly) throw ReadOnlyBufferException()
        if (!dst.hasRemaining()) return 0
        Memory(minOf(dst.remaining(), 65536).toLong()).use { data ->
            val read = WindowsEntryPinning.read(handle, data, data.size().toInt())
            if (read > 0) {
                dst.put(data.getByteArray(0, read))
                cursor += read
            }
            return read
        }
    }

    @Synchronized
    override fun write(src: ByteBuffer): Int {
        checkOpen()
        if (!writing) throw NonWritableChannelException()
        if (!src.hasRemaining()) return 0
        val bytes = ByteArray(minOf(src.remaining(), 65536))
        src.duplicate().get(bytes)
        Memory(bytes.size.toLong()).use { data ->
            data.write(0, bytes, 0, bytes.size)
            val written = WindowsEntryPinning.write(handle, data, bytes.size)
            src.position(src.position() + written)
            cursor += written
            return written
        }
    }

    @Synchronized
    override fun position(): Long {
        checkOpen()
        return cursor
    }

    @Synchronized
    override fun position(newPosition: Long): FileChannel {
        checkOpen()
        require(newPosition >= 0)
        WindowsEntryPinning.seek(handle, newPosition)
        cursor = newPosition
        return this
    }

    @Synchronized
    override fun size(): Long {
        checkOpen()
        return WindowsEntryPinning.size(handle)
    }

    @Synchronized
    override fun truncate(size: Long): FileChannel {
        checkOpen()
        require(size >= 0)
        if (!writing) throw NonWritableChannelException()
        if (size < size()) {
            WindowsEntryPinning.seek(handle, size)
            WindowsEntryPinning.truncate(handle)
            position(minOf(cursor, size))
        }
        return this
    }

    @Synchronized
    override fun force(metaData: Boolean) {
        checkOpen()
        if (writing) WindowsEntryPinning.force(handle)
    }

    @Synchronized
    override fun read(
        dst: ByteBuffer,
        position: Long,
    ): Int {
        val previous = cursor
        try {
            position(position)
            return read(dst)
        } finally {
            if (isOpen) position(previous)
        }
    }

    @Synchronized
    override fun write(
        src: ByteBuffer,
        position: Long,
    ): Int {
        val previous = cursor
        try {
            position(position)
            return write(src)
        } finally {
            if (isOpen) position(previous)
        }
    }

    override fun read(
        dsts: Array<out ByteBuffer>,
        offset: Int,
        length: Int,
    ): Long {
        java.util.Objects.checkFromIndexSize(offset, length, dsts.size)
        var total = 0L
        for (index in offset until offset + length) {
            val count = read(dsts[index])
            if (count < 0) return if (total == 0L) -1 else total
            total += count
            if (dsts[index].hasRemaining()) break
        }
        return total
    }

    override fun write(
        srcs: Array<out ByteBuffer>,
        offset: Int,
        length: Int,
    ): Long {
        java.util.Objects.checkFromIndexSize(offset, length, srcs.size)
        var total = 0L
        for (index in offset until offset + length) {
            total += write(srcs[index])
            if (srcs[index].hasRemaining()) break
        }
        return total
    }

    override fun transferTo(
        position: Long,
        count: Long,
        target: WritableByteChannel,
    ): Long = throw UnsupportedOperationException()

    override fun transferFrom(
        src: ReadableByteChannel,
        position: Long,
        count: Long,
    ): Long = throw UnsupportedOperationException()

    override fun map(
        mode: MapMode,
        position: Long,
        size: Long,
    ): MappedByteBuffer = throw UnsupportedOperationException()

    override fun lock(
        position: Long,
        size: Long,
        shared: Boolean,
    ): FileLock = throw UnsupportedOperationException()

    override fun tryLock(
        position: Long,
        size: Long,
        shared: Boolean,
    ): FileLock = throw UnsupportedOperationException()

    @Synchronized
    override fun implCloseChannel() = handle.close()
}

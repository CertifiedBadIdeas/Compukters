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
package ru.lazyhat.compukterkraft.network.upload

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

@JvmRecord
data class FileSlice(
    val fileId: Int,
    val offset: Int,
    val bytes: ByteBuffer?,
) {
    fun apply(files: MutableList<FileUpload?>) {
        if (fileId < 0 || fileId >= files.size) {
            LOG.warn("File ID is out-of-bounds (0 <= {} < {})", fileId, files.size)
            return
        }

        val file = files[fileId]!!.bytes
        if (offset < 0 || offset + bytes!!.remaining() > file.capacity()) {
            LOG.warn("File offset is out-of-bounds (0 <= {} <= {})", offset, file.capacity() - offset)
            return
        }

        file.put(offset, bytes, bytes.position(), bytes.remaining())
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(FileSlice::class.java)
    }
}

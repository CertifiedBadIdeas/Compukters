// SPDX-FileCopyrightText: 2021 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.upload

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

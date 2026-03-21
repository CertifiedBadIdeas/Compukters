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
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

class FileUpload(
    val name: String?,
    val bytes: ByteBuffer,
    val checksum: ByteArray?,
) {
    val length: Int = bytes.remaining()

    fun checksumMatches(): Boolean {
        // This is meant to be a checksum. Doesn't need to be cryptographically secure, hence non-constant time.
        val digest: ByteArray? = getDigest(bytes)
        return digest != null && checksum.contentEquals(digest)
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(FileUpload::class.java)

        const val CHECKSUM_LENGTH: Int = 32

        fun getDigest(bytes: ByteBuffer): ByteArray? {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(bytes.duplicate())
                return digest.digest()
            } catch (e: NoSuchAlgorithmException) {
                LOG.warn("Failed to compute digest ({})", e.toString())
                return null
            }
        }
    }
}

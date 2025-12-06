// SPDX-FileCopyrightText: 2021 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.upload

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

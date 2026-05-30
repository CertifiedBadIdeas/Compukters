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

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuxVolumeStoreTest {
    @Test
    fun `open or create creates one mebibyte storage0 volume`() {
        val root = createTempDirectory("rux-volume-store-test-")
        val store = FileRuxVolumeStore(root)

        store.openOrCreateComputerVolume(42, "storage0").use { blob ->
            assertEquals(DEFAULT_STORAGE0_VOLUME_SIZE, blob.size)
            assertTrue(root.resolve("compukterkraft/computers/42/volumes/storage0.kv").exists())
        }
    }

    @Test
    fun `written bytes survive reopen`() {
        val root = createTempDirectory("rux-volume-store-test-")
        val store = FileRuxVolumeStore(root)

        store.openOrCreateComputerVolume(42, "storage0").use { blob ->
            blob.write(8, byteArrayOf(1, 2, 3, 4))
            blob.flush()
        }

        store.openOrCreateComputerVolume(42, "storage0").use { blob ->
            assertContentEquals(byteArrayOf(1, 2, 3, 4), blob.read(8, 4))
        }
    }

    @Test
    fun `resize growth zero fills new bytes`() {
        val root = createTempDirectory("rux-volume-store-test-")
        val store = FileRuxVolumeStore(root, defaultStorage0Size = 16)

        store.openOrCreateComputerVolume(42, "storage0").use { blob ->
            blob.resize(20)

            assertEquals(20, blob.size)
            assertContentEquals(byteArrayOf(0, 0, 0, 0), blob.read(16, 4))
        }
    }

    @Test
    fun `resize shrink rejects reads beyond new size`() {
        val root = createTempDirectory("rux-volume-store-test-")
        val store = FileRuxVolumeStore(root, defaultStorage0Size = 16)

        store.openOrCreateComputerVolume(42, "storage0").use { blob ->
            blob.resize(8)

            val failure = assertFailsWith<RuxVolumeException> {
                blob.read(8, 1)
            }
            assertEquals(RuxVolumeError.OutOfBounds, failure.error)
        }
    }

    @Test
    fun `out of bounds write fails deterministically`() {
        val root = createTempDirectory("rux-volume-store-test-")
        val store = FileRuxVolumeStore(root, defaultStorage0Size = 16)

        store.openOrCreateComputerVolume(42, "storage0").use { blob ->
            val failure = assertFailsWith<RuxVolumeException> {
                blob.write(15, byteArrayOf(1, 2))
            }
            assertEquals(RuxVolumeError.OutOfBounds, failure.error)
        }
    }

    @Test
    fun `invalid magic fails deterministically`() {
        val root = createTempDirectory("rux-volume-store-test-")
        writeRawVolume(root, magic = "BADVOL".encodeToByteArray(), version = 1, logicalSize = 16, payloadSize = 16)

        val failure = assertFailsWith<RuxVolumeException> {
            FileRuxVolumeStore(root).openOrCreateComputerVolume(42, "storage0")
        }

        assertEquals(RuxVolumeError.InvalidMagic, failure.error)
    }

    @Test
    fun `unsupported version fails deterministically`() {
        val root = createTempDirectory("rux-volume-store-test-")
        writeRawVolume(root, magic = "RUXVOL".encodeToByteArray(), version = 2, logicalSize = 16, payloadSize = 16)

        val failure = assertFailsWith<RuxVolumeException> {
            FileRuxVolumeStore(root).openOrCreateComputerVolume(42, "storage0")
        }

        assertEquals(RuxVolumeError.UnsupportedVersion, failure.error)
    }

    @Test
    fun `truncated header fails deterministically`() {
        val root = createTempDirectory("rux-volume-store-test-")
        volumePath(root).parent.createDirectories()
        volumePath(root).writeBytes("RUX".encodeToByteArray())

        val failure = assertFailsWith<RuxVolumeException> {
            FileRuxVolumeStore(root).openOrCreateComputerVolume(42, "storage0")
        }

        assertEquals(RuxVolumeError.TruncatedHeader, failure.error)
    }

    @Test
    fun `truncated payload fails deterministically`() {
        val root = createTempDirectory("rux-volume-store-test-")
        writeRawVolume(root, magic = "RUXVOL".encodeToByteArray(), version = 1, logicalSize = 16, payloadSize = 8)

        val failure = assertFailsWith<RuxVolumeException> {
            FileRuxVolumeStore(root).openOrCreateComputerVolume(42, "storage0")
        }

        assertEquals(RuxVolumeError.TruncatedPayload, failure.error)
    }

    private fun writeRawVolume(
        root: java.nio.file.Path,
        magic: ByteArray,
        version: Int,
        logicalSize: Long,
        payloadSize: Int,
    ) {
        val header = ByteArray(RUX_VOLUME_HEADER_SIZE)
        magic.copyInto(header, endIndex = magic.size.coerceAtMost(RUX_VOLUME_MAGIC_BYTES.size))
        header[6] = (version and 0xff).toByte()
        header[7] = ((version ushr 8) and 0xff).toByte()
        var value = logicalSize
        for (index in 0 until Long.SIZE_BYTES) {
            header[8 + index] = (value and 0xff).toByte()
            value = value ushr 8
        }
        volumePath(root).parent.createDirectories()
        volumePath(root).writeBytes(header + ByteArray(payloadSize))
    }

    private fun volumePath(root: java.nio.file.Path) =
        root.resolve("compukterkraft/computers/42/volumes/storage0.kv")
}

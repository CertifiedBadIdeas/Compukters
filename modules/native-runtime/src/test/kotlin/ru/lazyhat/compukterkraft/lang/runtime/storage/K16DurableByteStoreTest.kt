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

import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class K16DurableByteStoreTest {
    @Test
    fun `write and read persists payload through integrity envelope`() {
        val root = createTempDirectory("k16-durable-byte-store-test-")
        val path = root.resolve("runtime.snapshot")
        val store = K16DurableByteStore(path)

        store.write(byteArrayOf(1, 2, 3, 4))

        assertTrue(path.exists())
        assertContentEquals(byteArrayOf(1, 2, 3, 4), store.read())
        assertEquals(
            emptyList(),
            root.listDirectoryEntries("*.tmp"),
            "durable writes should not leave temp files behind",
        )
    }

    @Test
    fun `checksum mismatch is rejected before payload is used`() {
        val root = createTempDirectory("k16-durable-byte-store-test-")
        val path = root.resolve("runtime.snapshot")
        val store = K16DurableByteStore(path)
        store.write(byteArrayOf(1, 2, 3, 4))

        val corrupt = path.readBytes()
        corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 0x7f).toByte()
        path.writeBytes(corrupt)

        val failure =
            assertFailsWith<K16DurableByteStoreException> {
                store.read()
            }

        assertEquals(K16DurableByteStoreError.ChecksumMismatch, failure.error)
    }

    @Test
    fun `truncated current file restores previous valid backup`() {
        val root = createTempDirectory("k16-durable-byte-store-test-")
        val path = root.resolve("runtime.snapshot")
        val store = K16DurableByteStore(path)
        val previous = byteArrayOf(0x10, 0x20, 0x30)
        val current = byteArrayOf(0x40, 0x50, 0x60)

        store.write(previous)
        store.write(current)
        path.writeBytes(path.readBytes().copyOf(6))

        assertContentEquals(previous, store.read())
        assertContentEquals(previous, store.read())
    }
}

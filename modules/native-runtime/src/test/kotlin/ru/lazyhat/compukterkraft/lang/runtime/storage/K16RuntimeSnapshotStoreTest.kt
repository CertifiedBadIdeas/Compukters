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
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class K16RuntimeSnapshotStoreTest {
    @Test
    fun `write and read computer snapshot uses deterministic world path`() {
        val root = createTempDirectory("k16-runtime-snapshot-store-test-")
        val store = K16RuntimeSnapshotStore(root)

        store.writeComputerSnapshot(42, byteArrayOf(1, 2, 3, 4))

        val path = root.resolve("compukterkraft/computers/42/runtime.ksnap")
        assertTrue(path.exists())
        assertContentEquals(byteArrayOf(1, 2, 3, 4), store.readComputerSnapshot(42))
    }

    @Test
    fun `corrupt current snapshot recovers from previous valid backup`() {
        val root = createTempDirectory("k16-runtime-snapshot-store-test-")
        val store = K16RuntimeSnapshotStore(root)
        val previous = byteArrayOf(0x10, 0x20, 0x30)
        val current = byteArrayOf(0x40, 0x50, 0x60)

        store.writeComputerSnapshot(42, previous)
        store.writeComputerSnapshot(42, current)
        val path = root.resolve("compukterkraft/computers/42/runtime.ksnap")
        path.writeBytes(path.readBytes().copyOf(6))

        assertContentEquals(previous, store.readComputerSnapshot(42))
        assertContentEquals(previous, store.readComputerSnapshot(42))
    }

    @Test
    fun `invalid computer id fails before path selection`() {
        val root = createTempDirectory("k16-runtime-snapshot-store-test-")
        val store = K16RuntimeSnapshotStore(root)

        val failure =
            assertFailsWith<K16RuntimeSnapshotStoreException> {
                store.writeComputerSnapshot(0, byteArrayOf(1))
            }

        assertEquals(K16RuntimeSnapshotStoreError.InvalidComputerId, failure.error)
    }

    @Test
    fun `delete computer snapshot removes current record and recovery backup`() {
        val root = createTempDirectory("k16-runtime-snapshot-store-test-")
        val store = K16RuntimeSnapshotStore(root)
        val path = root.resolve("compukterkraft/computers/42/runtime.ksnap")
        val backupPath = path.resolveSibling("runtime.ksnap.bak")
        store.writeComputerSnapshot(42, byteArrayOf(1, 2, 3))
        store.writeComputerSnapshot(42, byteArrayOf(4, 5, 6))
        assertTrue(path.exists())
        assertTrue(backupPath.exists())

        assertTrue(store.deleteComputerSnapshot(42))

        assertFalse(path.exists())
        assertFalse(backupPath.exists())
        assertEquals(null, store.readComputerSnapshotOrNull(42))
    }

    @Test
    fun `delete missing computer snapshot reports no change`() {
        val root = createTempDirectory("k16-runtime-snapshot-store-test-")
        val store = K16RuntimeSnapshotStore(root)

        assertFalse(store.deleteComputerSnapshot(42))
    }

    @Test
    fun `delete validates computer id before path selection`() {
        val root = createTempDirectory("k16-runtime-snapshot-store-test-")
        val store = K16RuntimeSnapshotStore(root)

        val failure =
            assertFailsWith<K16RuntimeSnapshotStoreException> {
                store.deleteComputerSnapshot(0)
            }

        assertEquals(K16RuntimeSnapshotStoreError.InvalidComputerId, failure.error)
    }

    @Test
    fun `delete failure preserves current snapshot`() {
        val root = createTempDirectory("k16-runtime-snapshot-store-test-")
        val store = K16RuntimeSnapshotStore(root)
        val path = root.resolve("compukterkraft/computers/42/runtime.ksnap")
        store.writeComputerSnapshot(42, byteArrayOf(1, 2, 3))
        path.resolveSibling("runtime.ksnap.bak").resolve("blocking-entry").createDirectories()

        val failure =
            assertFailsWith<K16DurableByteStoreException> {
                store.deleteComputerSnapshot(42)
            }

        assertEquals(K16DurableByteStoreError.IoFailure, failure.error)
        assertTrue(path.exists())
        assertContentEquals(byteArrayOf(1, 2, 3), store.readComputerSnapshot(42))
    }
}

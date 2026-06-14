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

import java.nio.file.Path

enum class K16RuntimeSnapshotStoreError {
    InvalidComputerId,
}

class K16RuntimeSnapshotStoreException(
    val error: K16RuntimeSnapshotStoreError,
    message: String,
) : RuntimeException(message)

class K16RuntimeSnapshotStore(
    private val root: Path,
) {
    fun writeComputerSnapshot(
        computerId: Int,
        snapshot: ByteArray,
    ) {
        snapshotStore(computerId).write(snapshot)
    }

    fun readComputerSnapshot(computerId: Int): ByteArray =
        snapshotStore(computerId).read()

    fun readComputerSnapshotOrNull(computerId: Int): ByteArray? =
        try {
            readComputerSnapshot(computerId)
        } catch (error: K16DurableByteStoreException) {
            if (error.error == K16DurableByteStoreError.Missing) {
                null
            } else {
                throw error
            }
        }

    private fun snapshotStore(computerId: Int): K16DurableByteStore =
        K16DurableByteStore(snapshotPath(computerId))

    private fun snapshotPath(computerId: Int): Path {
        if (computerId <= 0) {
            throw K16RuntimeSnapshotStoreException(
                K16RuntimeSnapshotStoreError.InvalidComputerId,
                "Invalid K16 runtime snapshot computer id: $computerId",
            )
        }
        return root
            .resolve("compukterkraft")
            .resolve("computers")
            .resolve(computerId.toString())
            .resolve("runtime.ksnap")
    }
}

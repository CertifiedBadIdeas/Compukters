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

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes

class K16ImmutableArtifactWorkspace(
    root: Path,
) {
    private val artifactDirectory = root.resolve("compukterkraft/artifacts")

    fun materialize(
        identity: String,
        bytes: ByteArray,
    ): Path {
        require(SDK_IDENTITY.matches(identity)) {
            "invalid K16 SDK artifact identity: $identity"
        }
        val artifact = artifactDirectory.resolve("$identity.kv").toAbsolutePath().normalize()
        val processLock = PROCESS_LOCKS.computeIfAbsent(artifact) { ReentrantLock() }
        return processLock.withLock {
            artifactDirectory.createDirectories()
            FileChannel
                .open(
                    artifact.resolveSibling("$identity.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                ).use { lockChannel ->
                    lockChannel.lock().use {
                        materializeLocked(artifact, identity, bytes)
                    }
                }
        }
    }

    private fun materializeLocked(
        artifact: Path,
        identity: String,
        bytes: ByteArray,
    ): Path {
        if (artifact.exists()) {
            check(artifact.readBytes().contentEquals(bytes)) {
                "immutable K16 SDK artifact identity has different bytes: $identity"
            }
            makeReadOnly(artifact)
            return artifact
        }

        val temporary = artifact.resolveSibling("$identity-${UUID.randomUUID()}.tmp")
        try {
            FileChannel
                .open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        channel.write(buffer)
                    }
                    channel.force(true)
                }
            Files.move(temporary, artifact, StandardCopyOption.ATOMIC_MOVE)
            makeReadOnly(artifact)
            check(artifact.readBytes().contentEquals(bytes)) {
                "published K16 SDK artifact bytes differ from source: $identity"
            }
            return artifact
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun makeReadOnly(artifact: Path) {
        check(artifact.toFile().setReadOnly()) {
            "could not make K16 SDK artifact read-only: $artifact"
        }
    }

    private companion object {
        val SDK_IDENTITY = Regex("[a-z][a-z0-9_]*")
        val PROCESS_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
    }
}

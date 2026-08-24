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

package ru.lazyhat.compukters.impl.fs

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import ru.lazyhat.compukters.impl.compiler.NeoForgeCompilerServices
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.minecraft.computer.ComputerFileSystemContext
import ru.lazyhat.compukters.minecraft.computer.ComputerFileSystemContextSource
import ru.lazyhat.compukters.minecraft.computer.ComputerFileSystemLease
import ru.lazyhat.compukters.minecraft.computer.ComputerFileSystemLifecycle
import java.nio.file.Files
import java.nio.file.Path

internal class WorldFileSystemStoreRegistry<S : Any>(
    private val opener: (Path) -> S,
    private val flusher: (S, ComputerId, Long) -> Unit,
    private val tombstoner: (S, ComputerId) -> Unit,
    private val recoverer: (S, ComputerId) -> Unit,
    private val closer: (S) -> Unit,
) {
    private val entries = mutableMapOf<Path, Entry<S>>()

    @Synchronized
    fun store(worldRoot: Path): S = entry(worldRoot).store

    fun lifecycle(worldRoot: Path): ComputerFileSystemLifecycle {
        val key = canonicalWorldRoot(worldRoot)
        return ComputerFileSystemLifecycle { computerId, generation, drain ->
            attach(key, computerId, generation, drain)
        }
    }

    @Synchronized
    fun save(worldRoot: Path) {
        entries[canonicalWorldRoot(worldRoot)]?.flushActive()
    }

    @Synchronized
    fun tombstone(
        worldRoot: Path,
        computerId: ComputerId,
    ) = tombstoner(entry(worldRoot).store, computerId)

    @Synchronized
    fun recover(
        worldRoot: Path,
        computerId: ComputerId,
    ) = recoverer(entry(worldRoot).store, computerId)

    @Synchronized
    fun stop(worldRoot: Path) {
        val current = entries.remove(canonicalWorldRoot(worldRoot)) ?: return
        val active = current.active.values.toList()
        current.active.clear()
        var failure: Throwable? = null
        active.forEach { attachment ->
            val generation =
                try {
                    attachment.drain()
                } catch (error: Throwable) {
                    failure = failure ?: error
                    null
                }
            if (generation != null) {
                try {
                    flusher(current.store, attachment.computerId, generation)
                } catch (error: Throwable) {
                    failure = failure ?: error
                }
            }
        }
        try {
            closer(current.store)
        } catch (error: Throwable) {
            failure = failure ?: error
        }
        failure?.let { throw it }
    }

    @Synchronized
    private fun attach(
        key: Path,
        computerId: ComputerId,
        generation: () -> Long?,
        drain: () -> Long?,
    ): ComputerFileSystemLease {
        val current = entries[key] ?: error("filesystem store has not been opened for $key")
        check(computerId !in current.active) { "computer filesystem is already active: $computerId" }
        val attachment = ActiveComputer(computerId, generation, drain)
        current.active[computerId] = attachment
        return ComputerFileSystemLease { finalGeneration -> release(key, attachment, finalGeneration) }
    }

    @Synchronized
    private fun release(
        key: Path,
        attachment: ActiveComputer,
        generation: Long?,
    ) {
        val current = entries[key] ?: return
        if (current.active[attachment.computerId] !== attachment) return
        current.active.remove(attachment.computerId)
        if (generation != null) flusher(current.store, attachment.computerId, generation)
    }

    private fun entry(worldRoot: Path): Entry<S> {
        val key = canonicalWorldRoot(worldRoot)
        return entries.getOrPut(key) {
            val storageRoot = key.resolve(STORAGE_DIRECTORY)
            Files.createDirectories(storageRoot)
            Entry(opener(storageRoot.toRealPath()))
        }
    }

    private fun canonicalWorldRoot(worldRoot: Path): Path = worldRoot.toRealPath()

    private fun Entry<S>.flushActive() {
        active.values.forEach { attachment ->
            attachment.generation()?.let { flusher(store, attachment.computerId, it) }
        }
    }

    private class Entry<S : Any>(
        val store: S,
        val active: MutableMap<ComputerId, ActiveComputer> = mutableMapOf(),
    )

    private class ActiveComputer(
        val computerId: ComputerId,
        val generation: () -> Long?,
        val drain: () -> Long?,
    )

    private companion object {
        val STORAGE_DIRECTORY: Path = Path.of("compukters", "filesystems")
    }
}

object NeoForgeWorldFileSystemStores {
    private val registry =
        WorldFileSystemStoreRegistry(
            opener = WorldFileSystemStore::open,
            flusher = WorldFileSystemStore::flush,
            tombstoner = WorldFileSystemStore::tombstone,
            recoverer = WorldFileSystemStore::recover,
            closer = WorldFileSystemStore::close,
        )

    val contextSource =
        object : ComputerFileSystemContextSource {
            override fun create(
                level: ServerLevel,
                computerId: ComputerId,
                romImage: ByteArray,
            ): ComputerFileSystemContext {
                val root = worldRoot(level.server)
                return ComputerFileSystemContext(
                    registry.store(root),
                    computerId,
                    romImage,
                    registry.lifecycle(root),
                    NeoForgeCompilerServices.router(level.server),
                )
            }

            override fun tombstone(
                level: ServerLevel,
                computerId: ComputerId,
            ) {
                registry.tombstone(worldRoot(level.server), computerId)
            }
        }

    fun recover(
        level: ServerLevel,
        computerId: ComputerId,
    ) = registry.recover(worldRoot(level.server), computerId)

    fun onLevelSave(event: LevelEvent.Save) {
        val level = event.level as? ServerLevel ?: return
        registry.save(worldRoot(level.server))
    }

    fun onServerStopping(event: ServerStoppingEvent) {
        registry.stop(worldRoot(event.server))
    }

    private fun worldRoot(server: MinecraftServer): Path = server.getWorldPath(LevelResource.ROOT)
}

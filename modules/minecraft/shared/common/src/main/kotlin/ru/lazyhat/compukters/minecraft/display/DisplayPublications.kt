/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory
import ru.lazyhat.compukters.core.display.DisplayCanvas
import ru.lazyhat.compukters.core.display.DisplayPerformanceTrace
import ru.lazyhat.compukters.core.display.DisplayPixelSnapshot
import ru.lazyhat.compukters.core.display.DisplaySurface
import ru.lazyhat.compukters.core.display.DisplayWorkQueue
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Server-owned admission/lifecycle. Worker inputs contain no live world or canvas references. */
object DisplayPublications {
    private data class Member(
        val instance: UUID,
        val column: Int,
        val row: Int,
        val position: Long,
    )

    private class State(
        val canvas: DisplayCanvas,
        val members: List<Member>,
    ) {
        var publication: DisplayWorldAccess.Publication? = null
    }

    private data class Key(
        val level: ServerLevel,
        val surface: UUID,
    )

    private data class Base(
        val mode: Int,
        val positions: String,
        val tiles: Map<UUID, ByteArray>,
        val revision: Long,
    )

    private data class Input(
        val snapshot: DisplayPixelSnapshot,
        val members: List<Member>,
        val positions: String,
        val previous: Base?,
        val identity: UUID,
        val canvasIdentity: Int,
        val revision: Long,
    )

    private data class Encoded(
        val input: Input,
        val tiles: Map<UUID, ByteArray>,
        val updates: Map<UUID, DisplayTileUpdate>,
        val unchanged: Boolean,
    )

    private class Owner(
        server: MinecraftServer,
    ) {
        val executor =
            ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(14), { job ->
                Thread(job, "Compukters display encoder").apply { isDaemon = true }
            })
        val work = DisplayWorkQueue<Key, Encoded>(executor, { server.execute(it) })
        val levels = mutableMapOf<ServerLevel, MutableMap<UUID, State>>()
        var active = true
    }

    private val owners = mutableMapOf<MinecraftServer, Owner>()
    private var sequence = 0L

    internal fun publication(
        level: ServerLevel,
        surface: DisplaySurface,
    ): DisplayWorldAccess.Publication? {
        check(level.server.isSameThread)
        val owner = owners.getOrPut(level.server) { Owner(level.server) }
        val states = owner.levels.getOrPut(level) { linkedMapOf() }
        val directory = DisplayStorage.get(level).directory
        states.keys.removeIf { directory.byId(it) == null }
        val members = members(surface)
        val state =
            states[surface.id]?.takeIf { it.canvas === surface.canvas && it.members == members }
                ?: State(surface.canvas, members).also { states[surface.id] = it }
        val previous = state.publication
        previous?.remaining?.removeIf { instance ->
            val pos = members.singleOrNull { it.instance == instance }?.position?.let(BlockPos::of)
            pos == null || !DisplayWorldAccess.matches(level, surface, instance, pos)
        }
        if (previous != null && (previous.remaining.isNotEmpty() || previous.sourceRevision == surface.canvas.revision)) return previous
        // Admission happens before the only full canvas copy. Busy surfaces retain their newest canvas instead of a queue of frames.
        owner.work.submit(
            Key(level, surface.id),
            surface.canvas.columns.toLong() * surface.canvas.rows * surface.canvas.density * surface.canvas.density * 10,
            capture = {
                val trace = DisplayPerformanceTrace.begin("sync.capture")
                Input(
                    surface.canvas.publicationSnapshot(),
                    members,
                    members.map { it.position }.sorted().joinToString(","),
                    previous?.let { Base(it.mode, it.panels, it.tiles, it.revision) },
                    surface.id,
                    System.identityHashCode(surface.canvas),
                    ++sequence,
                ).also { input ->
                    trace?.apply {
                        canvas = input.canvasIdentity
                        this.surface = input.identity.toString()
                        revision = input.snapshot.revision
                        publication = input.revision
                        panels = members.size
                        commit()
                    }
                }
            },
            compute = ::encode,
            complete = { result ->
                if (owner.active && owner.levels[level]?.get(surface.id) === state && directory.byId(surface.id) === surface &&
                    surface.canvas === state.canvas && members(surface) == members
                ) {
                    result
                        .onSuccess { encoded ->
                            state.publication =
                                if (encoded.unchanged) {
                                    checkNotNull(previous).copy(sourceRevision = encoded.input.snapshot.revision)
                                } else {
                                    DisplayWorldAccess.Publication(
                                        surface.id,
                                        encoded.input.revision,
                                        encoded.input.snapshot.mode,
                                        encoded.input.positions,
                                        encoded.tiles,
                                        members
                                            .filter {
                                                level.hasChunkAt(
                                                    BlockPos.of(it.position),
                                                )
                                            }.map { it.instance }
                                            .toMutableSet(),
                                        encoded.input.snapshot.revision,
                                        encoded.updates,
                                    )
                                }
                            // Completion is already on the server executor: avoid waiting for another block-entity tick.
                            if (!surface.attached) {
                                for (member in members) {
                                    val pos =
                                        BlockPos.of(member.position)
                                    if (DisplayWorldAccess.matches(level, surface, member.instance, pos)) {
                                        (level.getBlockEntity(pos) as? DisplayBlockEntity)?.serverTick()
                                    }
                                }
                            }
                        }.onFailure {
                            LoggerFactory
                                .getLogger(DisplayPublications::class.java)
                                .error("Display encoding failed", it)
                        }
                }
            },
        )
        return previous
    }

    private fun members(surface: DisplaySurface): List<Member> =
        surface.panels.map {
            Member(it.instance, it.column, it.row, DisplayWorldAccess.position(surface, it.column, it.row).asLong())
        }

    private fun encode(input: Input): Encoded {
        val trace = DisplayPerformanceTrace.begin("sync.publication")
        val previous = input.previous
        val tiles = input.members.associate { it.instance to input.snapshot.tile(it.column, it.row) }
        val compatible =
            previous != null && previous.mode == input.snapshot.mode && previous.positions == input.positions &&
                previous.tiles.keys == tiles.keys
        val density = DisplayCanvas.DENSITIES[input.snapshot.mode]
        val updates =
            tiles.mapValues { (id, bytes) ->
                DisplayTileUpdate.between(if (compatible) previous?.tiles?.get(id) else null, bytes, density, previous?.revision ?: -1)
            }
        val unchanged = compatible && updates.values.all { it.rgb.isEmpty() }
        trace?.apply {
            if (unchanged) phase = "sync.unchanged"
            canvas = input.canvasIdentity
            surface = input.identity.toString()
            revision = input.snapshot.revision
            publication = if (unchanged) checkNotNull(previous).revision else input.revision
            bytes = updates.values.sumOf { it.rgb.size }
            panels = tiles.size
            commit()
        }
        return Encoded(input, tiles, updates, unchanged)
    }

    internal fun invalidate(
        level: ServerLevel,
        id: UUID,
    ) {
        owners[level.server]?.levels?.get(level)?.remove(id)
    }

    fun unload(level: ServerLevel) {
        owners[level.server]?.levels?.remove(level)
    }

    fun stop(server: MinecraftServer) {
        owners.remove(server)?.let {
            it.active = false
            it.levels.clear()
            it.work.close()
            it.executor.shutdownNow()
        }
    }
}

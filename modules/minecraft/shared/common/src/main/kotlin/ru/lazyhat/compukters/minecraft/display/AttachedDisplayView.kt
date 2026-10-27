/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralWorldPositions
import java.util.UUID

/** Transport observations, never another canvas authority. Each message is bounded to one screen or tile. */
sealed interface AttachedDisplayMessage {
    val surface: UUID

    data class Member(
        val position: BlockPos,
        val instance: UUID,
    )

    data class Manifest(
        override val surface: UUID,
        val publication: Long,
        val mode: Int,
        val facing: Int,
        val opacity: Int,
        val members: List<Member>,
    ) : AttachedDisplayMessage {
        init {
            require(publication >= 0 && mode in 0..3 && facing in 0..5 && opacity in 0..255)
            require(
                members.size in 1..64 && members.distinctBy { it.position }.size == members.size &&
                    members.distinctBy { it.instance }.size == members.size,
            )
        }
    }

    data class Tile(
        override val surface: UUID,
        val instance: UUID,
        val publication: Long,
        val base: Long,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val rgb: ByteArray,
    ) : AttachedDisplayMessage {
        init {
            require(publication >= 0 && base >= -1 && (base == -1L || publication > base))
            require(x in 0..128 && y in 0..128 && width in 0..128 && height in 0..128 && x + width <= 128 && y + height <= 128)
            require(rgb.size == width * height * 3)
        }
    }

    data class Remove(
        override val surface: UUID,
    ) : AttachedDisplayMessage
}

/** Server-thread viewer cursor. A late viewer receives full tiles; later frames can use existing display deltas. */
class AttachedDisplayView {
    private class Seen(
        var manifest: AttachedDisplayMessage.Manifest? = null,
        val tiles: MutableMap<UUID, Long> = linkedMapOf(),
    )

    private var level: ServerLevel? = null
    private val seen = linkedMapOf<UUID, Seen>()

    fun update(
        current: ServerLevel,
        observer: Vec3,
        send: (AttachedDisplayMessage) -> Unit,
    ) {
        check(current.server.isSameThread)
        if (level !== current) {
            seen.keys.forEach { send(AttachedDisplayMessage.Remove(it)) }
            seen.clear()
            level = current
        }
        val visible =
            DisplayStorage.get(current).directory.snapshot().filter { screen ->
                screen.attached &&
                    screen.panels.any { panel ->
                        val pos = DisplayWorldAccess.position(screen, panel.column, panel.row)
                        current.hasChunkAt(pos) && PeripheralWorldPositions.within(current, observer, pos.center, 64.0)
                    }
            }
        val ids = visible.map { it.id }.toSet()
        for (id in seen.keys.toList().filterNot { it in ids }) {
            send(AttachedDisplayMessage.Remove(id))
            seen.remove(id)
        }
        for (surface in visible) {
            surface.canvas.expire()
            val publication = DisplayWorldAccess.publication(current, surface)
            val members = surface.panels.filter { current.hasChunkAt(DisplayWorldAccess.position(surface, it.column, it.row)) }
            if (members.isEmpty()) continue
            val manifest =
                AttachedDisplayMessage.Manifest(
                    surface.id,
                    publication.revision,
                    publication.mode,
                    surface.facing,
                    surface.canvas.opacity,
                    members.map {
                        AttachedDisplayMessage.Member(DisplayWorldAccess.position(surface, it.column, it.row), it.instance)
                    },
                )
            val cursor = seen.getOrPut(surface.id) { Seen() }
            if (cursor.manifest != manifest) {
                send(manifest)
                cursor.manifest = manifest
            }
            cursor.tiles.keys.retainAll(members.map { it.instance }.toSet())
            for (panel in members) {
                if (cursor.tiles[panel.instance] == publication.revision) continue
                val incremental = publication.updates.getValue(panel.instance)
                val update =
                    if (cursor.tiles[panel.instance] ==
                        incremental.base
                    ) {
                        incremental
                    } else {
                        DisplayTileUpdate.full(
                            publication.tiles.getValue(panel.instance),
                            ru.lazyhat.compukters.core.display.DisplayCanvas.DENSITIES[publication.mode],
                        )
                    }
                if (!DisplayWorldAccess.admitSync(current, update.rgb.size + 192)) break
                send(
                    AttachedDisplayMessage.Tile(
                        surface.id,
                        panel.instance,
                        publication.revision,
                        update.base,
                        update.x,
                        update.y,
                        update.width,
                        update.height,
                        update.rgb,
                    ),
                )
                cursor.tiles[panel.instance] = publication.revision
                publication.remaining.remove(panel.instance)
            }
        }
    }

    fun requestSnapshot(surface: UUID) {
        seen[surface]?.tiles?.clear()
    }

    companion object {
        /** Unwatched canvases retain their pixels, without an undelivered publication freezing future frames. */
        fun expire(
            level: ServerLevel,
            observers: List<Vec3>,
        ) {
            for (surface in DisplayStorage
                .get(level)
                .directory
                .snapshot()
                .filter { it.attached }) {
                surface.canvas.expire()
                if (observers.none { eye ->
                        surface.panels.any {
                            PeripheralWorldPositions.within(
                                level,
                                eye,
                                DisplayWorldAccess.position(surface, it.column, it.row).center,
                                64.0,
                            )
                        }
                    }
                ) {
                    DisplayWorldAccess.publication(level, surface).remaining.clear()
                }
            }
        }
    }
}

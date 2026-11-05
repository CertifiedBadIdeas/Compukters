/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.core.display.DisplayCanvas
import java.util.UUID

/** Keeps the previous image visible until every loaded panel has received the same publication. */
class DisplayClientImage {
    companion object {
        private var renderFrame: Long = 0

        /** Called once per render frame on the render thread. */
        fun beginRenderFrame() {
            renderFrame++
        }
    }

    private var lastRenderedFrame: Long = Long.MIN_VALUE

    private data class Tile(
        val surface: UUID,
        val publication: Long,
        val density: Int,
        val bytes: ByteArray,
        val panels: List<Long>,
    )

    var density: Int = 64
        private set
    var rgb: ByteArray = ByteArray(64 * 64 * 3)
        private set
    var revision: Long = 0
        private set

    // Ordered delivery needs the latest complete frame plus the next partial frame, never an unbounded history.
    private val pending = ArrayDeque<Tile>(2)
    private var committed: Tile? = null
    private var missingPublication: Long = -1
    val needsSnapshot: Boolean @Synchronized get() = missingPublication >= 0
    val needsInitialSnapshot: Boolean @Synchronized get() = pending.isEmpty() && committed == null

    @Synchronized
    fun requireSnapshot(publication: Long) {
        missingPublication = maxOf(publication, missingPublication)
    }

    val publication: Long get() = committed?.publication ?: 0
    val surfaceIdentity: String get() = committed?.surface?.toString() ?: ""

    @Synchronized
    fun receive(
        mode: Int,
        bytes: ByteArray,
        surface: String,
        publication: Long,
        members: String,
        base: Long = -1,
        x: Int = 0,
        y: Int = 0,
        width: Int = if (mode in DisplayCanvas.DENSITIES.indices) DisplayCanvas.DENSITIES[mode] else 0,
        height: Int = width,
    ) {
        if (mode !in DisplayCanvas.DENSITIES.indices || publication < 0 || members.length > 1408) return
        val density = DisplayCanvas.DENSITIES[mode]
        if (x !in 0..density || y !in 0..density || width !in 0..density || height !in 0..density ||
            x + width > density || y + height > density || bytes.size != width * height * 3
        ) {
            return
        }
        if ((width == 0 || height == 0) && (x != 0 || y != 0 || width != 0 || height != 0)) return
        if (base < -1 || (base >= 0 && publication <= base)) return
        if (base == -1L && (x != 0 || y != 0 || width != density || height != density)) return
        val id = runCatching { UUID.fromString(surface) }.getOrNull() ?: return
        val panels = runCatching { members.split(',').map(String::toLong) }.getOrNull() ?: return
        if (panels.size !in 1..64 || panels.distinct().size != panels.size) return
        val newest = pending.lastOrNull() ?: committed
        if (newest != null && publication <= newest.publication) return
        val image =
            if (base == -1L) {
                bytes.copyOf()
            } else {
                if (newest == null || newest.surface != id || newest.publication != base || newest.density != density ||
                    newest.panels != panels
                ) {
                    missingPublication = maxOf(missingPublication, publication)
                    return
                }
                if (bytes.isEmpty()) {
                    newest.bytes
                } else {
                    newest.bytes.copyOf().also { target ->
                        for (row in 0 until height) {
                            bytes.copyInto(
                                target,
                                ((y + row) * density + x) * 3,
                                row * width * 3,
                                (row + 1) * width * 3,
                            )
                        }
                    }
                }
            }
        if (publication >= missingPublication) missingPublication = -1
        pending.addLast(Tile(id, publication, density, image, panels))
        if (pending.size > 2) pending.removeFirst()
        ru.lazyhat.compukters.core.display.DisplayPerformanceTrace.begin("client.receive")?.apply {
            this.surface = id.toString()
            this.publication = publication
            this.bytes = bytes.size
            this.panels = panels.size
            commit()
        }
    }

    @Synchronized
    private fun candidate(tile: Tile): Tile? =
        pending.firstOrNull { it.surface == tile.surface && it.publication == tile.publication }
            ?: committed?.takeIf { it.surface == tile.surface && it.publication == tile.publication }

    @Synchronized
    private fun commit(tile: Tile) {
        density = tile.density
        if (rgb !== tile.bytes && !rgb.contentEquals(tile.bytes)) revision++
        rgb = tile.bytes
        committed = tile
        // A worker may already have prepared a later tile. Keep it for the next barrier.
        pending.removeAll { it.publication <= tile.publication }
    }

    fun publish(peer: (Long) -> DisplayClientImage?) = publish(null, peer)

    fun publishFrame(peer: (Long) -> DisplayClientImage?) = publish(renderFrame, peer)

    private fun publish(
        frame: Long?,
        peer: (Long) -> DisplayClientImage?,
    ) {
        if (frame != null) {
            if (lastRenderedFrame == frame) return
            lastRenderedFrame = frame
        }
        val candidates = synchronized(this) { pending.reversed() }
        for (tile in candidates) {
            val trace =
                ru.lazyhat.compukters.core.display.DisplayPerformanceTrace.begin("client.barrier")?.apply {
                    surface = tile.surface.toString()
                    publication = tile.publication
                }
            val ready = ArrayList<Pair<DisplayClientImage, Tile>>()
            var complete = true
            for (position in tile.panels) {
                val image = peer(position) ?: continue
                // A visible peer has already drawn this frame; keep the whole surface on that publication.
                if (frame != null && image !== this && image.lastRenderedFrame == frame) return
                val candidate = image.candidate(tile)
                if (candidate == null) {
                    complete = false
                    break
                }
                ready.add(image to candidate)
            }
            trace?.apply {
                if (complete) phase = "client.ready"
                panels = ready.size
                commit()
            }
            if (complete) {
                // Commit exact checked candidates; retain newer partial data for the next render.
                ready.forEach { (image, candidate) -> image.commit(candidate) }
                return
            }
        }
    }
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.core.display.DisplayCanvas
import java.util.UUID

/** Keeps the previous image visible until every loaded panel has received the same publication. */
internal class DisplayClientImage {
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
    private var pending: Tile? = null
    private var committed: Tile? = null
    private var missingPublication: Long = -1
    val needsSnapshot: Boolean get() = missingPublication >= 0

    val publication: Long get() = committed?.publication ?: 0
    val surfaceIdentity: String get() = committed?.surface?.toString() ?: ""

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
        val newest = pending ?: committed
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
        pending = Tile(id, publication, density, image, panels)
        ru.lazyhat.compukters.core.display.DisplayPerformanceTrace.begin("client.receive")?.apply {
            this.surface = id.toString()
            this.publication = publication
            this.bytes = bytes.size
            this.panels = panels.size
            commit()
        }
    }

    fun publish(peer: (Long) -> DisplayClientImage?) {
        val tile = pending ?: return
        val trace =
            ru.lazyhat.compukters.core.display.DisplayPerformanceTrace.begin("client.barrier")?.apply {
                surface = tile.surface.toString()
                publication = tile.publication
            }
        var loaded = 0
        for (position in tile.panels) {
            val image = peer(position) ?: continue
            loaded++
            val candidate = image.pending ?: image.committed
            if (candidate?.surface != tile.surface || candidate.publication != tile.publication) {
                trace?.apply {
                    panels = loaded
                    commit()
                }
                return
            }
        }
        trace?.apply {
            phase = "client.ready"
            panels = loaded
            commit()
        }
        for (position in tile.panels) {
            val image = peer(position) ?: continue
            val candidate = image.pending ?: continue
            image.density = candidate.density
            if (image.rgb !== candidate.bytes && !image.rgb.contentEquals(candidate.bytes)) {
                image.revision++
            }
            image.rgb = candidate.bytes
            image.committed = candidate
            image.pending = null
        }
    }
}

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

    fun receive(
        mode: Int,
        bytes: ByteArray,
        surface: String,
        publication: Long,
        members: String,
    ) {
        if (mode !in DisplayCanvas.DENSITIES.indices || publication < 0 || members.length > 1408) return
        val density = DisplayCanvas.DENSITIES[mode]
        if (bytes.size != density * density * 3) return
        val id = runCatching { UUID.fromString(surface) }.getOrNull() ?: return
        val panels = runCatching { members.split(',').map(String::toLong) }.getOrNull() ?: return
        if (panels.size !in 1..64 || panels.distinct().size != panels.size) return
        val newest = pending ?: committed
        if (newest?.surface == id && publication <= newest.publication) return
        pending = Tile(id, publication, density, bytes.copyOf(), panels)
    }

    fun publish(peer: (Long) -> DisplayClientImage?) {
        val tile = pending ?: return
        val loaded = tile.panels.mapNotNull(peer).distinct()
        if (loaded.any { image ->
                val candidate = image.pending ?: image.committed
                candidate?.surface != tile.surface || candidate.publication != tile.publication
            }
        ) {
            return
        }
        loaded.forEach { image ->
            val candidate = image.pending ?: return@forEach
            image.density = candidate.density
            image.rgb = candidate.bytes
            image.revision++
            image.committed = candidate
            image.pending = null
        }
    }
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import ru.lazyhat.compukters.impl.compat.Identifier

/** One texture per loaded panel; removed panels release native and GPU storage. */
internal object DisplayTextureCache {
    private data class Entry(
        val location: Identifier,
        val texture: DynamicTexture,
        var revision: Long,
    )

    private val entries = linkedMapOf<NeoForgeDisplayBlockEntity, Entry>()
    private var serial = 0L

    fun texture(entity: NeoForgeDisplayBlockEntity): Identifier? {
        entity.publishClientFrame()
        val manager = Minecraft.getInstance().textureManager
        var entry = entries[entity]
        if (entry != null &&
            (entry.texture.pixels?.width != entity.displayDensity() || manager.getTexture(entry.location) !== entry.texture)
        ) {
            manager.release(entry.location)
            entries.remove(entity)
            entry = null
        }
        if (entry == null) {
            cleanup()
            if (entries.size >= 1024) return null
            val location = Identifier.fromNamespaceAndPath("compukters", "display/panel_${serial++}")
            val texture = DisplayTextureCompat.create(entity.displayDensity())
            manager.register(location, texture)
            entry = Entry(location, texture, -1)
            entries[entity] = entry
        }
        if (entry.revision != entity.displayRevision()) {
            DisplayTextureCompat.upload(entry.texture, entity.displayDensity(), entity.displayPixels())
            entry.revision = entity.displayRevision()
        }
        return entry.location
    }

    fun cleanup() {
        val minecraft = Minecraft.getInstance()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val (entity, entry) = iterator.next()
            if (entity.isRemoved || entity.level !== minecraft.level) {
                minecraft.textureManager.release(entry.location)
                iterator.remove()
            }
        }
    }
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import ru.lazyhat.compukters.impl.compat.Identifier

/** One bounded texture cache for block entities and attached display tiles. */
internal object DisplayTextureCache {
    private data class Entry(
        val location: Identifier,
        val texture: DynamicTexture,
        var revision: Long,
        val valid: () -> Boolean,
    )

    private val entries = linkedMapOf<Any, Entry>()
    private var serial = 0L

    fun texture(entity: NeoForgeDisplayBlockEntity): Identifier? {
        entity.publishClientFrame()
        return texture(entity, entity.displayDensity(), entity.displayRevision(), entity.displayPixels(), {
            !entity.isRemoved && entity.level === Minecraft.getInstance().level
        }) {
            ru.lazyhat.compukters.core.display.DisplayPerformanceTrace.begin("texture.upload")?.apply {
                surface = entity.displaySurfaceIdentity()
                publication = entity.displayPublication()
                revision = entity.displayRevision()
                panel = entity.blockPos.asLong()
                bytes = entity.displayPixels().size
                commit()
            }
        }
    }

    fun texture(
        key: Any,
        density: Int,
        revision: Long,
        pixels: ByteArray,
        valid: () -> Boolean,
        uploaded: () -> Unit = {},
    ): Identifier? {
        val manager = Minecraft.getInstance().textureManager
        var entry = entries[key]
        if (entry != null && (entry.texture.pixels?.width != density || manager.getTexture(entry.location) !== entry.texture)) {
            manager.release(entry.location)
            entries.remove(key)
            entry = null
        }
        if (entry == null) {
            cleanup()
            if (entries.size >= 1024) return null
            val location = Identifier.fromNamespaceAndPath("compukters", "display/panel_${serial++}")
            val texture = DisplayTextureCompat.create(density)
            manager.register(location, texture)
            entry = Entry(location, texture, -1, valid)
            entries[key] = entry
        }
        if (entry.revision != revision) {
            DisplayTextureCompat.upload(entry.texture, density, pixels)
            uploaded()
            entry.revision = revision
        }
        return entry.location
    }

    fun cleanup() {
        val manager = Minecraft.getInstance().textureManager
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (!entry.valid()) {
                manager.release(entry.location)
                iterator.remove()
            }
        }
    }
}

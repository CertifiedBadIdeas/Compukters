/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import net.minecraft.client.renderer.texture.DynamicTexture

internal object DisplayTextureCompat {
    fun create(density: Int): DynamicTexture = DynamicTexture(density, density, false)

    fun upload(
        texture: DynamicTexture,
        density: Int,
        bytes: ByteArray,
    ) {
        require(bytes.size == density * density * 3)
        val image = requireNotNull(texture.pixels)
        for (y in 0 until density) {
            for (x in 0 until density) {
                val at = (y * density + x) * 3
                val red = bytes[at].toInt() and 255
                val green = bytes[at + 1].toInt() and 255
                val blue = bytes[at + 2].toInt() and 255
                image.setPixelRGBA(x, y, 0xFF000000.toInt() or (blue shl 16) or (green shl 8) or red)
            }
        }
        texture.upload()
    }
}

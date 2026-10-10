/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.core.display.DisplayCanvas
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/** Server-side rasterization: clients display the exact published RGB pixels, never re-layout text. */
internal object DisplayRasterFont {
    private val font by lazy {
        val path = "/assets/compukters/font/ide/jetbrains_mono_regular.ttf"
        requireNotNull(javaClass.getResourceAsStream(path)) { "Missing bundled display font" }.use {
            Font.createFont(Font.TRUETYPE_FONT, it).deriveFont(10f)
        }
    }
    private val glyphs = linkedMapOf<Int, BooleanArray>()

    fun draw(
        canvas: DisplayCanvas,
        owner: Any,
        x: Int,
        y: Int,
        text: String,
        color: Int,
        scale: Int,
    ) {
        require(scale in 1..8)
        DisplayCanvas.requireColor(color)
        require(text.toByteArray(Charsets.UTF_8).size <= 1024)
        require(text.none { it in '\uD800'..'\uDFFF' } || validSurrogates(text))
        val points = text.codePoints().toArray()
        require(points.size <= 256)
        val lines = mutableListOf<MutableList<BooleanArray>>(mutableListOf())
        points.forEach { point ->
            if (point == '\n'.code) {
                lines += mutableListOf<BooleanArray>()
            } else if (point != '\r'.code) {
                lines.last() += glyph(point)
            }
        }
        val width = (lines.maxOfOrNull { it.size } ?: 0) * 6 * scale
        val height = lines.size * 12 * scale
        canvas.mask(owner, x, y, width, height, color) { px, py ->
            val glyph = lines[py / (12 * scale)].getOrNull(px / (6 * scale))
            glyph?.get(((py / scale) % 12) * 6 + ((px / scale) % 6)) == true
        }
    }

    private fun glyph(point: Int): BooleanArray {
        glyphs[point]?.let { return it }
        val actual = if (font.canDisplay(point)) point else '?'.code
        val image = BufferedImage(6, 12, BufferedImage.TYPE_BYTE_BINARY)
        image.createGraphics().let { graphics ->
            try {
                graphics.font = font
                graphics.color = Color.WHITE
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
                graphics.drawString(String(Character.toChars(actual)), 0, 9)
            } finally {
                graphics.dispose()
            }
        }
        val result = BooleanArray(6 * 12) { index -> image.getRGB(index % 6, index / 6) and 0xFFFFFF != 0 }
        if (glyphs.size >= 512) glyphs.remove(glyphs.keys.first())
        glyphs[point] = result
        return result
    }

    private fun validSurrogates(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val c = text[index++]
            if (c.isHighSurrogate()) {
                if (index == text.length || !text[index++].isLowSurrogate()) return false
            } else if (c.isLowSurrogate()) {
                return false
            }
        }
        return true
    }
}

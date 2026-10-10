/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.core.display.DisplayCanvas
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayRasterFontTest {
    @Test
    fun `enlarged glyphs retain detail within the original pixel grid`() {
        for (scale in 2..8) {
            val canvas = render("W", scale)
            val hasFineDetail =
                (0 until 12).any { row ->
                    (0 until 6).any { column ->
                        val pixels =
                            (0 until scale).flatMap { dy ->
                                (0 until scale).map { dx -> canvas.pixelAt(column * scale + dx, row * scale + dy) }
                            }
                        pixels.any { it == COLOR } && pixels.any { it == 0 }
                    }
                }
            assertTrue(hasFineDetail, "Scale $scale must rasterize strokes rather than duplicate base pixels")
            assertTrue(canvas.encodeRgb().any { it != 0.toByte() })
        }
    }

    @Test
    fun `scaled text preserves cell spacing newlines transparency and clipping`() {
        val owner = Any()
        val actual = DisplayCanvas(1, 1, 3)
        val expected = DisplayCanvas(1, 1, 3)
        for (canvas in listOf(actual, expected)) {
            canvas.acquire(owner) { true }
            canvas.fill(owner, 0, 0, canvas.width, canvas.height, 0x123456)
        }
        DisplayRasterFont.draw(actual, owner, -3, -5, "W W\nW", COLOR, 3)
        DisplayRasterFont.draw(expected, owner, -3, -5, "W", COLOR, 3)
        DisplayRasterFont.draw(expected, owner, 33, -5, "W", COLOR, 3)
        DisplayRasterFont.draw(expected, owner, -3, 31, "W", COLOR, 3)
        assertContentEquals(expected.encodeRgb(), actual.encodeRgb())
        assertEquals(0x123456, actual.pixelAt(20, 20))
        assertEquals(0x123456, actual.pixelAt(127, 127))
    }

    @Test
    fun `drawing other sizes does not change a cached glyph size`() {
        val small = render("ЖW?", 1).encodeRgb()
        val large = render("ЖW?", 4).encodeRgb()
        assertContentEquals(small, render("ЖW?", 1).encodeRgb())
        assertContentEquals(large, render("ЖW?", 4).encodeRgb())
    }

    private fun render(
        text: String,
        scale: Int,
    ): DisplayCanvas {
        val owner = Any()
        return DisplayCanvas(1, 1, 3).also {
            it.acquire(owner) { true }
            DisplayRasterFont.draw(it, owner, 0, 0, text, COLOR, scale)
        }
    }

    private companion object {
        const val COLOR = 0x00FF00
    }
}

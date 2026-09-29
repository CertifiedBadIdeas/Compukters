/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayTextLayoutTest {
    @Test
    fun `horizontal and vertical offsets use character cells`() {
        val buffer = DisplayBuffer()
        val owner = Any()
        buffer.writeAt(owner, { true }, 0, 0, "H")
        buffer.writeAt(owner, { true }, 5, 2, "A")
        buffer.writeAt(owner, { true }, 8, 3, "😀")
        buffer.writeAt(owner, { true }, 19, 9, "Ж")

        val glyphs = mutableListOf<Triple<Int, Int, Int>>()
        DisplayTextLayout.forEachGlyph(buffer.rows()) { x, y, codePoint ->
            glyphs += Triple(x, y, codePoint)
        }

        assertEquals(
            listOf(
                Triple(-60, -65 + 3, 'H'.code),
                Triple(-60 + 5 * 6, -65 + 2 * 13 + 3, 'A'.code),
                Triple(-60 + 8 * 6, -65 + 3 * 13 + 3, 0xFFFD),
                Triple(54, 55, 'Ж'.code),
            ),
            glyphs,
        )
    }
}

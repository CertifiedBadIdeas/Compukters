/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
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

        val glyphs = mutableListOf<Triple<Int, Int, Int>>()
        DisplayTextLayout.forEachGlyph(buffer.rows(), TerminalFontProfile.DINA) { x, y, codePoint ->
            glyphs += Triple(x, y, codePoint)
        }

        assertEquals(
            listOf(
                Triple(-60, -60, 'H'.code),
                Triple(-60 + 5 * 6, -60 + 2 * 10, 'A'.code),
                Triple(-60 + 8 * 6, -60 + 3 * 10, '?'.code),
            ),
            glyphs,
        )
    }
}

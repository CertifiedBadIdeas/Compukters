/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package ru.lazyhat.compukters.impl.ide

import kotlin.test.Test
import kotlin.test.assertEquals

class IdeCodeGlyphLayoutTest {
    @Test
    fun `code glyphs occupy exact font cells independent of glyph advance`() {
        val font = IdeCodeFontProfile.DEFAULT

        val glyphs = IdeCodeGlyphLayout.layout("Wi Ж", 17, font)

        assertEquals(listOf("W", "i", "Ж"), glyphs.map { it.value })
        assertEquals(listOf(17, 17 + font.cellWidth, 17 + font.cellWidth * 3), glyphs.map { it.x })
    }

    @Test
    fun `operators remain separate glyphs and supplementary characters occupy one cell`() {
        val font = IdeCodeFontProfile.DEFAULT

        val glyphs = IdeCodeGlyphLayout.layout("!= -> 😀x", 0, font)

        assertEquals(listOf("!", "=", "-", ">", "😀", "x"), glyphs.map { it.value })
        assertEquals(listOf(0, 1, 3, 4, 6, 7).map { it * font.cellWidth }, glyphs.map { it.x })
    }
}

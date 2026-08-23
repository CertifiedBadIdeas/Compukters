/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package ru.lazyhat.compukters.impl.terminal

import net.minecraft.resources.Identifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalFontProfileTest {
    @Test
    fun `default profile exposes fixed Cozette metrics and resource`() {
        val profile = TerminalFontProfile.DEFAULT

        assertEquals("cozette", profile.id)
        assertEquals(
            Identifier.fromNamespaceAndPath("compukters", "terminal/cozette"),
            profile.fontDescription.id(),
        )
        assertEquals(6, profile.cellWidth)
        assertEquals(13, profile.cellHeight)
        assertEquals(10, profile.ascent)
        assertEquals(3, profile.glyphDrawOffsetY)
        assertEquals(0xFFFD, profile.replacementCodePoint)
    }

    @Test
    fun `fallback is deterministic and never indexes an unsupported code point`() {
        val profile = TerminalFontProfile.DEFAULT

        assertTrue(profile.supports('Ж'.code))
        assertFalse(profile.supports(0x1F680))
        assertEquals('Ж'.code, profile.renderCodePoint('Ж'.code))
        assertEquals(0xFFFD, profile.renderCodePoint(0x1F680))
        assertEquals(0xFFFD, profile.renderCodePoint(-1))
    }
}

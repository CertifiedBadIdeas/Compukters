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

    @Test
    fun `catalog resolves stable IDs and cycles in presentation order`() {
        assertEquals(TerminalFontProfile.COZETTE, TerminalFontProfile.fromId("cozette"))
        assertEquals(TerminalFontProfile.DINA, TerminalFontProfile.fromId("dina"))
        assertEquals(TerminalFontProfile.PROGGY_TINY, TerminalFontProfile.fromId("proggy_tiny"))
        assertEquals(TerminalFontProfile.DEFAULT, TerminalFontProfile.fromId("missing"))
        assertEquals(TerminalFontProfile.DEFAULT, TerminalFontProfile.fromId(null))
        assertEquals(TerminalFontProfile.DINA, TerminalFontProfile.COZETTE.next())
        assertEquals(TerminalFontProfile.PROGGY_TINY, TerminalFontProfile.DINA.next())
        assertEquals(TerminalFontProfile.COZETTE, TerminalFontProfile.PROGGY_TINY.next())
    }

    @Test
    fun `compact profiles use fixed six by ten cells and honest native fallback`() {
        listOf(TerminalFontProfile.DINA, TerminalFontProfile.PROGGY_TINY).forEach { profile ->
            assertEquals(6, profile.cellWidth)
            assertEquals(10, profile.cellHeight)
            assertEquals(8, profile.ascent)
            assertEquals(1, profile.glyphDrawOffsetY)
            assertEquals('?'.code, profile.replacementCodePoint)
            assertTrue(profile.supports('?'.code))
            assertFalse(profile.supports('Ж'.code))
            assertEquals('?'.code, profile.renderCodePoint('Ж'.code))
        }
    }
}

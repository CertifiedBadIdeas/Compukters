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

import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdeTerminalOverlayTest {
    @Test
    fun `every font keeps the exact guest grid and anchors the panel over immutable content`() {
        TerminalFontProfile.ALL.forEach { font ->
            val geometry = IdeRenderGeometry.compute(960, 540, 24, 180, 120, true, true, font)
            val editor = geometry.editor

            val overlay = IdeTerminalOverlayGeometry.compute(geometry.content, font)

            assertTrue(overlay.supported)
            assertEquals(geometry.content.right, overlay.panel.right)
            assertEquals(51 * font.cellWidth, overlay.grid?.width)
            assertEquals(19 * font.cellHeight, overlay.grid?.height)
            assertTrue(overlay.panel.top > geometry.content.top)
            assertTrue(overlay.panel.bottom < geometry.content.bottom)
            assertEquals(editor, geometry.editor)
        }
    }

    @Test
    fun `small content produces a bounded unavailable surface instead of a cropped grid`() {
        val content = IdeRect(10, 20, 210, 140)

        val overlay = IdeTerminalOverlayGeometry.compute(content, TerminalFontProfile.COZETTE)

        assertFalse(overlay.supported)
        assertEquals(content, overlay.panel)
        assertNull(overlay.grid)
        assertTrue(overlay.messageBounds.left >= content.left)
        assertTrue(overlay.messageBounds.right <= content.right)
        assertEquals("Viewport is too small for the target terminal", overlay.unsupportedMessage)
    }
}

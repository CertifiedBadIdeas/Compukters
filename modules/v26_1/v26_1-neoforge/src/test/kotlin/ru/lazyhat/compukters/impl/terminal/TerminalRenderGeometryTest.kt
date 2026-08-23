/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.impl.terminal

import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalRenderGeometryTest {
    @Test
    fun `compact panel keeps one fixed 51 by 19 grid centered after resize`() {
        val small = TerminalRenderGeometry(640, 360)
        val large = TerminalRenderGeometry(1_280, 720)

        assertEquals(51, small.columns)
        assertEquals(19, small.rows)
        assertEquals(306, small.grid.width)
        assertEquals(171, small.grid.height)
        assertEquals(322, small.panel.width)
        assertEquals(197, small.panel.height)
        assertEquals(TerminalRect(159, 81, 481, 278), small.panel)
        assertEquals(TerminalRect(167, 99, 473, 270), small.grid)
        assertEquals(TerminalRect(167, 99, 173, 108), small.cell(0, 0))
        assertEquals(
            TerminalRect(467, 261, 473, 270),
            small.cell(50, 18),
        )
        assertEquals(small.panel.width, large.panel.width)
        assertEquals(small.panel.height, large.panel.height)
        assertEquals(large.cell(7, 11), large.glyphClip(7, 11))
    }

    @Test
    fun `small viewport preserves scale and centers the overflowing panel`() {
        val geometry = TerminalRenderGeometry(300, 180)

        assertEquals(TerminalRect(-11, -8, 311, 189), geometry.panel)
        assertEquals(TerminalRect(-3, 10, 303, 181), geometry.grid)
        assertEquals(-3, geometry.titleX)
        assertEquals(-3, geometry.titleY)
    }

    @Test
    fun `palette mapping and cursor projection are exact and pure`() {
        val geometry = TerminalRenderGeometry(640, 360)
        assertEquals(
            listOf(
                0xFF000000.toInt(),
                0xFFAA0000.toInt(),
                0xFF00AA00.toInt(),
                0xFFAA5500.toInt(),
                0xFF0000AA.toInt(),
                0xFFAA00AA.toInt(),
                0xFF00AAAA.toInt(),
                0xFFAAAAAA.toInt(),
                0xFF555555.toInt(),
                0xFFFF5555.toInt(),
                0xFF55FF55.toInt(),
                0xFFFFFF55.toInt(),
                0xFF5555FF.toInt(),
                0xFFFF55FF.toInt(),
                0xFF55FFFF.toInt(),
                0xFFFFFFFF.toInt(),
            ),
            (0..15).map(TerminalRenderGeometry::paletteColor),
        )
        val cursor = geometry.cursor(TerminalPosition(2, 3))
        val cell = geometry.cell(2, 3)
        assertEquals(TerminalRect(cell.left, cell.bottom - 1, cell.right, cell.bottom), cursor)
        assertTrue(TerminalRenderGeometry.drawCursor(authoritativeVisible = true, milliseconds = 0))
        assertFalse(TerminalRenderGeometry.drawCursor(authoritativeVisible = true, milliseconds = 500))
        assertFalse(TerminalRenderGeometry.drawCursor(authoritativeVisible = false, milliseconds = 0))
    }
}

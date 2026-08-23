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
    fun `every terminal coordinate maps to one fixed cell after resize`() {
        val small = TerminalRenderGeometry(640, 360)
        val large = TerminalRenderGeometry(1_280, 720)

        assertEquals(51, small.columns)
        assertEquals(19, small.rows)
        assertEquals(small.gridWidth, large.gridWidth)
        assertEquals(small.gridHeight, large.gridHeight)
        assertEquals(TerminalRect(small.originX, small.originY, small.originX + 9, small.originY + 10), small.cell(0, 0))
        assertEquals(
            TerminalRect(small.originX + 50 * 9, small.originY + 18 * 10, small.originX + 51 * 9, small.originY + 19 * 10),
            small.cell(50, 18),
        )
        assertEquals(large.cell(7, 11), large.glyphClip(7, 11))
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
        assertEquals(TerminalRect(cell.left, cell.bottom - 2, cell.right, cell.bottom), cursor)
        assertTrue(TerminalRenderGeometry.drawCursor(authoritativeVisible = true, milliseconds = 0))
        assertFalse(TerminalRenderGeometry.drawCursor(authoritativeVisible = true, milliseconds = 500))
        assertFalse(TerminalRenderGeometry.drawCursor(authoritativeVisible = false, milliseconds = 0))
    }
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import ru.lazyhat.compukters.impl.terminal.TerminalFontProfile

object DisplayTextLayout {
    fun forEachGlyph(
        rows: List<String>,
        draw: (x: Int, y: Int, codePoint: Int) -> Unit,
    ) {
        val gridWidth = DisplayBuffer.WIDTH * TerminalFontProfile.cellWidth
        val left = -gridWidth / 2
        val top = -(DisplayBuffer.HEIGHT * TerminalFontProfile.cellHeight) / 2
        rows.forEachIndexed { y, row ->
            var offset = 0
            var x = 0
            while (offset < row.length) {
                val codePoint = row.codePointAt(offset)
                if (codePoint != ' '.code) {
                    draw(
                        left + x * TerminalFontProfile.cellWidth,
                        top + y * TerminalFontProfile.cellHeight + TerminalFontProfile.glyphDrawOffsetY,
                        TerminalFontProfile.renderCodePoint(codePoint),
                    )
                }
                offset += Character.charCount(codePoint)
                x++
            }
        }
    }
}

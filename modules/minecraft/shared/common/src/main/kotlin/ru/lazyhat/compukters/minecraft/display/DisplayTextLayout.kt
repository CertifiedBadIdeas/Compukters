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
        profile: TerminalFontProfile,
        draw: (x: Int, y: Int, codePoint: Int) -> Unit,
    ) {
        val left = -DisplayBuffer.WIDTH * profile.cellWidth / 2
        val top = -DisplayBuffer.HEIGHT * profile.cellHeight / 2
        rows.forEachIndexed { y, row ->
            var offset = 0
            var x = 0
            while (offset < row.length) {
                val codePoint = row.codePointAt(offset)
                if (codePoint != ' '.code) {
                    draw(left + x * profile.cellWidth, top + y * profile.cellHeight, profile.renderCodePoint(codePoint))
                }
                offset += Character.charCount(codePoint)
                x++
            }
        }
    }
}

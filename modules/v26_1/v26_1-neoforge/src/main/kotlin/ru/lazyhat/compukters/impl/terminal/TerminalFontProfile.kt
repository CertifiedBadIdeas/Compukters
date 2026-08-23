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

import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier

class TerminalFontProfile private constructor(
    val id: String,
    val fontDescription: FontDescription.Resource,
    val cellWidth: Int,
    val cellHeight: Int,
    val ascent: Int,
    supportedCodePoints: IntArray,
    val replacementCodePoint: Int,
) {
    private val supportedCodePoints = supportedCodePoints.copyOf()

    val glyphDrawOffsetY: Int = ascent - MINECRAFT_TEXT_BASELINE

    init {
        require(cellWidth > 0 && cellHeight > 0) { "terminal font cell must be positive" }
        require(ascent in 1..cellHeight) { "terminal font ascent must fit the cell" }
        require(this.supportedCodePoints.contentEquals(this.supportedCodePoints.sortedArray())) {
            "terminal font coverage must be sorted"
        }
        require(
            (1 until this.supportedCodePoints.size).none { index ->
                this.supportedCodePoints[index - 1] == this.supportedCodePoints[index]
            },
        ) {
            "terminal font coverage must not contain duplicates"
        }
        require(this.supportedCodePoints.binarySearch(replacementCodePoint) >= 0) {
            "terminal font coverage must contain its replacement glyph"
        }
    }

    fun supports(codePoint: Int): Boolean = supportedCodePoints.binarySearch(codePoint) >= 0

    fun renderCodePoint(codePoint: Int): Int = if (supports(codePoint)) codePoint else replacementCodePoint

    companion object {
        private const val MINECRAFT_TEXT_BASELINE = 7

        val DEFAULT =
            TerminalFontProfile(
                id = "cozette",
                fontDescription =
                    FontDescription.Resource(
                        Identifier.fromNamespaceAndPath("compukters", "terminal/cozette"),
                    ),
                cellWidth = 6,
                cellHeight = 13,
                ascent = 10,
                supportedCodePoints = COZETTE_SUPPORTED_CODE_POINTS,
                replacementCodePoint = 0xFFFD,
            )
    }
}

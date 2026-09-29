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
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.impl.terminal

import ru.lazyhat.compukters.impl.font.JetBrainsMonoFont

/** The single terminal/display face; geometry remains a fixed character grid. */
object TerminalFontProfile {
    val id = JetBrainsMonoFont.ID
    val cellWidth = JetBrainsMonoFont.CELL_WIDTH
    val cellHeight = JetBrainsMonoFont.CELL_HEIGHT
    val ascent = JetBrainsMonoFont.BASELINE
    val replacementCodePoint = 0xFFFD

    // Minecraft positions text from a baseline of 7, not the top of the grid cell.
    val glyphDrawOffsetY = ascent - 7

    fun supports(codePoint: Int): Boolean = JETBRAINS_MONO_SUPPORTED_CODE_POINTS.binarySearch(codePoint) >= 0

    fun renderCodePoint(codePoint: Int): Int = if (supports(codePoint)) codePoint else replacementCodePoint
}

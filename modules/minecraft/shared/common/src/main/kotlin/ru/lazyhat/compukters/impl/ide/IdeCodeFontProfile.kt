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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.impl.font.JetBrainsMonoFont

/** Shared metrics for drawing, selection and hit testing; aligned with the bundled terminal face. */
class IdeCodeFontProfile private constructor(
    val id: String,
    val cellWidth: Int,
    val cellHeight: Int,
    val baseline: Int,
    val size: Float,
    val oversample: Float,
) {
    val glyphDrawOffsetY: Int = baseline - MINECRAFT_TEXT_BASELINE

    companion object {
        private const val MINECRAFT_TEXT_BASELINE = 7

        val DEFAULT =
            IdeCodeFontProfile(
                id = JetBrainsMonoFont.ID,
                cellWidth = JetBrainsMonoFont.CELL_WIDTH,
                cellHeight = JetBrainsMonoFont.CELL_HEIGHT,
                baseline = JetBrainsMonoFont.BASELINE,
                size = JetBrainsMonoFont.SIZE,
                oversample = JetBrainsMonoFont.OVERSAMPLE,
            )
    }
}

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

/** Shared metrics for drawing, selection and hit testing; independent of the terminal font setting. */
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

        // JetBrains Mono's advance is 600/1000 em: size 10 gives an exact six-pixel cell.
        val DEFAULT =
            IdeCodeFontProfile(
                id = "jetbrains_mono",
                cellWidth = 6,
                cellHeight = 13,
                baseline = 10,
                size = 10f,
                oversample = 4f,
            )
    }
}

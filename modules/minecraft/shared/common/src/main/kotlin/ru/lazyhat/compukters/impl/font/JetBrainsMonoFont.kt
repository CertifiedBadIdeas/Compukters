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

package ru.lazyhat.compukters.impl.font

/** Metrics of the single bundled JetBrains Mono NL Regular face used by editor and terminal grids. */
object JetBrainsMonoFont {
    const val ID = "jetbrains_mono"
    const val CELL_WIDTH = 6
    const val CELL_HEIGHT = 13
    const val BASELINE = 10

    // The upstream advance is 600/1000 em, giving an exact six-pixel cell at size 10.
    const val SIZE = 10f
    const val OVERSAMPLE = 4f
}

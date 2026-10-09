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

enum class IdeUiScale(
    val label: String,
    val fixedScale: Int?,
    val action: IdeHitAction,
) {
    AUTO("Auto", null, IdeHitAction.ScaleAuto),
    TWO("2", 2, IdeHitAction.ScaleTwo),
    THREE("3", 3, IdeHitAction.ScaleThree),
}

object IdeUiScaleControl {
    const val WIDTH = 132

    fun options(viewport: IdeRect): List<Pair<IdeUiScale, IdeRect>> {
        if (viewport.width < WIDTH || viewport.height < 24) return emptyList()
        val left = viewport.right - WIDTH + 36
        return IdeUiScale.entries.mapIndexed { index, mode ->
            mode to IdeRect(left + index * 32, viewport.top + 3, left + (index + 1) * 32 - 2, viewport.top + 21)
        }
    }

    fun hit(
        viewport: IdeRect,
        x: Double,
        y: Double,
    ): IdeUiScale? =
        options(viewport).firstOrNull { (_, bounds) -> x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom }?.first
}

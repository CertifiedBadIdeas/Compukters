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

/** A control's visual bounds and interaction target are created together. */
data class IdeControlDraw(
    val panel: IdePanelDraw,
    val target: IdeHitTarget,
    val fills: List<IdeFillDraw> = emptyList(),
)

enum class IdeControlStyle { Standard, Neutral, Primary }

object IdeControls {
    fun target(
        target: IdeHitTarget,
        style: IdeControlStyle = IdeControlStyle.Neutral,
    ): IdeControlDraw =
        IdeControlDraw(
            IdePanelDraw(
                IdePanelKind.Control,
                target.bounds,
                when {
                    style == IdeControlStyle.Primary && target.enabled -> IdeColors.SELECTION
                    style == IdeControlStyle.Neutral && target.selected -> IdeColors.SELECTION
                    style == IdeControlStyle.Neutral -> if (target.enabled) IdeColors.CONTROL else IdeColors.PANEL_ALT
                    target.selected -> IdeColors.ACCENT
                    target.enabled -> IdeColors.BORDER
                    else -> IdeColors.PANEL_ALT
                },
                target.zIndex - BACKGROUND_OFFSET,
            ),
            target,
            if (style == IdeControlStyle.Standard) emptyList() else border(target),
        )

    private fun border(target: IdeHitTarget): List<IdeFillDraw> {
        val bounds = target.bounds
        if (bounds.width <= 0 || bounds.height <= 0) return emptyList()
        val color = if (target.enabled) IdeColors.CONTROL_BORDER else IdeColors.BORDER
        val z = target.zIndex - BACKGROUND_OFFSET + 1
        return listOf(
            IdeRect(bounds.left, bounds.top, bounds.right, bounds.top + 1),
            IdeRect(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom),
            IdeRect(bounds.left, bounds.top, bounds.left + 1, bounds.bottom),
            IdeRect(bounds.right - 1, bounds.top, bounds.right, bounds.bottom),
        ).map { IdeFillDraw(IdeFillKind.ControlBorder, it, color, z) }
    }

    const val BACKGROUND_OFFSET = 20
}

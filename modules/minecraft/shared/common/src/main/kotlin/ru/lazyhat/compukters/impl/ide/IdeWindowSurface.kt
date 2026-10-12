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

import kotlin.math.ceil
import kotlin.math.sqrt

/** Shared floating-window chrome; rectangular content stays inset from the rounded edge. */
internal object IdeWindowSurface {
    const val RADIUS = 4
    const val PADDING = RADIUS

    fun isWindow(kind: IdePanelKind): Boolean =
        kind in
            setOf(
                IdePanelKind.Dialog,
                IdePanelKind.PeripheralSelection,
                IdePanelKind.ProjectSelection,
                IdePanelKind.ProjectSwitcher,
                IdePanelKind.Tooltip,
            )

    fun paint(
        canvas: IdeCanvas,
        bounds: IdeRect,
        color: Int,
        border: Int = IdeColors.WINDOW_BORDER,
    ) {
        roundedFill(canvas, bounds, border)
        if (bounds.width > 2 && bounds.height > 2) {
            roundedFill(canvas, IdeRect(bounds.left + 1, bounds.top + 1, bounds.right - 1, bounds.bottom - 1), color, RADIUS - 1)
        }
    }

    fun roundedFill(
        canvas: IdeCanvas,
        bounds: IdeRect,
        color: Int,
        radius: Int = RADIUS,
    ) {
        val r = minOf(radius, bounds.width / 2, bounds.height / 2).coerceAtLeast(0)
        if (bounds.width <= 0 || bounds.height <= 0) return
        if (r == 0) {
            canvas.fill(bounds, color)
            return
        }
        for (row in 0 until r) {
            val distance = r - row - 0.5
            val inset = ceil(r - sqrt(r * r - distance * distance) - 0.5).toInt().coerceAtLeast(0)
            canvas.fill(IdeRect(bounds.left + inset, bounds.top + row, bounds.right - inset, bounds.top + row + 1), color)
            canvas.fill(IdeRect(bounds.left + inset, bounds.bottom - row - 1, bounds.right - inset, bounds.bottom - row), color)
        }
        if (bounds.height > 2 * r) canvas.fill(IdeRect(bounds.left, bounds.top + r, bounds.right, bounds.bottom - r), color)
    }

    fun shadows(
        bounds: IdeRect,
        z: Int,
    ): List<IdeFillDraw> =
        (6 downTo 1).map { spread ->
            IdeFillDraw(
                IdeFillKind.Shadow,
                IdeRect(
                    bounds.left - spread,
                    bounds.top - spread + 2,
                    bounds.right + spread,
                    bounds.bottom + spread + 2,
                ),
                0x08000000,
                z,
            )
        }
}

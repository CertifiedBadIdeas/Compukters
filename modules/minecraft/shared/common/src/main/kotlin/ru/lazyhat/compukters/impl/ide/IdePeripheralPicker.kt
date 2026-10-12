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

import ru.lazyhat.compukters.impl.ide.target.IdeNamedPeripheral

data class IdePeripheralPickerView(
    val entries: List<IdeNamedPeripheral> = emptyList(),
    val message: String? = null,
    val loading: Boolean = false,
    val firstRow: Int = 0,
    val writable: Boolean = false,
)

internal object IdePeripheralPicker {
    const val ROW_HEIGHT = 40

    fun bounds(area: IdeRect): IdeRect {
        val width = minOf(380, area.width - 8)
        val height = minOf(360, area.height - 8)
        return IdeRect(area.right - width - 4, area.top + 4, area.right - 4, area.top + height + 4)
    }

    fun visibleRows(area: IdeRect): Int = ((bounds(area).height - 64) / ROW_HEIGHT).coerceAtLeast(0)

    fun draw(
        output: IdeDrawBuilder,
        area: IdeRect,
        view: IdePeripheralPickerView,
    ) = with(output) {
        val bounds = bounds(area)
        val z = IdeDrawLayers.Z_PROJECT_SWITCHER
        val controlZ = IdeDrawLayers.Z_PROJECT_SWITCHER_TARGET
        val textZ = IdeDrawLayers.Z_PROJECT_SWITCHER_TEXT
        panel(IdePanelKind.PeripheralSelection, bounds, IdeColors.PANEL_ALT, z)
        ui(IdeTextKind.Header, "Named peripherals", bounds.left + 8, bounds.top + 8, z = textZ)
        val close = IdeRect(bounds.right - 24, bounds.top + 4, bounds.right - 4, bounds.top + 24)
        target(IdeHitAction.PeripheralClose, close, true, "Close peripherals", z = controlZ, style = IdeControlStyle.Neutral)
        centeredUi(IdeTextKind.Toolbar, "×", close.left + (close.width - uiWidth("×")) / 2, close, z = textZ)
        val refresh = IdeRect(bounds.left + 6, bounds.top + 28, bounds.left + 80, bounds.top + 48)
        target(IdeHitAction.PeripheralRefresh, refresh, !view.loading, "Refresh peripherals", z = controlZ, style = IdeControlStyle.Neutral)
        centeredUi(IdeTextKind.Toolbar, "Refresh", refresh.left + (refresh.width - uiWidth("Refresh")) / 2, refresh, z = textZ)
        val message =
            view.message
                ?: if (view.loading) {
                    "Loading…"
                } else if (view.entries.isEmpty()) {
                    "No named peripherals"
                } else if (!view.writable) {
                    "Open a writable Kotlin source"
                } else {
                    null
                }
        message?.let {
            ui(
                IdeTextKind.Status,
                fitUi(it, bounds.width - 100),
                refresh.right + 6,
                refresh.top + 4,
                IdeColors.MUTED,
                bounds,
                textZ,
            )
        }
        val count = visibleRows(area)
        val first = view.firstRow.coerceIn(0, (view.entries.size - count).coerceAtLeast(0))
        view.entries.drop(first).take(count).forEachIndexed { index, entry ->
            val top = bounds.top + 56 + index * ROW_HEIGHT
            val row = IdeRect(bounds.left + 4, top, bounds.right - 4, top + ROW_HEIGHT - 2)
            target(
                IdeHitAction.PeripheralChoice,
                row,
                view.writable && !entry.duplicate && !view.loading,
                if (entry.duplicate) "Ambiguous peripheral name" else "Insert constant for ${entry.name}",
                z = controlZ,
                choiceIndex = first + index,
            )
            icon(
                IdeIconDraw(
                    IdeIconKind.Resolve,
                    IdeRect(row.left + 3, top + 3, row.left + 35, top + 35),
                    IdeColors.TEXT,
                    textZ,
                    entry.itemId,
                ),
            )
            ui(IdeTextKind.ProjectChoice, fitUi(entry.name, row.width - 46), row.left + 42, top + 5, clip = row, z = textZ)
            ui(
                IdeTextKind.ProjectChoice,
                fitUi(if (entry.duplicate) "Duplicate name" else entry.itemId, row.width - 46),
                row.left + 42,
                top + 19,
                IdeColors.MUTED,
                row,
                textZ,
            )
        }
    }
}

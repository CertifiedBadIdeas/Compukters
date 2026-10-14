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
    val selectedIndex: Int? = null,
    val lookupIndex: Int = 0,
)

internal object IdePeripheralPicker {
    const val ROW_HEIGHT = 40
    private const val DETAILS_HEIGHT = 132

    fun selected(view: IdePeripheralPickerView): IdeNamedPeripheral? = view.entries.getOrNull(view.selectedIndex ?: -1)

    fun lookup(view: IdePeripheralPickerView): String? {
        val entry = selected(view)?.takeUnless { it.duplicate } ?: return null
        val type = entry.lookupTypes.getOrNull(view.lookupIndex) ?: return null
        return "$type.named(${ru.lazyhat.compukters.ide.editor.PeripheralConstantInsertion.quote(entry.name)})"
    }

    fun bounds(area: IdeRect): IdeRect {
        val width = minOf(440, area.width - 8)
        val height = minOf(420, area.height - 8)
        return IdeRect(area.right - width - 4, area.top + 4, area.right - 4, area.top + height + 4)
    }

    fun visibleRows(area: IdeRect): Int = ((bounds(area).height - 64 - DETAILS_HEIGHT) / ROW_HEIGHT).coerceAtLeast(0)

    fun draw(
        output: IdeDrawBuilder,
        area: IdeRect,
        view: IdePeripheralPickerView,
    ): Unit =
        with(output) {
            val bounds = bounds(area)
            if (bounds.width < 240 || bounds.height < 200) return
            val z = IdeDrawLayers.Z_PROJECT_SWITCHER
            val controlZ = IdeDrawLayers.Z_PROJECT_SWITCHER_TARGET
            val textZ = IdeDrawLayers.Z_PROJECT_SWITCHER_TEXT
            panel(IdePanelKind.PeripheralSelection, bounds, IdeColors.PANEL_ALT, z)
            ui(IdeTextKind.Header, "Named peripherals", bounds.left + 8, bounds.top + 8, z = textZ)
            val close = IdeRect(bounds.right - 24, bounds.top + 4, bounds.right - 4, bounds.top + 24)
            target(IdeHitAction.PeripheralClose, close, true, "Close peripherals", z = controlZ, style = IdeControlStyle.Neutral)
            centeredUi(IdeTextKind.Toolbar, "×", close.left + (close.width - uiWidth("×")) / 2, close, z = textZ)
            val refresh = IdeRect(bounds.left + 6, bounds.top + 28, bounds.left + 80, bounds.top + 48)
            target(
                IdeHitAction.PeripheralRefresh,
                refresh,
                !view.loading,
                "Refresh peripherals",
                z = controlZ,
                style = IdeControlStyle.Neutral,
            )
            centeredUi(IdeTextKind.Toolbar, "Refresh", refresh.left + (refresh.width - uiWidth("Refresh")) / 2, refresh, z = textZ)
            val message =
                view.message
                    ?: if (view.loading) {
                        "Loading…"
                    } else if (view.entries.isEmpty()) {
                        "No named peripherals"
                    } else if (view.selectedIndex == null) {
                        "Select a device"
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
                    !view.loading,
                    "Select ${entry.name}; double-click to insert a name constant",
                    selected = view.selectedIndex == first + index,
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
            val entry = selected(view)
            val detailsTop = bounds.bottom - DETAILS_HEIGHT
            fill(
                IdeFillDraw(
                    IdeFillKind.Border,
                    IdeRect(bounds.left + 8, detailsTop, bounds.right - 8, detailsTop + 1),
                    IdeColors.BORDER,
                    textZ,
                ),
            )
            ui(
                IdeTextKind.Header,
                fitUi(entry?.name ?: "Device actions", bounds.width - 16),
                bounds.left + 8,
                detailsTop + 8,
                clip = bounds,
                z = textZ,
            )

            fun button(
                action: IdeHitAction,
                label: String,
                rect: IdeRect,
                enabled: Boolean,
                tooltip: String = label,
            ) {
                target(action, rect, enabled && !view.loading, tooltip, z = controlZ, style = IdeControlStyle.Neutral)
                centeredUi(
                    IdeTextKind.Toolbar,
                    fitUi(label, rect.width - 8),
                    rect.left + (rect.width - uiWidth(fitUi(label, rect.width - 8))) / 2,
                    rect,
                    z = textZ,
                )
            }
            val api = IdeRect(bounds.left + 8, detailsTop + 26, bounds.right - 8, detailsTop + 48)
            val type = entry?.lookupTypes?.getOrNull(view.lookupIndex)
            button(
                IdeHitAction.PeripheralLookupType,
                type?.let { "API: ${it.substringAfterLast('.')}" } ?: "Lookup unavailable",
                api,
                (entry?.lookupTypes?.size ?: 0) > 1,
                "Choose lookup API",
            )
            val hint =
                when {
                    entry == null -> "Select a device to rename or copy its name"
                    entry.duplicate -> "Duplicate name: rename before using a lookup"
                    entry.reference == null -> "Bind the device to a network to rename it"
                    else -> "Renaming does not update names in source code"
                }
            ui(IdeTextKind.Status, fitUi(hint, bounds.width - 16), bounds.left + 8, detailsTop + 54, IdeColors.MUTED, bounds, textZ)
            val width = (bounds.width - 20) / 2
            val left = bounds.left + 8
            val right = bounds.left + 12 + width
            val copyTop = bounds.bottom - 54
            val editTop = bounds.bottom - 30
            button(
                IdeHitAction.PeripheralCopyName,
                "Copy name",
                IdeRect(left, copyTop, left + width, copyTop + 22),
                entry != null,
                "Copy the exact device name in the network (Ctrl+C)",
            )
            button(
                IdeHitAction.PeripheralCopyLookup,
                "Copy lookup",
                IdeRect(right, copyTop, bounds.right - 8, copyTop + 22),
                lookup(view) != null,
            )
            button(
                IdeHitAction.PeripheralInsert,
                "Insert constant",
                IdeRect(left, editTop, left + width, editTop + 22),
                entry != null && !entry.duplicate && view.writable,
                "Insert a name constant into the writable Kotlin file",
            )
            button(
                IdeHitAction.PeripheralRename,
                "Rename",
                IdeRect(right, editTop, bounds.right - 8, editTop + 22),
                entry?.reference != null,
            )
        }
}

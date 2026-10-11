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

import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_DIALOG
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_DIALOG_SCRIM
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_DIALOG_TARGET
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_DIALOG_TEXT

data class IdeStatusDetailsView(
    val bounds: IdeRect,
    val body: IdeRect,
    val copy: IdeRect,
    val close: IdeRect,
    val rows: List<String>,
    val firstRow: Int,
    val totalRows: Int,
)

/** Read-only UI state; its captured messages can be copied without truncation. */
internal class IdeStatusDetails {
    private var messages = emptyList<String>()
    private var project: String? = null
    private var firstRow = 0
    val visible: Boolean get() = project != null

    fun open(
        project: String,
        messages: List<String>,
    ) {
        this.project = project
        this.messages = messages.toList()
        firstRow = 0
    }

    fun synchronize(project: String?) {
        if (this.project != project) close()
    }

    fun close() {
        project = null
        messages = emptyList()
        firstRow = 0
    }

    fun text(): String = messages.joinToString("\n\n")

    fun view(panel: IdeRect): IdeStatusDetailsView? {
        if (!visible) return null
        val width = minOf(600, panel.width - 24).coerceAtLeast(80)
        val columns = ((width - 24) / IdeCodeFontProfile.DEFAULT.cellWidth).coerceAtLeast(1)
        val rows =
            text()
                .lineSequence()
                .flatMap { line ->
                    val points = line.codePoints().toArray()
                    if (points.isEmpty()) {
                        sequenceOf("")
                    } else {
                        sequence {
                            for (start in points.indices step columns) yield(String(points, start, minOf(columns, points.size - start)))
                        }
                    }
                }.toList()
        val height = minOf(380, panel.height - 24, maxOf(132, rows.size * 12 + 74)).coerceAtLeast(80)
        val left = panel.left + (panel.width - width) / 2
        val top = panel.top + (panel.height - height) / 2
        val bounds = IdeRect(left, top, left + width, top + height)
        val body = IdeRect(left + 12, top + 36, bounds.right - 12, bounds.bottom - 38)
        val visibleRows = (body.height / 12).coerceAtLeast(1)
        firstRow = firstRow.coerceIn(0, (rows.size - visibleRows).coerceAtLeast(0))
        return IdeStatusDetailsView(
            bounds,
            body,
            IdeRect(left + 12, bounds.bottom - 28, left + 90, bounds.bottom - 8),
            IdeRect(bounds.right - 72, bounds.bottom - 28, bounds.right - 12, bounds.bottom - 8),
            rows.drop(firstRow).take(visibleRows),
            firstRow,
            rows.size,
        )
    }

    fun scroll(
        rows: Int,
        panel: IdeRect,
    ) {
        firstRow = (firstRow + rows).coerceAtLeast(0)
        view(panel)
    }

    fun press(
        x: Double,
        y: Double,
        panel: IdeRect,
        copy: (String) -> Unit,
    ) {
        val view = view(panel) ?: return
        when {
            view.close.containsPoint(x, y) -> close()
            view.copy.containsPoint(x, y) -> copy(text())
        }
    }

    fun key(
        event: IdeKeyInput,
        panel: IdeRect,
        copy: (String) -> Unit,
    ) {
        val page = ((view(panel)?.body?.height ?: 12) / 12).coerceAtLeast(1)
        when {
            event.key == IdeKeyCode.ESCAPE || event.key == IdeKeyCode.ENTER -> {
                close()
            }

            event.key == IdeKeyCode.C && event.modifiers and IdeModifier.CONTROL != 0 -> {
                copy(text())
            }

            event.key == IdeKeyCode.UP -> {
                scroll(-1, panel)
            }

            event.key == IdeKeyCode.DOWN -> {
                scroll(1, panel)
            }

            event.key == IdeKeyCode.PAGE_UP -> {
                scroll(-page, panel)
            }

            event.key == IdeKeyCode.PAGE_DOWN -> {
                scroll(page, panel)
            }

            event.key == IdeKeyCode.HOME -> {
                firstRow = 0
            }

            event.key == IdeKeyCode.END -> {
                firstRow = Int.MAX_VALUE
                view(panel)
            }
        }
    }
}

internal object IdeStatusDetailsRenderer {
    fun draw(
        output: IdeDrawBuilder,
        panel: IdeRect,
        view: IdeStatusDetailsView,
    ) = with(output) {
        disableTargets()
        fill(IdeFillDraw(IdeFillKind.DialogScrim, panel, IdeColors.DIM, Z_DIALOG_SCRIM))
        output.panel(IdePanelKind.Dialog, view.bounds, IdeColors.PANEL_ALT, Z_DIALOG)
        ui(IdeTextKind.Dialog, "Status details", view.bounds.left + 12, view.bounds.top + 12, z = Z_DIALOG_TEXT)
        ui(
            IdeTextKind.Dialog,
            "Captured on opening · scroll for more",
            view.bounds.left + 12,
            view.bounds.top + 24,
            IdeColors.MUTED,
            view.bounds,
            z = Z_DIALOG_TEXT,
        )
        view.rows.forEachIndexed { index, row ->
            ui(IdeTextKind.Dialog, row, view.body.left, view.body.top + index * 12, clip = view.body, z = Z_DIALOG_TEXT)
        }
        target(
            IdeHitAction.StatusDetails,
            view.copy,
            true,
            "Copy all details (Ctrl+C)",
            z = Z_DIALOG_TARGET,
            style = IdeControlStyle.Neutral,
        )
        target(IdeHitAction.Dismiss, view.close, true, "Close (Escape)", z = Z_DIALOG_TARGET, style = IdeControlStyle.Neutral)
        ui(IdeTextKind.Dialog, "Copy all", view.copy.left + 8, view.copy.top + 5, z = Z_DIALOG_TARGET + 1)
        ui(IdeTextKind.Dialog, "Close", view.close.left + 8, view.close.top + 5, z = Z_DIALOG_TARGET + 1)
        val progress = "${view.firstRow + 1}–${view.firstRow + view.rows.size} / ${view.totalRows}"
        val progressBounds = IdeRect(view.copy.right + 8, view.close.top, view.close.left - 8, view.close.bottom)
        ui(
            IdeTextKind.Dialog,
            fitUi(progress, progressBounds.width),
            progressBounds.left,
            progressBounds.top + 5,
            IdeColors.MUTED,
            progressBounds,
            z = Z_DIALOG_TARGET + 1,
        )
    }
}

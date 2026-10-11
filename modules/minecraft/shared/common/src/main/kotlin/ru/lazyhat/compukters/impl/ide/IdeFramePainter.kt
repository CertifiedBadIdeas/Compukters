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

import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalState
import ru.lazyhat.compukters.impl.terminal.TerminalGridGeometry
import ru.lazyhat.compukters.impl.terminal.TerminalSurfaceStyle
import ru.lazyhat.compukters.impl.ui.CompuktersUiViewport
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState

interface IdeCanvas {
    fun fill(
        bounds: IdeRect,
        color: Int,
    )

    fun icon(draw: IdeIconDraw)

    fun text(draw: IdeTextDraw)

    fun terminal(
        state: TerminalState,
        geometry: TerminalGridGeometry,
        nowMillis: Long,
    )
}

data class IdeScreenFrame(
    val viewport: CompuktersUiViewport,
    val geometry: IdeRenderGeometry,
    val model: IdeDrawModel,
    val terminalState: IdeTargetTerminalState,
    val terminalVisible: Boolean,
    val terminalFocused: Boolean,
)

class IdeRenderOperation(
    val zIndex: Int,
    val draw: () -> Unit,
)

fun executeIdeRenderOperations(
    operations: MutableList<IdeRenderOperation>,
    terminalVisible: Boolean,
    renderTerminal: () -> Unit,
) {
    if (terminalVisible) operations += IdeRenderOperation(IDE_TERMINAL_OVERLAY_Z, renderTerminal)
    operations.sortBy(IdeRenderOperation::zIndex)
    operations.forEach { it.draw() }
}

object IdeFramePainter {
    fun paint(
        frame: IdeScreenFrame,
        canvas: IdeCanvas,
        nowMillis: Long,
    ) {
        val model = frame.model
        val operations = mutableListOf<IdeRenderOperation>()
        model.panels.forEach { draw ->
            operations +=
                IdeRenderOperation(draw.zIndex) {
                    if (IdeWindowSurface.isWindow(draw.kind)) {
                        IdeWindowSurface.paint(canvas, draw.bounds, draw.color)
                    } else {
                        canvas.fill(draw.bounds, draw.color)
                    }
                }
        }
        model.fills.forEach { draw ->
            operations +=
                IdeRenderOperation(draw.zIndex) {
                    if (draw.kind == IdeFillKind.Shadow) {
                        IdeWindowSurface.roundedFill(canvas, draw.bounds, draw.color)
                    } else {
                        canvas.fill(draw.bounds, draw.color)
                    }
                }
        }
        model.icons.forEach { draw -> operations += IdeRenderOperation(draw.zIndex) { canvas.icon(draw) } }
        model.text.forEach { draw -> operations += IdeRenderOperation(draw.zIndex) { canvas.text(draw) } }
        executeIdeRenderOperations(operations, frame.terminalVisible) { terminalOverlay(frame, canvas, nowMillis) }
    }

    private fun terminalOverlay(
        frame: IdeScreenFrame,
        canvas: IdeCanvas,
        nowMillis: Long,
    ) {
        val overlay = IdeTerminalOverlayGeometry.compute(frame.geometry.content)
        IdeWindowSurface.shadows(overlay.panel, IDE_TERMINAL_OVERLAY_Z).forEach { shadow ->
            IdeWindowSurface.roundedFill(canvas, shadow.bounds, shadow.color)
        }
        IdeWindowSurface.paint(
            canvas,
            overlay.panel,
            TerminalSurfaceStyle.BACKGROUND_COLOR,
            if (frame.terminalFocused) IdeColors.ACCENT else IdeColors.WINDOW_BORDER,
        )
        val labels = IdeDrawBuilder(frame.geometry.font)
        if (!overlay.supported) {
            labels.ui(
                IdeTextKind.Status,
                overlay.unsupportedMessage,
                overlay.messageBounds.left,
                overlay.messageBounds.top,
                IdeColors.ERROR,
                overlay.messageBounds,
            )
            labels.build().text.forEach(canvas::text)
            return
        }
        labels.centeredUi(
            IdeTextKind.Status,
            labels.fitUi(terminalOverlayTitle(frame.terminalState), overlay.title.width - 10),
            overlay.title.left + 5,
            overlay.title,
            if (frame.terminalState is IdeTargetTerminalState.Failed) IdeColors.ERROR else IdeColors.TEXT,
            IDE_TERMINAL_OVERLAY_Z,
        )
        labels.build().text.forEach(canvas::text)
        val replica =
            when (val state = frame.terminalState) {
                is IdeTargetTerminalState.Active -> state.replica
                is IdeTargetTerminalState.Resyncing -> state.replica
                else -> null
            }
        overlay.grid?.let { grid ->
            replica?.let { canvas.terminal(it.state, TerminalGridGeometry(grid.left, grid.top), nowMillis) }
        }
    }
}

private const val IDE_TERMINAL_OVERLAY_Z = 80

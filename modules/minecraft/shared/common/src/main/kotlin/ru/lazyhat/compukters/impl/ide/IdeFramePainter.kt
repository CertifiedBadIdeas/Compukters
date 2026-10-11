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
        model.panels.forEach { draw -> operations += IdeRenderOperation(draw.zIndex) { canvas.fill(draw.bounds, draw.color) } }
        model.fills.forEach { draw -> operations += IdeRenderOperation(draw.zIndex) { canvas.fill(draw.bounds, draw.color) } }
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
        canvas.fill(overlay.shadow, 0x66000000)
        canvas.fill(overlay.panel, 0xFF27323A.toInt())
        canvas.fill(
            IdeRect(overlay.panel.left + 1, overlay.panel.top + 1, overlay.panel.right - 1, overlay.panel.bottom - 1),
            0xFF101418.toInt(),
        )
        if (!overlay.supported) {
            text(
                canvas,
                overlay.unsupportedMessage,
                overlay.messageBounds.left,
                overlay.messageBounds.top,
                0xFFFF6B6B.toInt(),
                overlay.messageBounds,
            )
            return
        }
        text(
            canvas,
            terminalOverlayTitle(frame.terminalState),
            overlay.title.left + 5,
            overlay.title.top + 5,
            if (frame.terminalFocused) 0xFF38D6B4.toInt() else 0xFFF2F4F8.toInt(),
            overlay.title,
        )
        val replica =
            when (val state = frame.terminalState) {
                is IdeTargetTerminalState.Active -> state.replica
                is IdeTargetTerminalState.Resyncing -> state.replica
                else -> null
            }
        overlay.grid?.let { grid ->
            canvas.fill(grid, 0xFF000000.toInt())
            replica?.let { canvas.terminal(it.state, TerminalGridGeometry(grid.left, grid.top), nowMillis) }
        }
    }

    private fun text(
        canvas: IdeCanvas,
        value: String,
        x: Int,
        y: Int,
        color: Int,
        clip: IdeRect,
    ) {
        canvas.text(
            IdeTextDraw(
                IdeTextKind.Status,
                value,
                x,
                y,
                color,
                IdeTextStyle.Ui,
                IdeCodeFontProfile.DEFAULT,
                clip,
                null,
                IDE_TERMINAL_OVERLAY_Z,
            ),
        )
    }
}

private const val IDE_TERMINAL_OVERLAY_Z = 80

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

import ru.lazyhat.compukters.ide.client.git.IdeGitField
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.git.GitOperation
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_CONTENT
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_POPUP
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TARGET
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TEXT

/** Per-assembly primitive collector; controls share their visual and interactive bounds. */
internal open class IdeDrawBuilder(
    protected val font: IdeCodeFontProfile,
) {
    protected val panels = mutableListOf<IdePanelDraw>()
    protected val text = mutableListOf<IdeTextDraw>()
    protected val fills = mutableListOf<IdeFillDraw>()
    protected val scissors = mutableListOf<IdeScissorDraw>()
    protected val hitTargets = mutableListOf<IdeHitTarget>()
    protected val icons = mutableListOf<IdeIconDraw>()
    protected var gitScrollMaximum: Int? = null
    protected var gitPreviewScrollMaximum: Int? = null
    protected var gitPreviewBounds: IdeRect? = null
    protected val gitFieldFocusScroll = mutableMapOf<IdeGitField, Int>()
    protected var bottomScrollMaximum = 0

    fun disableTargets() {
        for (index in hitTargets.indices) hitTargets[index] = hitTargets[index].copy(enabled = false)
    }

    fun icon(draw: IdeIconDraw) {
        icons += draw
    }

    fun fill(draw: IdeFillDraw) {
        fills += draw
    }

    fun scissor(draw: IdeScissorDraw) {
        scissors += draw
    }

    fun hit(target: IdeHitTarget) {
        hitTargets += target
    }

    fun uiWidth(value: String): Int = value.codePointCount(0, value.length) * font.cellWidth

    /** Bound fitting work by the visible code-point budget, matching the font used for drawing. */
    fun fitUi(
        value: String,
        width: Int,
    ): String {
        val columns = (width / font.cellWidth).coerceAtLeast(0)
        val points = value.codePoints().limit(columns.toLong() + 1).toArray()
        if (points.size <= columns) return value
        if (columns == 0) return ""
        return String(points, 0, columns - 1) + "…"
    }

    fun ui(
        kind: IdeTextKind,
        value: String,
        x: Int,
        y: Int,
        color: Int = IdeColors.TEXT,
        clip: IdeRect? = null,
        z: Int = Z_TEXT,
        rotation: IdeTextRotation = IdeTextRotation.None,
    ) {
        text += IdeTextDraw(kind, value, x, y + font.glyphDrawOffsetY, color, IdeTextStyle.Ui, font, clip, null, z, rotation)
    }

    fun code(
        kind: IdeTextKind,
        value: String,
        x: Int,
        y: Int,
        color: Int,
        clip: IdeRect,
        style: IdeTextStyle = IdeTextStyle.Plain,
        sourceRange: EditorRange? = null,
        z: Int = Z_TEXT,
    ) {
        text += IdeTextDraw(kind, value, x, y, color, style, font, clip, sourceRange, z)
    }

    fun panel(
        kind: IdePanelKind,
        bounds: IdeRect,
        color: Int,
        z: Int = Z_CONTENT,
    ) {
        if (z >= Z_POPUP) {
            for (spread in 6 downTo 1) {
                val shadow = IdeRect(bounds.left - spread, bounds.top - spread + 2, bounds.right + spread, bounds.bottom + spread + 2)
                fills += IdeFillDraw(IdeFillKind.Shadow, shadow, 0x08000000, z - 1)
            }
        }
        panels += IdePanelDraw(kind, bounds, color, z)
    }

    fun target(
        action: IdeHitAction,
        bounds: IdeRect,
        enabled: Boolean,
        tooltip: String? = null,
        focusGroup: IdeFocusGroup = IdeFocusGroup.Page,
        z: Int = Z_TARGET,
        selected: Boolean = false,
        choiceIndex: Int? = null,
        gitOperation: GitOperation? = null,
        gitCommand: IdeCommand? = null,
        style: IdeControlStyle = IdeControlStyle.Standard,
    ) {
        val control =
            IdeControls.target(
                IdeHitTarget(
                    action,
                    bounds,
                    enabled,
                    tooltip,
                    focusGroup,
                    z,
                    selected,
                    choiceIndex,
                    gitOperation = gitOperation,
                    gitCommand = gitCommand,
                ),
                style,
            )
        panels += control.panel
        hitTargets += control.target
    }

    fun build(): IdeDrawModel =
        IdeDrawModel(
            panels.toList(),
            text.toList(),
            fills.toList(),
            scissors.toList(),
            hitTargets.toList(),
            icons.toList(),
            bottomScrollMaximum = bottomScrollMaximum,
            gitScrollMaximum = gitScrollMaximum,
            gitPreviewScrollMaximum = gitPreviewScrollMaximum,
            gitPreviewBounds = gitPreviewBounds,
            gitFieldFocusScroll = gitFieldFocusScroll.toMap(),
        )
}

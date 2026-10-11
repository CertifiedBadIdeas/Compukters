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

import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.state.IdeBusyOperation
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdeToolingState
import ru.lazyhat.compukters.ide.client.target.IdeTargetState
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TEXT

/** Projects command availability without owning build, editor or target state. */
internal object IdeToolbar {
    fun draw(
        output: IdeDrawBuilder,
        geometry: IdeRenderGeometry,
        workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView,
        targetState: IdeTargetState,
        toolingState: IdeToolingState,
        busy: Set<IdeBusyOperation>,
        hasActiveEntry: Boolean,
    ) = with(output) {
        var left = geometry.toolbar.left + 6
        val expanded = geometry.toolbar.width >= EXPANDED_MINIMUM_WIDTH

        fun action(
            icon: IdeIconKind,
            action: IdeHitAction,
            enabled: Boolean = true,
            tooltip: String,
            selected: Boolean = false,
        ) {
            val label =
                if (!expanded) {
                    null
                } else {
                    when (action) {
                        IdeHitAction.Build -> "Build"
                        IdeHitAction.Run -> "Run"
                        IdeHitAction.Cancel, IdeHitAction.GitCancel -> "Cancel"
                        else -> null
                    }
                }
            val width = label?.let { uiWidth(it) + 34 } ?: TOOLBAR_ICON_CONTROL_WIDTH
            val bounds = IdeRect(left, geometry.toolbar.top + 3, left + width, geometry.toolbar.bottom - 3)
            if (!geometry.toolbar.contains(bounds)) return
            val style = if (action == IdeHitAction.Run) IdeControlStyle.Primary else IdeControlStyle.Neutral
            target(action, bounds, enabled, tooltip, selected = selected, style = style)
            val color =
                if (!enabled) {
                    IdeColors.DISABLED
                } else if (selected ||
                    action == IdeHitAction.Run
                ) {
                    IdeColors.ACCENT
                } else {
                    IdeColors.TEXT
                }
            val iconBounds = if (label == null) bounds else IdeRect(bounds.left + 2, bounds.top, bounds.left + 24, bounds.bottom)
            output.icon(IdeIconDraw(icon, iconBounds, color, Z_TEXT))
            if (label != null) ui(IdeTextKind.Toolbar, label, bounds.left + 28, bounds.top + 5, color, bounds)
            left = bounds.right + 4
        }

        fun groupGap() {
            left += TOOLBAR_GROUP_GAP
        }

        val toolingReady = toolingState == IdeToolingState.Ready
        val toolingUnavailable = if (toolingReady) null else "Kotlin tooling is not ready"
        val build = workspace.build
        val editor = workspace.editor as? IdeEditorView.Text
        val formatBusy = IdeBusyOperation.Format in busy
        val writableKotlin = editor?.readOnly == false && editor.path?.value?.endsWith(".kt") == true
        val formatTooltip =
            when {
                !toolingReady -> checkNotNull(toolingUnavailable)
                !writableKotlin -> "Open a writable Kotlin source"
                formatBusy -> "Kotlin formatting is already running"
                else -> "Reformat Code (Ctrl+Alt+L)"
            }
        action(
            IdeIconKind.Format,
            IdeHitAction.Format,
            toolingReady && writableKotlin && !formatBusy,
            formatTooltip,
            selected = formatBusy,
        )
        action(IdeIconKind.Resolve, IdeHitAction.Resolve, toolingReady, toolingUnavailable ?: "Resolve dependencies")
        if (IdeBusyOperation.Clone in busy || IdeBusyOperation.Git in busy) {
            action(IdeIconKind.Cancel, IdeHitAction.GitCancel, tooltip = "Cancel Git operation")
        } else if (build is IdeBuildState.Compiling || build is IdeBuildState.Saving) {
            action(IdeIconKind.Cancel, IdeHitAction.Cancel, tooltip = "Cancel build")
        } else {
            action(IdeIconKind.Build, IdeHitAction.Build, toolingReady, toolingUnavailable ?: "Build (Ctrl+F9)")
        }
        groupGap()
        val targetReady =
            toolingReady && targetState.isReadyForAction && build !is IdeBuildState.Compiling && build !is IdeBuildState.Saving
        val targetTooltip =
            when {
                !toolingReady -> toolingUnavailable
                targetState.attachedTarget == null -> NO_TARGET
                !targetReady -> "Target operation in progress"
                else -> null
            }
        action(IdeIconKind.Verify, IdeHitAction.Verify, targetReady, targetTooltip ?: "Verify target artifact")
        action(IdeIconKind.Deploy, IdeHitAction.Deploy, targetReady, targetTooltip ?: "Deploy to target")
        action(IdeIconKind.Run, IdeHitAction.Run, targetReady, targetTooltip ?: "Run on target")
        groupGap()
        action(IdeIconKind.NewFile, IdeHitAction.CreateText, tooltip = "New file")
        action(IdeIconKind.NewDirectory, IdeHitAction.CreateDirectory, tooltip = "New directory")
        action(
            IdeIconKind.Rename,
            IdeHitAction.Rename,
            hasActiveEntry,
            if (hasActiveEntry) "Rename selected entry" else "Select an entry to rename",
        )
        action(
            IdeIconKind.Delete,
            IdeHitAction.Delete,
            hasActiveEntry,
            if (hasActiveEntry) "Delete selected entry" else "Select an entry to delete",
        )
        val bounds = IdeRect(left + 4, geometry.toolbar.top + 3, left + 88, geometry.toolbar.bottom - 3)
        if (geometry.toolbar.contains(bounds)) {
            target(IdeHitAction.QuickOpen, bounds, busy.isEmpty(), "Open file (Ctrl+Shift+N)", style = IdeControlStyle.Neutral)
            ui(IdeTextKind.Toolbar, "Open file…", bounds.left + 8, bounds.top + 5)
        }
    }

    private const val EXPANDED_MINIMUM_WIDTH = 720
    private const val TOOLBAR_ICON_CONTROL_WIDTH = 22
    private const val TOOLBAR_GROUP_GAP = 6
    private const val NO_TARGET = "No target attached"
}

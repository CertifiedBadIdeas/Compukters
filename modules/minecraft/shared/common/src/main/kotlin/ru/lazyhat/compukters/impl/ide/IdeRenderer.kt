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

import ru.lazyhat.compukters.ide.client.files.IdeComputerChildren
import ru.lazyhat.compukters.ide.client.files.IdeComputerNode
import ru.lazyhat.compukters.ide.client.files.IdeComputerTreeState
import ru.lazyhat.compukters.ide.client.git.IdeGitField
import ru.lazyhat.compukters.ide.client.git.IdeGitFieldView
import ru.lazyhat.compukters.ide.client.state.IdeBottomTab
import ru.lazyhat.compukters.ide.client.state.IdeBusyOperation
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdeGitMenu
import ru.lazyhat.compukters.ide.client.state.IdeGitTab
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeToolingState
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView
import ru.lazyhat.compukters.ide.client.target.IdeTargetState
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.git.GitOperation
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_BACKGROUND
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_CARET
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_CLIP
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_CONTENT
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_DIALOG
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_DIALOG_SCRIM
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_DIALOG_TARGET
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_DIALOG_TEXT
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_DRAG
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_PANEL
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_POPUP
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_POPUP_TARGET
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_POPUP_TEXT
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_PROJECT_SWITCHER
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_PROJECT_SWITCHER_TARGET
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_PROJECT_SWITCHER_TEXT
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_SELECTION
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TARGET
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TEXT
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TOOLTIP
import ru.lazyhat.compukters.impl.ide.IdeDrawLayers.Z_TOOLTIP_TEXT

object IdeRenderer {
    fun extract(
        state: IdeViewState,
        geometry: IdeRenderGeometry,
        caretVisible: Boolean = true,
        prompt: IdePromptState? = null,
        treeFirstRow: Int = 0,
        selectedTreePath: ProjectPath? = null,
        terminalState: IdeTerminalStatus = IdeTerminalStatus.Closed,
        terminalVisible: Boolean = false,
        explorerDrag: IdeExplorerDragVisual? = null,
        projectSwitcherOpen: Boolean = false,
        pointerX: Int? = null,
        pointerY: Int? = null,
        uiScale: IdeUiScale = IdeUiScale.AUTO,
        viewportSupported: Boolean = true,
        projectFirstRow: Int = 0,
        fileTabFirstIndex: Int = 0,
        quickOpen: IdeQuickOpenView? = null,
        statusDetails: IdeStatusDetailsView? = null,
        peripheralPicker: IdePeripheralPickerView? = null,
        hoverScroll: Int = 0,
        hoverDocumentation: IdeHoverDocumentation? = null,
    ): IdeDrawModel {
        val output =
            Builder(
                geometry,
                geometry.font,
                treeFirstRow,
                selectedTreePath,
                terminalState,
                terminalVisible,
                explorerDrag,
                projectSwitcherOpen,
                projectFirstRow,
                fileTabFirstIndex,
                hoverScroll,
                hoverDocumentation,
            )
        if (state.page == IdePageState.Opening) {
            output.background()
            return output.build()
        }
        output.base(state.page is IdePageState.Start)
        if (!geometry.supported || !viewportSupported) {
            output.scaleControls(uiScale, enabled = true)
            output.ui(
                IdeTextKind.Status,
                "Window is too small for this IDE scale. Choose Auto or 2 above.",
                8,
                32,
                IdeColors.ERROR,
                clip = geometry.viewport,
            )
            return output.build()
        }
        when (val page = state.page) {
            IdePageState.Opening -> Unit
            is IdePageState.Start -> output.start(page, state.target, state.busy)
            is IdePageState.Workspace -> output.workspace(page.value, state.target, state.tooling, state.busy, caretVisible)
        }
        if (peripheralPicker != null &&
            state.page is IdePageState.Workspace
        ) {
            IdePeripheralPicker.draw(output, geometry.content, peripheralPicker)
        }
        output.terminalTool(state.target)
        output.scaleControls(uiScale, enabled = statusDetails == null && quickOpen == null && prompt == null && state.dialog == null)
        val dialog = state.dialog
        if (statusDetails != null && dialog == null && prompt == null) {
            IdeStatusDetailsRenderer.draw(output, geometry.panel, statusDetails)
        } else if (quickOpen != null) {
            output.quickOpen(quickOpen)
        } else if (prompt != null) {
            output.prompt(prompt)
        } else if (dialog != null) {
            output.dialog(dialog)
        } else if (pointerX != null && pointerY != null) {
            output.tooltip(pointerX, pointerY)
        }
        return output.build()
    }

    private class Builder(
        private val geometry: IdeRenderGeometry,
        font: IdeCodeFontProfile,
        private val treeFirstRow: Int,
        private val selectedTreePath: ProjectPath?,
        private val terminalState: IdeTerminalStatus,
        private val terminalVisible: Boolean,
        private val explorerDrag: IdeExplorerDragVisual?,
        private val projectSwitcherOpen: Boolean,
        private val projectFirstRow: Int,
        private val fileTabFirstIndex: Int,
        hoverScroll: Int,
        hoverDocumentation: IdeHoverDocumentation?,
    ) : IdeDrawBuilder(font, hoverScroll, hoverDocumentation) {
        fun background() {
            fills += IdeFillDraw(IdeFillKind.Background, geometry.viewport, IdeColors.DIM, Z_BACKGROUND)
        }

        fun base(startPage: Boolean) {
            background()
            if (!geometry.supported) return
            fills += IdeFillDraw(IdeFillKind.Border, expand(geometry.panel, 1), IdeColors.BORDER, Z_PANEL)
            panel(IdePanelKind.Main, geometry.panel, IdeColors.PANEL)
            panel(IdePanelKind.Header, geometry.header, IdeColors.PANEL)
            panel(IdePanelKind.Toolbar, geometry.toolbar, IdeColors.PANEL)
            panel(IdePanelKind.ToolStripe, geometry.toolStripe, IdeColors.PANEL)
            panel(IdePanelKind.Status, geometry.status, IdeColors.PANEL)
            if (startPage) {
                panel(IdePanelKind.Editor, geometry.content, IdeColors.EDITOR)
            } else {
                geometry.tree?.let { panel(IdePanelKind.Tree, it, IdeColors.PANEL_ALT) }
                panel(IdePanelKind.Editor, geometry.editor, IdeColors.EDITOR)
                geometry.diagnostics?.let { panel(IdePanelKind.Diagnostics, it, IdeColors.PANEL_ALT) }
                geometry.treeSplitter?.let { fills += IdeFillDraw(IdeFillKind.Splitter, it, IdeColors.PANEL, Z_CONTENT) }
                geometry.diagnosticsSplitter?.let { fills += IdeFillDraw(IdeFillKind.Splitter, it, IdeColors.PANEL, Z_CONTENT) }
            }
        }

        fun scaleControls(
            scale: IdeUiScale,
            enabled: Boolean,
        ) {
            val options = IdeUiScaleControl.options(geometry.viewport)
            if (options.isEmpty()) return
            val first = options.first().second
            panels +=
                IdePanelDraw(
                    IdePanelKind.Header,
                    IdeRect(first.left - 36, geometry.viewport.top, geometry.viewport.right, geometry.viewport.top + 24),
                    IdeColors.PANEL,
                    Z_POPUP - 1,
                )
            ui(IdeTextKind.Header, "Scale", first.left - 36, font.centeredCellTop(first), z = Z_POPUP_TEXT)
            options.forEach { (mode, bounds) ->
                target(mode.action, bounds, enabled, "IDE scale: ${mode.label}", z = Z_POPUP_TARGET, selected = mode == scale)
                ui(IdeTextKind.Header, mode.label, bounds.left + 3, font.centeredCellTop(bounds), clip = bounds, z = Z_POPUP_TEXT)
            }
        }

        fun start(
            page: IdePageState.Start,
            targetState: IdeTargetState,
            busy: Set<IdeBusyOperation>,
        ) {
            ui(
                IdeTextKind.Header,
                "Compukters IDE · ${targetLabel(targetState)}",
                geometry.header.left + 6,
                font.centeredCellTop(geometry.header),
            )
            val width = minOf(900, geometry.content.width - 8)
            val height = minOf(360, geometry.content.height - 4)
            val left = geometry.content.left + (geometry.content.width - width) / 2
            val top = geometry.content.top + (geometry.content.height - height) / 2
            val bounds = IdeRect(left, top, left + width, top + height)
            panel(IdePanelKind.ProjectSelection, bounds, IdeColors.PANEL_ALT)
            ui(IdeTextKind.StartProject, "Projects", bounds.left + 6, bounds.top + 5, clip = bounds)
            val sideActions = width >= 660 && height >= 160
            val actionsLeft = if (sideActions) bounds.right - 168 else bounds.left + 4
            val actionsTop = if (sideActions) bounds.top + 24 else bounds.bottom - 44
            val actionsWidth = if (sideActions) 164 else (bounds.width - 12) / 2
            val actions =
                listOf(
                    IdeHitAction.CreateProject to "Create project",
                    IdeHitAction.OpenExisting to "Open directory",
                    IdeHitAction.CloneProject to "Clone HTTPS",
                    if (IdeBusyOperation.Project in busy) {
                        IdeHitAction.GitCancel to "Cancel clone"
                    } else {
                        IdeHitAction.GitAuthenticate to "HTTPS token"
                    },
                )
            actions.forEachIndexed { index, (action, label) ->
                val x = actionsLeft + if (sideActions) 0 else index % 2 * (actionsWidth + 4)
                val y = actionsTop + if (sideActions) index * 30 else index / 2 * 20
                val actionBounds = IdeRect(x, y, x + actionsWidth, y + if (sideActions) 24 else 18)
                target(action, actionBounds, busy.isEmpty() || action == IdeHitAction.GitCancel, label)
                ui(IdeTextKind.ProjectAction, label, actionBounds.left + 4, font.centeredCellTop(actionBounds), clip = actionBounds)
            }
            val listTop = bounds.top + 24
            val listBottom = if (sideActions) bounds.bottom - 4 else actionsTop - 4
            val listRight = if (sideActions) actionsLeft - 8 else bounds.right - 4
            val maximumRows = ((listBottom - listTop) / PROJECT_ROW_HEIGHT).coerceAtLeast(0)
            val first = projectFirstRow.coerceIn(0, (page.projects.size - maximumRows).coerceAtLeast(0))
            page.projects.drop(first).take(maximumRows).forEachIndexed { index, project ->
                val rowTop = listTop + index * PROJECT_ROW_HEIGHT
                projectRow(
                    project,
                    first + index,
                    IdeRect(bounds.left + 4, rowTop, listRight, rowTop + PROJECT_ROW_HEIGHT),
                    busy.isEmpty(),
                    false,
                    Z_TARGET,
                    Z_CONTENT,
                )
            }
            if (page.projects.isEmpty()) {
                ui(
                    IdeTextKind.StartProject,
                    "No saved projects. Create, open or clone a project.",
                    bounds.left + 6,
                    listTop + 5,
                    IdeColors.MUTED,
                    clip = IdeRect(bounds.left + 4, listTop, listRight, listBottom),
                )
            }
            if (busy.isNotEmpty()) {
                ui(
                    IdeTextKind.Status,
                    "Opening project…",
                    geometry.status.left + 6,
                    font.centeredCellTop(geometry.status),
                )
            }
            page.error?.let {
                ui(
                    IdeTextKind.Status,
                    it.message,
                    geometry.status.left + 6,
                    font.centeredCellTop(geometry.status),
                    problemColor(it.severity.name),
                )
            }
        }

        fun workspace(
            workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView,
            targetState: IdeTargetState,
            toolingState: IdeToolingState,
            busy: Set<IdeBusyOperation>,
            caretVisible: Boolean,
        ) {
            val projectControl = IdeWorkspaceHeader.draw(this, geometry, workspace, targetState, projectSwitcherOpen)
            IdeToolbar.draw(
                this,
                geometry,
                workspace,
                targetState,
                toolingState,
                busy,
                workspace.activeFile != null || selectedTreePath != null,
            )
            toolWindows(workspace)
            tree(workspace)
            fileTabs(workspace, busy)
            if (workspace.git.visible) {
                gitPanel(workspace, busy)
            } else {
                when (val editor = workspace.editor) {
                    IdeEditorView.Empty -> {
                        ui(IdeTextKind.Source, "Open a file", geometry.editor.left + 8, geometry.editor.top + 8, IdeColors.MUTED)
                    }

                    is IdeEditorView.Binary -> {
                        ui(
                            IdeTextKind.Binary,
                            "Binary file · ${editor.bytes} bytes",
                            geometry.editor.left + 8,
                            geometry.editor.top + 8,
                            IdeColors.MUTED,
                        )
                    }

                    is IdeEditorView.Text -> {
                        IdeEditorRenderer(this, geometry, editor).draw(caretVisible, workspace.diagnostics)
                    }
                }
            }
            diagnostics(workspace)
            IdeStatusBar.draw(this, geometry, workspace, targetState, toolingState, busy)
            if (projectSwitcherOpen) projectSwitcher(workspace, projectControl)
        }

        private fun fileTabs(
            workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView,
            busy: Set<IdeBusyOperation>,
        ) {
            val bounds = geometry.fileTabs ?: return
            val active = workspace.activeFile.takeUnless { (workspace.editor as? IdeEditorView.Text)?.readOnly == true }
            val layout = IdeFileTabBar.layout(bounds, workspace.openFiles, fileTabFirstIndex)
            val component = IdeFileTabBar.draw(layout, active, IdeBusyOperation.Git !in busy && IdeBusyOperation.Project !in busy, font)
            component.controls.forEach(::control)
            text += component.text
            fills += component.fills
        }

        private fun projectSwitcher(
            workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView,
            control: IdeRect,
        ) {
            val availableHeight = (geometry.status.top - geometry.header.bottom - 4).coerceAtLeast(PROJECT_ROW_HEIGHT)
            val projectRows =
                ((availableHeight - 4 * PROJECT_ACTION_HEIGHT - 2 * IdeWindowSurface.PADDING) / PROJECT_ROW_HEIGHT)
                    .coerceAtLeast(
                        0,
                    )
            val first = projectFirstRow.coerceIn(0, (workspace.projects.size - projectRows).coerceAtLeast(0))
            val visibleProjects = workspace.projects.drop(first).take(projectRows)
            val width = minOf(PROJECT_SWITCHER_WIDTH, geometry.panel.right - control.left - 4).coerceAtLeast(control.width)
            val bounds =
                IdeRect(
                    control.left,
                    geometry.header.bottom,
                    control.left + width,
                    geometry.header.bottom + visibleProjects.size * PROJECT_ROW_HEIGHT + 4 * PROJECT_ACTION_HEIGHT +
                        2 * IdeWindowSurface.PADDING,
                )
            panel(IdePanelKind.ProjectSwitcher, bounds, IdeColors.PANEL_ALT, Z_PROJECT_SWITCHER)
            scissors += IdeScissorDraw(IdeScissorKind.ProjectSwitcher, bounds, Z_PROJECT_SWITCHER)
            visibleProjects.forEachIndexed { index, project ->
                val row =
                    IdeRect(
                        bounds.left + IdeWindowSurface.PADDING,
                        bounds.top + IdeWindowSurface.PADDING + index * PROJECT_ROW_HEIGHT,
                        bounds.right - IdeWindowSurface.PADDING,
                        bounds.top + IdeWindowSurface.PADDING + (index + 1) * PROJECT_ROW_HEIGHT,
                    )
                projectRow(
                    project,
                    first + index,
                    row,
                    true,
                    project.directoryName == workspace.project.directoryName,
                    Z_PROJECT_SWITCHER_TARGET,
                    Z_PROJECT_SWITCHER_TEXT,
                    IdeFocusGroup.ProjectSwitcher,
                )
            }
            listOf(
                IdeHitAction.CreateProject to "+ New project",
                IdeHitAction.OpenExisting to "Open existing directory",
                IdeHitAction.CloneProject to "Clone HTTPS repository",
                IdeHitAction.GitAuthenticate to "HTTPS token · session only",
            ).forEachIndexed { index, (action, label) ->
                val top = bounds.top + IdeWindowSurface.PADDING + visibleProjects.size * PROJECT_ROW_HEIGHT + index * PROJECT_ACTION_HEIGHT
                val row =
                    IdeRect(
                        bounds.left + IdeWindowSurface.PADDING,
                        top,
                        bounds.right - IdeWindowSurface.PADDING,
                        top + PROJECT_ACTION_HEIGHT,
                    )
                target(action, row, true, focusGroup = IdeFocusGroup.ProjectSwitcher, z = Z_PROJECT_SWITCHER_TARGET)
                ui(IdeTextKind.ProjectAction, label, row.left + 6, font.centeredCellTop(row), clip = bounds, z = Z_PROJECT_SWITCHER_TEXT)
            }
        }

        private fun projectRow(
            project: ru.lazyhat.compukters.ide.client.state.IdeProjectSummary,
            index: Int,
            row: IdeRect,
            enabled: Boolean,
            selected: Boolean,
            targetZ: Int,
            textZ: Int,
            focusGroup: IdeFocusGroup = IdeFocusGroup.Page,
        ) {
            val actionsWidth = if (project.external) 112 else 158
            val open = IdeRect(row.left, row.top, row.right - actionsWidth - 6, row.bottom - 2)
            target(
                IdeHitAction.ProjectChoice,
                open,
                enabled && !selected,
                project.path,
                focusGroup = focusGroup,
                z = targetZ,
                selected = selected,
                choiceIndex = index,
            )
            ui(
                IdeTextKind.ProjectChoice,
                if (project.external) "External · ${project.displayName}" else "${project.displayName} · IDE folder",
                open.left + 5,
                font.centeredCellTop(open, rows = 2),
                clip = open,
                z = textZ,
            )
            ui(
                IdeTextKind.ProjectChoice,
                project.path,
                open.left + 5,
                font.centeredCellTop(open, rows = 2) + font.cellHeight,
                IdeColors.MUTED,
                clip = open,
                z = textZ,
            )
            var left = open.right + 4
            if (!project.external) {
                val rename = IdeRect(left, row.top + 4, left + 60, row.bottom - 6)
                target(
                    IdeHitAction.RenameProjectFolder,
                    rename,
                    enabled,
                    "Rename project folder",
                    focusGroup = focusGroup,
                    z = targetZ,
                    choiceIndex = index,
                )
                ui(IdeTextKind.ProjectAction, "Rename…", rename.left + 4, font.centeredCellTop(rename), clip = rename, z = textZ)
                left = rename.right + 4
            }
            val remove = IdeRect(left, row.top + 4, row.right, row.bottom - 6)
            val label = if (project.external) "Remove from list" else "Delete folder…"
            target(
                if (project.external) IdeHitAction.ForgetExternalProject else IdeHitAction.DeleteProjectFolder,
                remove,
                enabled,
                if (project.external) "Remove from list; files are kept" else "Delete folder and all files after confirmation",
                focusGroup = focusGroup,
                z = targetZ,
                choiceIndex = index,
            )
            ui(IdeTextKind.ProjectAction, label, remove.left + 4, font.centeredCellTop(remove), clip = remove, z = textZ)
        }

        private fun gitPanel(
            workspace: IdeWorkspaceView,
            busy: Set<IdeBusyOperation>,
        ) {
            val bounds = geometry.editor
            val view = workspace.git
            val status = view.result?.status
            val available = status?.available == true
            val idle = busy.isEmpty()
            val wide = bounds.width >= 560
            val compact = bounds.height < 290
            var x = bounds.left + 6
            var y = bounds.top + 6 - if (compact) view.scroll * 20 else 0

            fun button(
                label: String,
                action: IdeHitAction = IdeHitAction.GitOperation,
                command: IdeCommand? = null,
                operation: GitOperation? = null,
                enabled: Boolean = idle,
                selected: Boolean = false,
            ) {
                val width = (label.length * 6 + 14).coerceAtMost(bounds.width - 12)
                if (x + width > bounds.right - 6) {
                    x = bounds.left + 6
                    y += 24
                }
                val rect = IdeRect(x, y, x + width, y + 20)
                if (bounds.contains(rect)) {
                    target(action, rect, enabled, label, selected = selected, gitOperation = operation, gitCommand = command)
                    ui(
                        IdeTextKind.Toolbar,
                        label,
                        rect.left + 6,
                        font.centeredCellTop(rect),
                        if (enabled) IdeColors.TEXT else IdeColors.DISABLED,
                        rect,
                    )
                }
                x += width + 4
            }
            button("Changes", command = IdeCommand.GitTab(IdeGitTab.Changes), selected = view.tab == IdeGitTab.Changes)
            if (!wide) button("Diff", command = IdeCommand.GitTab(IdeGitTab.Diff), selected = view.tab == IdeGitTab.Diff)
            button("Editor", action = IdeHitAction.GitClose, enabled = true)
            x = bounds.left + 6
            y += 24
            button(
                (status?.branch ?: "No repository").take(18) + " ▾",
                command = IdeCommand.GitMenu(IdeGitMenu.Branches),
                enabled =
                    idle && available,
            )
            button("Update", operation = GitOperation.Pull, enabled = idle && available)
            button("Push", operation = GitOperation.Push, enabled = idle && available)
            button("Repository ▾", command = IdeCommand.GitMenu(IdeGitMenu.Repository))
            button("Refresh", operation = GitOperation.Status)
            if (!idle) button("Cancel", action = IdeHitAction.GitCancel, enabled = IdeBusyOperation.Git in busy)
            val bodyTop = y + 28
            val leftRight = if (wide) bounds.left + (bounds.width * 2 / 5).coerceAtLeast(240) else bounds.right
            val listLeft = bounds.left + 6
            val listRight = leftRight - 6
            val contentTop = maxOf(bounds.top + 6, bodyTop)
            var maximum = 0

            fun line(
                label: String,
                rowTop: Int,
                left: Int = listLeft,
                right: Int = listRight,
                color: Int = IdeColors.TEXT,
                monospace: Boolean = false,
            ) {
                val rect = IdeRect(left, rowTop, right, rowTop + 20)
                if (bounds.contains(rect)) {
                    if (monospace) {
                        code(IdeTextKind.Source, label, left + 4, rowTop + 4 + font.glyphDrawOffsetY, color, rect)
                    } else {
                        ui(IdeTextKind.Source, label, left + 4, rowTop + 5, color, rect)
                    }
                }
            }
            if (!available) {
                line("This project has no Git repository", bodyTop)
                x = listLeft
                y = bodyTop + 24
                button("Create Git repository", operation = GitOperation.Init)
                line("Open Repository for HTTPS account settings", y + 28, color = IdeColors.MUTED)
            } else if (view.tab == IdeGitTab.Diff) {
                val rows =
                    view.result
                        ?.diff
                        ?.lineSequence()
                        ?.toList()
                        .orEmpty()
                line(
                    "HEAD → Working tree · ${view.previewPath?.value ?: "Select a file in Changes"}",
                    bodyTop,
                    right = bounds.right - 6,
                    color = IdeColors.MUTED,
                )
                val offset = if (compact) 0 else view.scroll
                rows.drop(offset).forEachIndexed { index, text ->
                    line(text, bodyTop + 24 + index * 20, right = bounds.right - 6, color = gitDiffColor(text), monospace = true)
                }
                if (rows.isEmpty()) {
                    line(
                        "Choose a changed file to preview",
                        bodyTop + 24,
                        right = bounds.right - 6,
                        color = IdeColors.MUTED,
                    )
                }
                maximum =
                    if (compact) {
                        ((bodyTop + view.scroll * 20 + 24 + rows.size * 20 - bounds.bottom + 19).coerceAtLeast(0) / 20)
                    } else {
                        (
                            (
                                rows.size *
                                    20 -
                                    (bounds.bottom - contentTop - 24)
                            ) +
                                19
                        ).coerceAtLeast(0) /
                            20
                    }
            } else {
                val changes = status.changes
                val formTop = if (compact) bodyTop + 24 + maxOf(1, changes.size) * 20 + 12 else bounds.bottom - 154
                val listBottom = if (compact) bounds.bottom - 6 else formTop - 8
                val heading = IdeRect(listLeft, bodyTop, listRight, bodyTop + 20)
                if (bounds.contains(heading)) {
                    target(IdeHitAction.GitOperation, heading, idle, "Select or clear all files", gitCommand = IdeCommand.GitCheck(null))
                    checkbox(
                        IdeRect(heading.left, heading.top, heading.left + 22, heading.bottom),
                        changes.isNotEmpty() && view.checkedPaths.size == changes.size,
                        idle,
                    )
                    ui(
                        IdeTextKind.Source,
                        "Changes (${view.checkedPaths.size}/${changes.size})",
                        heading.left + 26,
                        font.centeredCellTop(heading),
                        clip = heading,
                    )
                }
                val rowsTop = bodyTop + 24
                val offset = if (compact) 0 else view.scroll
                if (changes.isEmpty()) line("No changes", rowsTop, color = IdeColors.MUTED)
                changes.drop(offset).forEachIndexed { index, change ->
                    val top = rowsTop + index * 20
                    val row = IdeRect(listLeft, top, listRight, top + 20)
                    if (top >= bounds.top && row.bottom <= listBottom && bounds.contains(row)) {
                        val check = IdeRect(row.left, row.top, row.left + 22, row.bottom)
                        target(
                            IdeHitAction.GitOperation,
                            row,
                            idle,
                            "Preview ${change.path.value}",
                            selected =
                                view.previewPath == change.path,
                            gitCommand = IdeCommand.GitPreview(change.path, showDiff = !wide),
                        )
                        target(
                            IdeHitAction.GitOperation,
                            check,
                            idle,
                            "Include ${change.path.value} in commit",
                            gitCommand = IdeCommand.GitCheck(change.path),
                        )
                        checkbox(check, change.path in view.checkedPaths, idle)
                        val color = IdeGitFileColors.color(change)
                        ui(IdeTextKind.Source, change.path.value, row.left + 26, font.centeredCellTop(row), color, row)
                    }
                }
                val messageField = IdeRect(listLeft, formTop + 18, listRight, formTop + 66)
                for (field in IdeGitField.entries) {
                    val offset = if (field == IdeGitField.Message) 18 else 90
                    gitFieldFocusScroll[field] =
                        if (compact) ((formTop + view.scroll * 20 + offset - bounds.top - 6) / 20).coerceAtLeast(0) else 0
                }
                line("Commit message", formTop, color = IdeColors.MUTED)
                gitField(messageField, IdeGitField.Message, view.draft.message, view.draft.focused, idle)
                val half = (listLeft + listRight) / 2
                line("Author", formTop + 72, right = half - 3, color = IdeColors.MUTED)
                line("Email", formTop + 72, left = half + 3, color = IdeColors.MUTED)
                gitField(
                    IdeRect(listLeft, formTop + 90, half - 3, formTop + 112),
                    IdeGitField.AuthorName,
                    view.draft.authorName,
                    view.draft.focused,
                    idle,
                )
                gitField(
                    IdeRect(half + 3, formTop + 90, listRight, formTop + 112),
                    IdeGitField.AuthorEmail,
                    view.draft.authorEmail,
                    view.draft.focused,
                    idle,
                )
                val canCommit = idle && view.checkedPaths.isNotEmpty() && view.draft.canCommit
                val commit = IdeRect(listLeft, formTop + 122, listLeft + 66, formTop + 144)
                val push = IdeRect(commit.right + 5, commit.top, minOf(listRight, commit.right + 111), commit.bottom)
                if (bounds.contains(commit)) {
                    target(
                        IdeHitAction.GitCommit,
                        commit,
                        canCommit,
                        "Commit selected files (Ctrl+Enter)",
                        gitCommand = IdeCommand.GitCommitDraft(),
                    )
                    ui(
                        IdeTextKind.Toolbar,
                        "Commit",
                        commit.left + 8,
                        commit.top + 7,
                        if (canCommit) IdeColors.TEXT else IdeColors.DISABLED,
                        commit,
                    )
                }
                if (bounds.contains(push)) {
                    target(
                        IdeHitAction.GitCommit,
                        push,
                        canCommit,
                        "Commit selected files, then push",
                        gitCommand = IdeCommand.GitCommitDraft(push = true),
                    )
                    ui(
                        IdeTextKind.Toolbar,
                        "Commit & Push",
                        push.left + 5,
                        push.top + 7,
                        if (canCommit) IdeColors.TEXT else IdeColors.DISABLED,
                        push,
                    )
                }
                maximum =
                    if (compact) {
                        ((formTop + view.scroll * 20 + 154 - bounds.bottom + 19).coerceAtLeast(0) / 20)
                    } else {
                        (
                            (
                                changes.size * 20 -
                                    (listBottom - rowsTop)
                            ) +
                                19
                        ).coerceAtLeast(0) /
                            20
                    }
                if (wide) {
                    val diffBounds =
                        IdeRect(
                            leftRight + 6,
                            maxOf(bounds.top + 6, bodyTop).coerceAtMost(bounds.bottom - 6),
                            bounds.right - 6,
                            bounds.bottom - 6,
                        )
                    panel(IdePanelKind.Editor, diffBounds, IdeColors.EDITOR)
                    line(
                        "HEAD → Working tree · ${view.previewPath?.value ?: "Select a file"}",
                        bodyTop,
                        diffBounds.left,
                        diffBounds.right,
                        IdeColors.MUTED,
                    )
                    val diffLines =
                        view.result
                            ?.diff
                            ?.lineSequence()
                            ?.toList()
                            .orEmpty()
                    val scroll = view.previewScroll
                    diffLines.drop(scroll).forEachIndexed {
                        index,
                        text,
                        ->
                        line(text, bodyTop + 24 + index * 20, diffBounds.left, diffBounds.right, gitDiffColor(text), monospace = true)
                    }
                    gitPreviewBounds = diffBounds
                    gitPreviewScrollMaximum = ((diffLines.size * 20 - (bounds.bottom - contentTop - 24)) + 19).coerceAtLeast(0) / 20
                }
            }
            gitScrollMaximum = maximum
            gitFieldFocusScroll.replaceAll { _, scroll -> minOf(scroll, maximum) }
            view.menu?.let { menu ->
                for (index in hitTargets.indices) hitTargets[index] = hitTargets[index].copy(enabled = false)
                val popup = IdeRect(bounds.left + 6, bounds.top + 6, minOf(bounds.right - 6, bounds.left + 310), bounds.bottom - 6)
                panel(IdePanelKind.Dialog, popup, IdeColors.PANEL_ALT, Z_POPUP)
                var row = 0

                fun item(
                    label: String,
                    action: IdeHitAction = IdeHitAction.GitOperation,
                    command: IdeCommand? = null,
                    operation: GitOperation? = null,
                    enabled: Boolean = idle,
                ) {
                    val top = popup.top + 6 + (row++ - view.scroll) * 24
                    val rect = IdeRect(popup.left + 6, top, popup.right - 6, top + 22)
                    if (!popup.contains(rect)) return
                    target(action, rect, enabled, label, z = Z_POPUP_TARGET, gitOperation = operation, gitCommand = command)
                    ui(
                        IdeTextKind.Dialog,
                        label,
                        rect.left + 6,
                        font.centeredCellTop(rect),
                        if (enabled) IdeColors.TEXT else IdeColors.DISABLED,
                        rect,
                        Z_POPUP_TEXT,
                    )
                }
                item("Close", command = IdeCommand.GitMenu(null), enabled = true)
                if (menu == IdeGitMenu.Branches) {
                    item("New branch…", IdeHitAction.GitBranch)
                    status?.branches?.forEach { branch ->
                        item(
                            if (branch ==
                                status.branch
                            ) {
                                "● $branch"
                            } else {
                                branch
                            },
                            operation = GitOperation.SwitchBranch(branch),
                            enabled =
                                idle && branch != status.branch,
                        )
                    }
                } else {
                    item("Set origin URL…", IdeHitAction.GitRemote, enabled = idle && available)
                    item("Fetch", operation = GitOperation.Fetch, enabled = idle && available)
                    item("Update · fast-forward only", operation = GitOperation.Pull, enabled = idle && available)
                    item("Push", operation = GitOperation.Push, enabled = idle && available)
                    item(if (view.authenticated) "Replace HTTPS token…" else "HTTPS account…", IdeHitAction.GitAuthenticate)
                    if (view.authenticated) item("Forget HTTPS token", IdeHitAction.GitForgetCredentials)
                }
                gitPreviewBounds = null
                gitPreviewScrollMaximum = null
                gitFieldFocusScroll.clear()
                gitScrollMaximum = ((row * 24 - (popup.height - 12)) + 23).coerceAtLeast(0) / 24
            }
        }

        private fun gitDiffColor(line: String): Int =
            when {
                line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") -> IdeColors.MUTED
                line.startsWith("+") -> IdeColors.STRING
                line.startsWith("-") -> IdeColors.ERROR
                line.startsWith("@@") -> IdeColors.INFO
                else -> IdeColors.TEXT
            }

        private fun gitField(
            bounds: IdeRect,
            field: IdeGitField,
            value: IdeGitFieldView,
            focused: IdeGitField?,
            enabled: Boolean,
        ) {
            if (!geometry.editor.contains(bounds)) return
            fills += IdeFillDraw(IdeFillKind.Border, bounds, if (field == focused) IdeColors.ACCENT else IdeColors.DISABLED, Z_CONTENT + 1)
            val inside = IdeRect(bounds.left + 1, bounds.top + 1, bounds.right - 1, bounds.bottom - 1)
            fills += IdeFillDraw(IdeFillKind.Background, inside, IdeColors.EDITOR, Z_CONTENT + 2)
            val command = IdeCommand.GitFocusField(field)
            hitTargets +=
                IdeHitTarget(
                    IdeHitAction.GitOperation,
                    bounds,
                    enabled,
                    "Edit ${field.name}",
                    IdeFocusGroup.Page,
                    Z_TARGET,
                    gitCommand = command,
                )
            val lines = value.text.split('\n')
            val caretLine = value.text.substring(0, value.caret.coerceIn(0, value.text.length)).count { it == '\n' }
            val rows = ((inside.height - 4) / font.cellHeight).coerceAtLeast(1)
            val first = if (field == focused) (caretLine - rows + 1).coerceAtLeast(0) else 0
            val columns = ((inside.width - 8) / font.cellWidth).coerceAtLeast(1)
            var start = 0
            lines.forEachIndexed { index, raw ->
                if (index in first until first + rows) {
                    val caret = (value.caret - start).coerceIn(0, raw.length)
                    val before = raw.codePointCount(0, caret)
                    val scroll = if (field == focused && index == caretLine) (before - columns + 1).coerceAtLeast(0) else 0
                    val localStart = raw.offsetByCodePoints(0, scroll.coerceAtMost(raw.codePointCount(0, raw.length)))
                    val remaining = raw.codePointCount(localStart, raw.length)
                    val localEnd = raw.offsetByCodePoints(localStart, minOf(columns, remaining))
                    val rowTop = inside.top + 2 + (index - first) * font.cellHeight
                    val rect = IdeRect(inside.left + 4, rowTop, inside.right - 4, rowTop + font.cellHeight)
                    val range = EditorRange(start + localStart, start + localEnd)
                    hitTargets +=
                        IdeHitTarget(
                            IdeHitAction.GitOperation,
                            rect,
                            enabled,
                            "Edit ${field.name}",
                            IdeFocusGroup.Page,
                            Z_TARGET + 1,
                            gitCommand = command,
                            gitTextRange = range,
                        )
                    if (field == focused) {
                        value.selection?.let { selection ->
                            val selectedStart = maxOf(range.startUtf16, selection.startUtf16)
                            val selectedEnd = minOf(range.endUtf16, selection.endUtf16)
                            if (selectedEnd > selectedStart) {
                                val left = rect.left + raw.codePointCount(localStart, selectedStart - start) * font.cellWidth
                                val right = rect.left + raw.codePointCount(localStart, selectedEnd - start) * font.cellWidth
                                fills +=
                                    IdeFillDraw(
                                        IdeFillKind.Selection,
                                        IdeRect(left, rowTop, right, rowTop + font.cellHeight),
                                        IdeColors.SELECTION,
                                        Z_SELECTION,
                                    )
                            }
                        }
                        if (index == caretLine) {
                            val left = rect.left + (before - scroll) * font.cellWidth
                            fills +=
                                IdeFillDraw(
                                    IdeFillKind.Caret,
                                    IdeRect(left, rowTop, left + 1, rowTop + font.cellHeight),
                                    IdeColors.CARET,
                                    Z_CARET,
                                )
                        }
                    }
                    code(
                        IdeTextKind.Source,
                        raw.substring(localStart, localEnd).replace('\t', ' '),
                        rect.left,
                        rowTop + font.glyphDrawOffsetY,
                        IdeColors.TEXT,
                        inside,
                        sourceRange = range,
                    )
                }
                start += raw.length + 1
            }
        }

        fun tooltip(
            pointerX: Int,
            pointerY: Int,
        ) {
            val target =
                hitTargets
                    .asReversed()
                    .firstOrNull { it.tooltip != null && it.bounds.contains(pointerX, pointerY) } ?: return
            val diagnosticTargets = hitTargets.filter { it.diagnostic != null && it.bounds.contains(pointerX, pointerY) }
            if (diagnosticTargets.isNotEmpty()) {
                IdeDiagnosticTooltip.draw(this, geometry, diagnosticTargets)
                return
            }
            val value = checkNotNull(target.tooltip)
            val maximumWidth = (geometry.panel.width - TOOLTIP_MARGIN * 2).coerceAtLeast(1)
            val width = minOf(value.length * 6 + TOOLTIP_HORIZONTAL_PADDING * 2, maximumWidth)
            val left =
                (pointerX - width / 2).coerceIn(
                    geometry.panel.left + TOOLTIP_MARGIN,
                    geometry.panel.right - TOOLTIP_MARGIN - width,
                )
            val proposedTop = pointerY + TOOLTIP_POINTER_OFFSET
            val top =
                if (proposedTop + TOOLTIP_HEIGHT <= geometry.status.top) {
                    proposedTop
                } else {
                    pointerY - TOOLTIP_HEIGHT - TOOLTIP_POINTER_OFFSET
                }.coerceIn(geometry.panel.top + TOOLTIP_MARGIN, geometry.panel.bottom - TOOLTIP_MARGIN - TOOLTIP_HEIGHT)
            val bounds = IdeRect(left, top, left + width, top + TOOLTIP_HEIGHT)
            val visibleCharacters = ((width - TOOLTIP_HORIZONTAL_PADDING * 2) / 6).coerceAtLeast(0)
            panel(IdePanelKind.Tooltip, bounds, IdeColors.PANEL_ALT, Z_TOOLTIP)
            scissors += IdeScissorDraw(IdeScissorKind.Tooltip, bounds, Z_TOOLTIP)
            ui(
                IdeTextKind.Tooltip,
                value.take(visibleCharacters),
                bounds.left + TOOLTIP_HORIZONTAL_PADDING,
                font.centeredCellTop(bounds),
                clip = bounds,
                z = Z_TOOLTIP_TEXT,
            )
        }

        private fun tree(workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView) {
            val bounds = geometry.tree ?: return
            scissors += IdeScissorDraw(IdeScissorKind.Tree, bounds, Z_CLIP)
            val rows = (bounds.height - 4).coerceAtLeast(0) / UI_LINE_HEIGHT
            val fileColors = IdeGitFileColors(workspace.git.status ?: workspace.git.result?.status)
            workspace.explorerRows().drop(treeFirstRow).take(rows).forEachIndexed { index, row ->
                val y = bounds.top + 4 + index * UI_LINE_HEIGHT
                if (
                    row is IdeExplorerRow.ProjectEntry &&
                    row.entry.kind is ru.lazyhat.compukters.ide.project.tree.ProjectFileKind.Directory &&
                    row.entry.path == explorerDrag?.destination
                ) {
                    fills +=
                        IdeFillDraw(
                            IdeFillKind.DropTarget,
                            IdeRect(bounds.left, y, bounds.right, y + UI_LINE_HEIGHT),
                            IdeColors.DROP_TARGET,
                            Z_SELECTION,
                        )
                }
                val (label, depth, color) =
                    when (row) {
                        is IdeExplorerRow.ProjectRoot -> {
                            Triple("Project · ${row.name}", 0, IdeColors.TEXT)
                        }

                        is IdeExplorerRow.ProjectEntry -> {
                            val entry = row.entry
                            val marker = if (entry.kind is ru.lazyhat.compukters.ide.project.tree.ProjectFileKind.Directory) "▸ " else "  "
                            val selected = entry.path == selectedTreePath || entry.path == workspace.activeFile
                            if (selected) {
                                fills +=
                                    IdeFillDraw(
                                        IdeFillKind.Selection,
                                        IdeRect(bounds.left, y, bounds.right, y + UI_LINE_HEIGHT),
                                        IdeColors.SELECTION,
                                        Z_SELECTION,
                                    )
                            }
                            Triple(
                                marker + entry.path.value.substringAfterLast('/'),
                                entry.path.value.count {
                                    it == '/'
                                } + 1,
                                fileColors.color(entry.path.value),
                            )
                        }

                        is IdeExplorerRow.ComputerRoot -> {
                            Triple(computerRootLabel(row.state), 0, IdeColors.COMPUTER)
                        }

                        is IdeExplorerRow.ComputerEntry -> {
                            val marker =
                                when (val node = row.node) {
                                    is IdeComputerNode.File -> "  "
                                    is IdeComputerNode.Directory -> if (node.children is IdeComputerChildren.Unloaded) "▸ " else "▾ "
                                }
                            Triple(marker + row.node.name, row.depth, IdeColors.COMPUTER)
                        }
                    }
                ui(
                    IdeTextKind.TreeRow,
                    label,
                    bounds.left + 5 + depth * 8,
                    y,
                    color,
                    bounds,
                )
                if (row is IdeExplorerRow.ComputerRoot && row.state !is IdeComputerTreeState.NoTarget) {
                    val refresh = IdeRect(bounds.right - 48, y, bounds.right - 4, y + UI_LINE_HEIGHT)
                    target(IdeHitAction.RefreshComputer, refresh, row.state !is IdeComputerTreeState.Loading, "Refresh target filesystem")
                    ui(IdeTextKind.TreeRow, "Refresh", refresh.left + 3, y, IdeColors.MUTED, bounds)
                }
            }
            explorerDrag?.let { drag ->
                ui(
                    IdeTextKind.DragGhost,
                    drag.source.name,
                    drag.x.toInt() + 7,
                    drag.y.toInt() + 7,
                    if (drag.destination == null) IdeColors.MUTED else IdeColors.ACCENT,
                    geometry.viewport,
                    Z_DRAG,
                )
            }
        }

        private fun computerRootLabel(state: IdeComputerTreeState): String =
            when (state) {
                IdeComputerTreeState.NoTarget -> "Computer · No target"
                IdeComputerTreeState.Loading -> "Computer · Loading…"
                is IdeComputerTreeState.Available -> "Computer"
                is IdeComputerTreeState.Unavailable -> "Computer · Unavailable"
                is IdeComputerTreeState.TargetLost -> "Computer · Target lost"
            }

        private fun terminalTooltip(): String? =
            when (val state = terminalState) {
                IdeTerminalStatus.Opening -> "Opening target terminal…"
                is IdeTerminalStatus.Failed -> state.detail
                else -> null
            }

        fun terminalTool(targetState: IdeTargetState) {
            val attached = targetState.attachedTarget
            val enabled = attached?.capabilities?.terminal == true
            val tooltip =
                when {
                    attached == null -> NO_TARGET
                    !enabled -> TERMINAL_UNAVAILABLE
                    else -> terminalTooltip()
                }
            val bounds = stripeButton(2)
            target(IdeHitAction.Terminal, bounds, enabled, tooltip, selected = terminalVisible)
            toolIcon(bounds, ToolIcon.Terminal, if (enabled) IdeColors.TEXT else IdeColors.DISABLED)
        }

        private enum class ToolIcon { Project, Commit, Terminal, Problems, GitLog }

        private fun stripeButton(index: Int): IdeRect =
            IdeRect(
                geometry.toolStripe.left,
                geometry.content.top + index * 24,
                geometry.toolStripe.right,
                geometry.content.top + index * 24 + 22,
            )

        private fun toolWindows(workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView) {
            val project = stripeButton(0)
            target(IdeHitAction.ProjectTool, project, true, "Project", selected = geometry.treeVisible)
            toolIcon(project, ToolIcon.Project)
            val commit = stripeButton(1)
            target(IdeHitAction.GitToggle, commit, true, "Commit", selected = workspace.git.visible)
            toolIcon(commit, ToolIcon.Commit)
            val problems = stripeButton(3)
            if (geometry.toolStripe.contains(problems)) {
                target(
                    IdeHitAction.GitOperation,
                    problems,
                    true,
                    "Problems",
                    selected = workspace.bottom.tab == IdeBottomTab.Problems,
                    gitCommand = IdeCommand.BottomTab(if (workspace.bottom.tab == IdeBottomTab.Problems) null else IdeBottomTab.Problems),
                )
                toolIcon(problems, ToolIcon.Problems)
            }
            val log = IdeRect(geometry.toolStripe.left, geometry.status.top - 24, geometry.toolStripe.right, geometry.status.top - 2)
            target(
                IdeHitAction.GitOperation,
                log,
                true,
                "Git Log",
                selected = workspace.bottom.tab == IdeBottomTab.GitLog,
                gitCommand = IdeCommand.BottomTab(if (workspace.bottom.tab == IdeBottomTab.GitLog) null else IdeBottomTab.GitLog),
            )
            toolIcon(log, ToolIcon.GitLog)
        }

        /** Small vector icons stay crisp at the same UI scale as the existing controls. */
        private fun toolIcon(
            bounds: IdeRect,
            kind: ToolIcon,
            color: Int = IdeColors.TEXT,
        ) {
            val x = bounds.left + (bounds.width - 14) / 2
            val y = bounds.top + (bounds.height - 14) / 2

            fun line(
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
            ) {
                fills += IdeFillDraw(IdeFillKind.Border, IdeRect(x + left, y + top, x + right, y + bottom), color, Z_TEXT)
            }

            fun box(
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
            ) {
                line(left, top, right, top + 1)
                line(left, bottom - 1, right, bottom)
                line(left, top, left + 1, bottom)
                line(right - 1, top, right, bottom)
            }
            when (kind) {
                ToolIcon.Project -> {
                    box(1, 4, 13, 12)
                    line(1, 2, 6, 3)
                    line(1, 2, 2, 5)
                }

                ToolIcon.Commit -> {
                    box(1, 1, 13, 13)
                    line(4, 4, 10, 5)
                    line(4, 7, 10, 8)
                    line(4, 10, 8, 11)
                }

                ToolIcon.Terminal -> {
                    box(0, 1, 14, 13)
                    line(3, 4, 4, 5)
                    line(4, 5, 5, 6)
                    line(3, 6, 4, 7)
                    line(7, 8, 11, 9)
                }

                ToolIcon.Problems -> {
                    box(1, 1, 13, 13)
                    line(6, 3, 8, 8)
                    line(6, 10, 8, 12)
                }

                ToolIcon.GitLog -> {
                    line(3, 2, 4, 12)
                    box(1, 1, 6, 5)
                    box(1, 9, 6, 13)
                    line(8, 3, 13, 4)
                    line(8, 10, 13, 11)
                }
            }
        }

        private fun diagnostics(workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView) {
            val panelBounds = geometry.diagnostics ?: return
            val header = IdeRect(panelBounds.left, panelBounds.top, panelBounds.right, panelBounds.top + 22)

            fun tab(
                label: String,
                left: Int,
                right: Int,
                value: IdeBottomTab,
            ) {
                val rect = IdeRect(left, header.top, right, header.bottom)
                target(
                    IdeHitAction.GitOperation,
                    rect,
                    true,
                    label,
                    selected = workspace.bottom.tab == value,
                    gitCommand = IdeCommand.BottomTab(value),
                )
                ui(IdeTextKind.ToolWindow, label, left + 6, font.centeredCellTop(rect), clip = rect)
            }
            tab("Problems", header.left, header.left + 74, IdeBottomTab.Problems)
            tab("Git Log", header.left + 74, header.left + 140, IdeBottomTab.GitLog)
            val close = IdeRect(header.right - 22, header.top, header.right, header.bottom)
            target(IdeHitAction.GitOperation, close, true, "Hide tool window", gitCommand = IdeCommand.BottomTab(null))
            ui(IdeTextKind.ToolWindow, "×", close.left + 6, font.centeredCellTop(close))
            val bounds = IdeRect(panelBounds.left, header.bottom, panelBounds.right, panelBounds.bottom)
            scissors += IdeScissorDraw(IdeScissorKind.Diagnostics, bounds, Z_CLIP)
            if (workspace.bottom.tab == IdeBottomTab.GitLog) {
                val refresh = IdeRect(header.left + 146, header.top, header.left + 210, header.bottom)
                target(IdeHitAction.GitOperation, refresh, true, "Refresh Git Log", gitCommand = IdeCommand.BottomTab(IdeBottomTab.GitLog))
                ui(IdeTextKind.ToolWindow, "Refresh", refresh.left + 4, font.centeredCellTop(refresh), clip = refresh)
                val commits = workspace.git.history
                val count = ((bounds.height - UI_LINE_HEIGHT) / UI_LINE_HEIGHT).coerceAtLeast(0)
                bottomScrollMaximum = (commits.size - count).coerceAtLeast(0)
                val authorX = bounds.right - minOf(140, bounds.width / 3)
                ui(IdeTextKind.Diagnostic, "Hash      Commit", bounds.left + 6, bounds.top + 2, IdeColors.MUTED, bounds)
                ui(IdeTextKind.Diagnostic, "Author", authorX, bounds.top + 2, IdeColors.MUTED, bounds)
                commits.drop(workspace.bottom.scroll.coerceAtMost(bottomScrollMaximum)).take(count).forEachIndexed { index, commit ->
                    val top = bounds.top + (index + 1) * UI_LINE_HEIGHT
                    val messageBounds = IdeRect(bounds.left, top, authorX - 4, top + UI_LINE_HEIGHT)
                    ui(
                        IdeTextKind.Diagnostic,
                        "${commit.id.take(8)}  ${commit.message.lineSequence().firstOrNull().orEmpty()}",
                        bounds.left + 6,
                        top + 2,
                        clip = messageBounds,
                    )
                    ui(
                        IdeTextKind.Diagnostic,
                        commit.author,
                        authorX,
                        top + 2,
                        IdeColors.MUTED,
                        IdeRect(authorX, top, bounds.right, top + UI_LINE_HEIGHT),
                    )
                }
                if (commits.isEmpty()) {
                    ui(
                        IdeTextKind.Diagnostic,
                        "No commits to show",
                        bounds.left + 6,
                        bounds.top + UI_LINE_HEIGHT + 2,
                        IdeColors.MUTED,
                        bounds,
                    )
                }
                return
            }
            workspace.usages?.let { usages ->
                ui(
                    IdeTextKind.Diagnostic,
                    "Find Usages: ${usages.total} (${usages.rows.size} shown) — ↑↓ / Enter / Esc",
                    bounds.left + 6,
                    bounds.top + 4,
                    IdeColors.MUTED,
                    bounds,
                )
                val close = IdeRect(bounds.right - 24, bounds.top, bounds.right, bounds.top + UI_LINE_HEIGHT)
                target(IdeHitAction.UsagesClose, close, true, "Close Find Usages")
                ui(IdeTextKind.Diagnostic, "×", close.left + 6, font.centeredCellTop(close), IdeColors.MUTED, bounds)
                val count = ((bounds.height - UI_LINE_HEIGHT - 4) / UI_LINE_HEIGHT).coerceAtLeast(0)
                val first = (usages.selectedIndex - count + 1).coerceAtLeast(0)
                usages.rows.drop(first).take(count).forEachIndexed { visible, usage ->
                    val index = first + visible
                    val top = bounds.top + UI_LINE_HEIGHT + visible * UI_LINE_HEIGHT
                    val row = IdeRect(bounds.left, top, bounds.right, top + UI_LINE_HEIGHT)
                    if (index == usages.selectedIndex) fills += IdeFillDraw(IdeFillKind.Selection, row, IdeColors.SELECTION, Z_SELECTION)
                    ui(
                        IdeTextKind.Diagnostic,
                        "${usage.path.value}:${usage.line + 1}  ${usage.context}",
                        row.left + 6,
                        row.top + 4,
                        IdeColors.TEXT,
                        bounds,
                    )
                    hitTargets +=
                        IdeHitTarget(IdeHitAction.UsageChoice, row, true, "Open usage", IdeFocusGroup.Page, Z_TARGET, choiceIndex = index)
                }
                if (usages.rows.isEmpty()) {
                    ui(
                        IdeTextKind.Diagnostic,
                        "No usages found",
                        bounds.left + 6,
                        bounds.top + UI_LINE_HEIGHT + 4,
                        IdeColors.MUTED,
                        bounds,
                    )
                }
                return
            }
            val editor = workspace.editor as? IdeEditorView.Text
            val values = workspace.diagnostics.rows
            val selected =
                values.indexOfFirst {
                    it.navigable && it.diagnostic.path?.value == editor?.path?.value &&
                        it.diagnostic.range?.startUtf16 == editor?.caretUtf16
                }
            val count = ((bounds.height - 4) / UI_LINE_HEIGHT).coerceAtLeast(0)
            val first = (selected - count + 1).coerceAtLeast(0)
            values.drop(first).take(count).forEachIndexed { index, row ->
                val diagnostic = row.diagnostic
                val top = bounds.top + index * UI_LINE_HEIGHT
                val rowBounds = IdeRect(bounds.left, top, bounds.right, top + UI_LINE_HEIGHT)
                val location =
                    diagnostic.path
                        ?.value
                        ?.let { path -> "$path${row.line?.let { ":${it + 1}" }.orEmpty()}  " }
                        .orEmpty()
                val stale = if (diagnostic.range != null && !row.navigable) " [outdated]" else ""
                if (first + index == selected) fills += IdeFillDraw(IdeFillKind.Selection, rowBounds, IdeColors.SELECTION, Z_SELECTION)
                ui(
                    IdeTextKind.Diagnostic,
                    "$location${diagnostic.message}$stale",
                    bounds.left + 6,
                    bounds.top + 4 + index * UI_LINE_HEIGHT,
                    IdeSourceStyles.diagnosticColor(diagnostic.severity),
                    bounds,
                )
                hitTargets +=
                    IdeHitTarget(
                        IdeHitAction.DiagnosticChoice,
                        rowBounds,
                        row.navigable,
                        if (row.navigable) "Go to problem (F2 / Shift+F2)" else "No current source location",
                        IdeFocusGroup.Page,
                        Z_TARGET,
                        diagnostic = row,
                    )
            }
        }

        fun dialog(dialog: IdeDialogState) {
            for (index in hitTargets.indices) hitTargets[index] = hitTargets[index].copy(enabled = false)
            fills += IdeFillDraw(IdeFillKind.DialogScrim, geometry.panel, IdeColors.DIM, Z_DIALOG_SCRIM)
            val width = minOf(360, geometry.panel.width - 24).coerceAtLeast(0)
            val height = minOf(132, geometry.panel.height - 24).coerceAtLeast(0)
            val left = geometry.panel.left + (geometry.panel.width - width) / 2
            val top = geometry.panel.top + (geometry.panel.height - height) / 2
            val bounds = IdeRect(left, top, left + width, top + height)
            panel(IdePanelKind.Dialog, bounds, IdeColors.PANEL_ALT, Z_DIALOG)
            val (title, message) =
                when (dialog) {
                    is IdeDialogState.Confirmation -> {
                        dialog.title to dialog.message
                    }

                    is IdeDialogState.FileConflict -> {
                        "File conflict" to if (dialog.closing) "Discard changes to ${dialog.path.value} and close?" else dialog.path.value
                    }

                    is IdeDialogState.LockUpdate -> {
                        "Update lock" to dialog.projectDirectory
                    }

                    is IdeDialogState.TargetOverwrite -> {
                        "Replace target executable?" to "${dialog.path.value} changed at revision ${dialog.revision.generation}"
                    }

                    is IdeDialogState.ComputerImport -> {
                        "Replace project entry?" to dialog.destination.value
                    }
                }
            ui(IdeTextKind.Dialog, title, bounds.left + 10, bounds.top + 10, IdeColors.TEXT, bounds, Z_DIALOG_TEXT)
            ui(IdeTextKind.Dialog, message, bounds.left + 10, bounds.top + 30, IdeColors.MUTED, bounds, Z_DIALOG_TEXT)
            val dismiss = IdeRect(bounds.right - 78, bounds.bottom - 26, bounds.right - 10, bounds.bottom - 8)
            val confirm = IdeRect(dismiss.left - 76, dismiss.top, dismiss.left - 8, dismiss.bottom)
            target(IdeHitAction.Confirm, confirm, true, focusGroup = IdeFocusGroup.Dialog, z = Z_DIALOG_TARGET)
            target(IdeHitAction.Dismiss, dismiss, true, focusGroup = IdeFocusGroup.Dialog, z = Z_DIALOG_TARGET)
            ui(
                IdeTextKind.Dialog,
                if (dialog is IdeDialogState.FileConflict) {
                    if (dialog.closing) "Discard" else "Reload"
                } else {
                    "Confirm"
                },
                confirm.left + 9,
                font.centeredCellTop(confirm),
                z = Z_DIALOG_TEXT,
            )
            ui(IdeTextKind.Dialog, "Cancel", dismiss.left + 12, font.centeredCellTop(dismiss), z = Z_DIALOG_TEXT)
        }

        fun quickOpen(view: IdeQuickOpenView) {
            for (index in hitTargets.indices) hitTargets[index] = hitTargets[index].copy(enabled = false)
            fills += IdeFillDraw(IdeFillKind.DialogScrim, geometry.panel, IdeColors.DIM, Z_DIALOG_SCRIM)
            val picker = IdeQuickOpenGeometry.compute(geometry.panel)
            val bounds = picker.bounds
            panel(IdePanelKind.Dialog, bounds, IdeColors.PANEL_ALT, Z_DIALOG)
            ui(IdeTextKind.Dialog, "Open file", bounds.left + 12, bounds.top + 12, clip = bounds, z = Z_DIALOG_TEXT)
            val field = IdeTextFields.draw(view.field, picker.field, Z_DIALOG + 1, font)
            fills += field.fills
            text += field.text
            ui(
                IdeTextKind.Dialog,
                "${view.results.size} files shown · filter by name or path",
                bounds.left + 12,
                bounds.top + 62,
                IdeColors.MUTED,
                bounds,
                Z_DIALOG_TEXT,
            )
            if (view.results.isEmpty()) {
                ui(
                    IdeTextKind.Dialog,
                    "No matching files",
                    picker.list.left + 8,
                    picker.list.top + 10,
                    IdeColors.MUTED,
                    picker.list,
                    Z_DIALOG_TEXT,
                )
            }
            view.results.drop(view.first).take(picker.rows).forEachIndexed { index, path ->
                val top = picker.list.top + index * IdeQuickOpenGeometry.ROW_HEIGHT
                val row = IdeRect(picker.list.left, top, picker.list.right, top + IdeQuickOpenGeometry.ROW_HEIGHT)
                if (view.first + index == view.selected) {
                    fills += IdeFillDraw(IdeFillKind.Selection, row, IdeColors.SELECTION, Z_DIALOG + 2)
                    fills +=
                        IdeFillDraw(
                            IdeFillKind.Border,
                            IdeRect(row.left, row.top, row.left + 2, row.bottom),
                            IdeColors.ACCENT,
                            Z_DIALOG + 3,
                        )
                }
                ui(
                    IdeTextKind.Dialog,
                    path.value.substringAfterLast('/'),
                    row.left + 8,
                    font.centeredCellTop(row, rows = 2),
                    clip = row,
                    z = Z_DIALOG_TEXT,
                )
                ui(
                    IdeTextKind.Dialog,
                    path.value,
                    row.left + 8,
                    font.centeredCellTop(row, rows = 2) + font.cellHeight,
                    IdeColors.MUTED,
                    row,
                    Z_DIALOG_TEXT,
                )
            }
            ui(
                IdeTextKind.Dialog,
                "↑↓ select   Enter open   Esc cancel",
                bounds.left + 12,
                bounds.bottom - 17,
                IdeColors.MUTED,
                bounds,
                Z_DIALOG_TEXT,
            )
        }

        fun prompt(prompt: IdePromptState) {
            for (index in hitTargets.indices) hitTargets[index] = hitTargets[index].copy(enabled = false)
            fills += IdeFillDraw(IdeFillKind.DialogScrim, geometry.panel, IdeColors.DIM, Z_DIALOG_SCRIM)
            val promptGeometry = IdePromptGeometry.compute(geometry.panel)
            val bounds = promptGeometry.bounds
            panel(IdePanelKind.Dialog, bounds, IdeColors.PANEL_ALT, Z_DIALOG)
            ui(IdeTextKind.Dialog, prompt.title, bounds.left + 10, bounds.top + 10, clip = bounds, z = Z_DIALOG_TEXT)
            ui(IdeTextKind.Dialog, prompt.fieldLabel + ":", bounds.left + 10, bounds.top + 30, clip = bounds, z = Z_DIALOG_TEXT)
            val field = IdeTextFields.draw(prompt.field, promptGeometry.field, Z_DIALOG + 1, font)
            fills += field.fills
            text += field.text
            prompt.error?.let { ui(IdeTextKind.Dialog, it, bounds.left + 10, bounds.top + 76, IdeColors.ERROR, bounds, Z_DIALOG_TEXT) }
            val dismiss = IdeRect(bounds.right - 78, bounds.bottom - 26, bounds.right - 10, bounds.bottom - 8)
            val confirm = IdeRect(dismiss.left - 76, dismiss.top, dismiss.left - 8, dismiss.bottom)
            target(IdeHitAction.Confirm, confirm, true, focusGroup = IdeFocusGroup.Dialog, z = Z_DIALOG_TARGET)
            target(IdeHitAction.Dismiss, dismiss, true, focusGroup = IdeFocusGroup.Dialog, z = Z_DIALOG_TARGET)
            ui(IdeTextKind.Dialog, "Confirm", confirm.left + 9, font.centeredCellTop(confirm), z = Z_DIALOG_TEXT)
            ui(IdeTextKind.Dialog, "Cancel", dismiss.left + 12, font.centeredCellTop(dismiss), z = Z_DIALOG_TEXT)
        }

        private fun checkbox(
            bounds: IdeRect,
            checked: Boolean,
            enabled: Boolean,
        ) {
            val size = 10
            val left = bounds.left + (bounds.width - size) / 2
            val top = bounds.top + (bounds.height - size) / 2
            val color = if (enabled && checked) IdeColors.ACCENT else IdeColors.MUTED
            val edges =
                listOf(
                    IdeRect(left, top, left + size, top + 1),
                    IdeRect(left, top + size - 1, left + size, top + size),
                    IdeRect(left, top + 1, left + 1, top + size - 1),
                    IdeRect(left + size - 1, top + 1, left + size, top + size - 1),
                )
            edges.forEach { fills += IdeFillDraw(IdeFillKind.CheckboxBorder, it, color, Z_TEXT) }
            if (checked) {
                listOf(2 to 5, 3 to 6, 4 to 5, 5 to 4, 6 to 3).forEach { (x, y) ->
                    fills += IdeFillDraw(IdeFillKind.CheckboxMark, IdeRect(left + x, top + y, left + x + 2, top + y + 2), color, Z_TEXT)
                }
            }
        }
    }

    private fun expand(
        bounds: IdeRect,
        amount: Int,
    ): IdeRect =
        IdeRect(
            bounds.left - amount,
            bounds.top - amount,
            bounds.right + amount,
            bounds.bottom + amount,
        )

    private fun IdeRect.contains(
        x: Int,
        y: Int,
    ): Boolean = x >= left && x < right && y >= top && y < bottom

    private fun problemColor(name: String): Int =
        when (name) {
            "Error" -> IdeColors.ERROR
            "Warning" -> IdeColors.WARNING
            else -> IdeColors.INFO
        }

    private const val UI_LINE_HEIGHT = 12
    private const val PROJECT_SWITCHER_WIDTH = 540
    private const val PROJECT_ROW_HEIGHT = 40
    private const val PROJECT_ACTION_HEIGHT = 18
    private const val TOOLTIP_MARGIN = 4
    private const val TOOLTIP_HORIZONTAL_PADDING = 5
    private const val TOOLTIP_POINTER_OFFSET = 12
    private const val TOOLTIP_HEIGHT = 18
    private const val NO_TARGET = "No target attached"
    private const val TERMINAL_UNAVAILABLE = "Target terminal is unavailable"
}

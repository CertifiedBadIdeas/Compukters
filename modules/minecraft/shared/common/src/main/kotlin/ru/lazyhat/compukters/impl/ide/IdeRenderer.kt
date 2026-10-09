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

import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.analysis.SemanticCategory
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeDeclarationTarget
import ru.lazyhat.compukters.ide.client.analysis.IdeParameterInfoState
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticAnchor
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticInteraction
import ru.lazyhat.compukters.ide.client.build.IdeBuildState
import ru.lazyhat.compukters.ide.client.files.IdeComputerChildren
import ru.lazyhat.compukters.ide.client.files.IdeComputerNode
import ru.lazyhat.compukters.ide.client.files.IdeComputerTransferState
import ru.lazyhat.compukters.ide.client.files.IdeComputerTreeState
import ru.lazyhat.compukters.ide.client.git.IdeGitField
import ru.lazyhat.compukters.ide.client.git.IdeGitFieldView
import ru.lazyhat.compukters.ide.client.search.IdeFindView
import ru.lazyhat.compukters.ide.client.state.IdeBottomTab
import ru.lazyhat.compukters.ide.client.state.IdeBusyOperation
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeDiagnostics
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdeGitMenu
import ru.lazyhat.compukters.ide.client.state.IdeGitTab
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.state.IdeToolingState
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeTargetState
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.git.GitOperation
import ru.lazyhat.compukters.ide.highlight.KotlinLexicalKind
import ru.lazyhat.compukters.ide.project.fs.ProjectPath

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
            )
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
            is IdePageState.Start -> output.start(page, state.target, state.busy)
            is IdePageState.Workspace -> output.workspace(page.value, state.target, state.tooling, state.busy, caretVisible)
        }
        output.terminalTool(state.target)
        output.scaleControls(uiScale, enabled = prompt == null && state.dialog == null)
        val dialog = state.dialog
        if (prompt != null) {
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
        private val font: IdeCodeFontProfile,
        private val treeFirstRow: Int,
        private val selectedTreePath: ProjectPath?,
        private val terminalState: IdeTerminalStatus,
        private val terminalVisible: Boolean,
        private val explorerDrag: IdeExplorerDragVisual?,
        private val projectSwitcherOpen: Boolean,
        private val projectFirstRow: Int,
    ) {
        private val panels = mutableListOf<IdePanelDraw>()
        private val text = mutableListOf<IdeTextDraw>()
        private val fills = mutableListOf<IdeFillDraw>()
        private val scissors = mutableListOf<IdeScissorDraw>()
        private val hitTargets = mutableListOf<IdeHitTarget>()
        private val icons = mutableListOf<IdeIconDraw>()
        private var gitScrollMaximum: Int? = null
        private var gitPreviewScrollMaximum: Int? = null
        private var gitPreviewBounds: IdeRect? = null
        private val gitFieldFocusScroll = mutableMapOf<IdeGitField, Int>()
        private var bottomScrollMaximum = 0

        fun base(startPage: Boolean) {
            fills += IdeFillDraw(IdeFillKind.Background, geometry.viewport, IdeColors.DIM, Z_BACKGROUND)
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
            ui(IdeTextKind.Header, "Scale", first.left - 36, first.top + 4, z = Z_POPUP_TEXT)
            options.forEach { (mode, bounds) ->
                target(mode.action, bounds, enabled, "IDE scale: ${mode.label}", z = Z_POPUP_TARGET, selected = mode == scale)
                ui(IdeTextKind.Header, mode.label, bounds.left + 3, bounds.top + 4, clip = bounds, z = Z_POPUP_TEXT)
            }
        }

        fun start(
            page: IdePageState.Start,
            targetState: IdeTargetState,
            busy: Set<IdeBusyOperation>,
        ) {
            ui(IdeTextKind.Header, "Compukters IDE · ${targetLabel(targetState)}", geometry.header.left + 6, geometry.header.top + 7)
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
                ui(IdeTextKind.ProjectAction, label, actionBounds.left + 4, actionBounds.top + 4, clip = actionBounds)
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
            if (busy.isNotEmpty()) ui(IdeTextKind.Status, "Opening project…", geometry.status.left + 6, geometry.status.top + 5)
            page.error?.let {
                ui(
                    IdeTextKind.Status,
                    it.message,
                    geometry.status.left + 6,
                    geometry.status.top + 5,
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
            val projectTitle = "Projects · ${workspace.project.displayName}"
            val projectControl = projectControl(projectTitle)
            target(IdeHitAction.ProjectSwitcher, projectControl, true, "Choose or manage projects", selected = projectSwitcherOpen)
            val projectLabelWidth = ((projectControl.width - 18) / 6).coerceAtLeast(1)
            val projectLabel = projectTitle.take(projectLabelWidth)
            ui(IdeTextKind.Header, "$projectLabel ▾", projectControl.left + 5, projectControl.top + 5)
            val active = workspace.activeFile?.value ?: "No file"
            val fileColors = IdeGitFileColors(workspace.git.status ?: workspace.git.result?.status)
            val activeLeft = projectControl.right + 7
            val activeWidth = ((geometry.header.right - IdeUiScaleControl.WIDTH - activeLeft - 6) / font.cellWidth).coerceAtLeast(0)
            val targetText = " · ${targetLabel(targetState)}"
            val visibleActive = active.take((activeWidth - targetText.length).coerceAtLeast(0))
            ui(IdeTextKind.Header, visibleActive, activeLeft, geometry.header.top + 7, fileColors.color(active), geometry.header)
            ui(
                IdeTextKind.Header,
                targetText,
                activeLeft + visibleActive.length * font.cellWidth,
                geometry.header.top + 7,
                IdeColors.MUTED,
                geometry.header,
            )
            toolbar(workspace, targetState, toolingState, busy, workspace.activeFile != null || selectedTreePath != null)
            toolWindows(workspace)
            tree(workspace)
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
                        editor(editor, caretVisible, workspace.diagnostics)
                    }
                }
            }
            diagnostics(workspace)
            status(workspace, targetState, toolingState, busy)
            if (projectSwitcherOpen) projectSwitcher(workspace, projectControl)
        }

        private fun projectControl(displayName: String): IdeRect {
            val maximum = minOf(PROJECT_SWITCHER_WIDTH, (geometry.header.width / 2).coerceAtLeast(PROJECT_CONTROL_MINIMUM_WIDTH))
            val width = (displayName.length * 6 + 22).coerceIn(PROJECT_CONTROL_MINIMUM_WIDTH, maximum)
            return IdeRect(geometry.header.left + 4, geometry.header.top + 3, geometry.header.left + 4 + width, geometry.header.bottom - 3)
        }

        private fun projectSwitcher(
            workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView,
            control: IdeRect,
        ) {
            val availableHeight = (geometry.status.top - geometry.header.bottom - 4).coerceAtLeast(PROJECT_ROW_HEIGHT)
            val projectRows = ((availableHeight - 4 * PROJECT_ACTION_HEIGHT) / PROJECT_ROW_HEIGHT).coerceAtLeast(0)
            val first = projectFirstRow.coerceIn(0, (workspace.projects.size - projectRows).coerceAtLeast(0))
            val visibleProjects = workspace.projects.drop(first).take(projectRows)
            val width = minOf(PROJECT_SWITCHER_WIDTH, geometry.panel.right - control.left - 4).coerceAtLeast(control.width)
            val bounds =
                IdeRect(
                    control.left,
                    geometry.header.bottom,
                    control.left + width,
                    geometry.header.bottom + visibleProjects.size * PROJECT_ROW_HEIGHT + 4 * PROJECT_ACTION_HEIGHT + 2,
                )
            panel(IdePanelKind.ProjectSwitcher, bounds, IdeColors.PANEL_ALT, Z_PROJECT_SWITCHER)
            scissors += IdeScissorDraw(IdeScissorKind.ProjectSwitcher, bounds, Z_PROJECT_SWITCHER)
            visibleProjects.forEachIndexed { index, project ->
                val row =
                    IdeRect(
                        bounds.left + 1,
                        bounds.top + 1 + index * PROJECT_ROW_HEIGHT,
                        bounds.right - 1,
                        bounds.top + 1 + (index + 1) * PROJECT_ROW_HEIGHT,
                    )
                projectRow(
                    project,
                    first + index,
                    row,
                    true,
                    project.directoryName == workspace.project.directoryName,
                    Z_PROJECT_SWITCHER_TARGET,
                    Z_PROJECT_SWITCHER_TEXT,
                )
            }
            listOf(
                IdeHitAction.CreateProject to "+ New project",
                IdeHitAction.OpenExisting to "Open existing directory",
                IdeHitAction.CloneProject to "Clone HTTPS repository",
                IdeHitAction.GitAuthenticate to "HTTPS token · session only",
            ).forEachIndexed { index, (action, label) ->
                val top = bounds.top + 1 + visibleProjects.size * PROJECT_ROW_HEIGHT + index * PROJECT_ACTION_HEIGHT
                val row = IdeRect(bounds.left + 1, top, bounds.right - 1, top + PROJECT_ACTION_HEIGHT)
                target(action, row, true, z = Z_PROJECT_SWITCHER_TARGET)
                ui(IdeTextKind.ProjectAction, label, row.left + 6, row.top + 5, clip = bounds, z = Z_PROJECT_SWITCHER_TEXT)
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
        ) {
            val actionsWidth = if (project.external) 112 else 158
            val open = IdeRect(row.left, row.top, row.right - actionsWidth - 6, row.bottom - 2)
            target(
                IdeHitAction.ProjectChoice,
                open,
                enabled && !selected,
                project.path,
                z = targetZ,
                selected = selected,
                choiceIndex = index,
            )
            ui(
                IdeTextKind.ProjectChoice,
                if (project.external) "External · ${project.displayName}" else "${project.displayName} · IDE folder",
                open.left + 5,
                open.top + 4,
                clip = open,
                z = textZ,
            )
            ui(IdeTextKind.ProjectChoice, project.path, open.left + 5, open.top + 19, IdeColors.MUTED, clip = open, z = textZ)
            var left = open.right + 4
            if (!project.external) {
                val rename = IdeRect(left, row.top + 4, left + 60, row.bottom - 6)
                target(IdeHitAction.RenameProjectFolder, rename, enabled, "Rename project folder", z = targetZ, choiceIndex = index)
                ui(IdeTextKind.ProjectAction, "Rename…", rename.left + 4, rename.top + 5, clip = rename, z = textZ)
                left = rename.right + 4
            }
            val remove = IdeRect(left, row.top + 4, row.right, row.bottom - 6)
            val label = if (project.external) "Remove from list" else "Delete folder…"
            target(
                if (project.external) IdeHitAction.ForgetExternalProject else IdeHitAction.DeleteProjectFolder,
                remove,
                enabled,
                if (project.external) "Remove from list; files are kept" else "Delete folder and all files after confirmation",
                z = targetZ,
                choiceIndex = index,
            )
            ui(IdeTextKind.ProjectAction, label, remove.left + 4, remove.top + 5, clip = remove, z = textZ)
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
                    ui(IdeTextKind.Toolbar, label, rect.left + 6, rect.top + 6, if (enabled) IdeColors.TEXT else IdeColors.DISABLED, rect)
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
                        heading.top + 5,
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
                        ui(IdeTextKind.Source, change.path.value, row.left + 26, row.top + 5, color, row)
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
                        rect.top + 6,
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

        private fun toolbar(
            workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView,
            targetState: IdeTargetState,
            toolingState: IdeToolingState,
            busy: Set<IdeBusyOperation>,
            hasActiveEntry: Boolean,
        ) {
            var left = geometry.toolbar.left + 6

            fun action(
                icon: IdeIconKind,
                action: IdeHitAction,
                enabled: Boolean = true,
                tooltip: String,
                selected: Boolean = false,
            ) {
                val bounds = IdeRect(left, geometry.toolbar.top + 3, left + TOOLBAR_ICON_CONTROL_WIDTH, geometry.toolbar.bottom - 3)
                target(action, bounds, enabled, tooltip, selected = selected)
                icons += IdeIconDraw(icon, bounds, if (enabled) IdeColors.TEXT else IdeColors.DISABLED, Z_TEXT)
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
        }

        fun tooltip(
            pointerX: Int,
            pointerY: Int,
        ) {
            val target =
                hitTargets
                    .asReversed()
                    .firstOrNull { it.tooltip != null && it.bounds.contains(pointerX, pointerY) } ?: return
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
                bounds.top + 5,
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

        private fun editor(
            editor: IdeEditorView.Text,
            caretVisible: Boolean,
            diagnostics: IdeDiagnostics,
        ) {
            val bounds = geometry.editor
            editor.find?.let { findBar(it) }
            scissors += IdeScissorDraw(IdeScissorKind.Editor, bounds, Z_CLIP)
            val rows = minOf(geometry.codeRows, editor.visibleLines.size)
            val gutterDigits =
                editor.totalLines
                    .toString()
                    .length
                    .coerceAtLeast(2)
            val codeLeft = bounds.left + (gutterDigits + 2) * font.cellWidth
            val counts = (editor.analysis as? IdeAnalysisState.Active)?.presentation?.methodUsages.orEmpty()
            val completion = (editor.analysis as? IdeAnalysisState.Active)?.completion
            var usageIndex = 0
            repeat(rows) { visibleIndex ->
                val lineNumber = editor.firstVisibleLine + visibleIndex
                val line = editor.visibleLines[visibleIndex]
                val lineStart = editor.visibleLineStartsUtf16[visibleIndex]
                val rowTop = bounds.top + visibleIndex * font.cellHeight
                val y = rowTop + font.glyphDrawOffsetY
                val completionRange =
                    completion?.replacement?.let { range ->
                        val localStart = range.startUtf16 - lineStart
                        val start = if (localStart > 0 && line.getOrNull(localStart - 1) == '.') range.startUtf16 - 1 else range.startUtf16
                        EditorRange(start, range.endUtf16)
                    }
                val diagnostic =
                    diagnostics.rows
                        .filter { it.navigable && it.diagnostic.path?.value == editor.path?.value && it.line == lineNumber }
                        .filterNot { row ->
                            val range = row.diagnostic.range
                            if (range == null || completionRange == null) return@filterNot false
                            val overlaps = range.startUtf16 < completionRange.endUtf16 && completionRange.startUtf16 < range.endUtf16
                            overlaps
                        }.maxByOrNull { it.diagnostic.severity.ordinal }
                diagnostic?.let { row ->
                    val left = bounds.left + gutterDigits * font.cellWidth
                    fills +=
                        IdeFillDraw(
                            IdeFillKind.DiagnosticMarker,
                            IdeRect(left + 3, rowTop + 3, left + 7, rowTop + font.cellHeight - 3),
                            diagnosticColor(row.diagnostic.severity),
                            Z_CONTENT,
                        )
                    hitTargets +=
                        IdeHitTarget(
                            IdeHitAction.DiagnosticChoice,
                            IdeRect(left, rowTop, codeLeft, rowTop + font.cellHeight),
                            true,
                            row.diagnostic.message,
                            IdeFocusGroup.Page,
                            Z_TARGET,
                            diagnostic = row,
                        )
                }
                val nextLineStart = editor.visibleLineStartsUtf16.getOrNull(visibleIndex + 1)
                val caretBelongsToLine =
                    editor.caretUtf16 >= lineStart &&
                        (nextLineStart?.let { editor.caretUtf16 < it } ?: (editor.caretUtf16 <= lineStart + line.length))
                if (caretBelongsToLine) {
                    fills +=
                        IdeFillDraw(
                            IdeFillKind.CurrentLine,
                            IdeRect(bounds.left, rowTop, bounds.right, rowTop + font.cellHeight),
                            IdeColors.CURRENT_LINE,
                            Z_SELECTION - 2,
                        )
                }
                code(
                    IdeTextKind.LineNumber,
                    (lineNumber + 1).toString().padStart(gutterDigits),
                    bounds.left,
                    y,
                    IdeColors.LINE_NUMBER,
                    bounds,
                )
                editor.gitLineChanges.forEach { change ->
                    val deleted = change.kind == ru.lazyhat.compukters.ide.git.GitLineChangeKind.Deleted
                    val anchor = change.firstLine.coerceAtMost(editor.totalLines - 1)
                    val applies =
                        if (deleted) {
                            lineNumber == anchor
                        } else {
                            lineNumber >= change.firstLine &&
                                lineNumber.toLong() < change.firstLine.toLong() + change.lineCount
                        }
                    if (applies) {
                        val top = if (deleted && change.firstLine >= editor.totalLines) rowTop + font.cellHeight - 2 else rowTop
                        val marker = IdeRect(codeLeft - 5, top, codeLeft - 2, if (deleted) top + 2 else rowTop + font.cellHeight)
                        val color =
                            when (change.kind) {
                                ru.lazyhat.compukters.ide.git.GitLineChangeKind.Added -> IdeColors.GIT_ADDED
                                ru.lazyhat.compukters.ide.git.GitLineChangeKind.Modified -> IdeColors.GIT_MODIFIED
                                ru.lazyhat.compukters.ide.git.GitLineChangeKind.Deleted -> IdeColors.GIT_DELETED
                            }
                        fills += IdeFillDraw(IdeFillKind.GitChange, marker, color, Z_TEXT)
                    }
                }
                occurrenceHighlights(editor, line, lineStart, codeLeft, rowTop)
                selection(editor, line, lineStart, codeLeft, rowTop)
                styledLine(editor, lineNumber, line, lineStart, codeLeft, y)
                while (usageIndex < counts.size && counts[usageIndex].range.startUtf16 < lineStart) usageIndex++
                var usageX = codeLeft + (visualColumns(line) - editor.firstVisibleColumn + 2) * font.cellWidth
                while (usageIndex < counts.size && counts[usageIndex].range.startUtf16 < lineStart + line.length) {
                    val index = usageIndex++
                    val usage = counts[index]
                    if (usage.count == 0) continue
                    val label = "${usage.count} ${if (usage.count == 1) "usage" else "usages"}"
                    val x = usageX
                    val right = x + label.length * font.cellWidth
                    if (x >= codeLeft && right <= bounds.right - font.cellWidth) {
                        code(IdeTextKind.MethodUsageCount, label, x, y, IdeColors.MUTED, bounds)
                        hitTargets +=
                            IdeHitTarget(
                                IdeHitAction.MethodUsages,
                                IdeRect(x, rowTop, right, rowTop + font.cellHeight),
                                true,
                                "Find Usages (Alt+F7)",
                                IdeFocusGroup.Page,
                                Z_TARGET,
                                choiceIndex = index,
                            )
                    }
                    usageX = right + 2 * font.cellWidth
                }
                if (caretVisible && caretBelongsToLine) {
                    val local = (editor.caretUtf16 - lineStart).coerceAtMost(line.length)
                    val x = codeLeft + (visualColumns(line.substring(0, local)) - editor.firstVisibleColumn) * font.cellWidth
                    fills += IdeFillDraw(IdeFillKind.Caret, IdeRect(x, rowTop, x + 1, rowTop + font.cellHeight), IdeColors.CARET, Z_CARET)
                }
            }
            val active = editor.analysis as? IdeAnalysisState.Active
            when (val interaction = active?.interaction) {
                is IdeSemanticInteraction.Chooser -> {
                    semanticChooser(editor, codeLeft, interaction)
                }

                else -> {
                    val parameterInfo = active?.parameterInfo
                    if (parameterInfo != null) {
                        parameterInfo(editor, codeLeft, parameterInfo)
                    } else {
                        completion(editor, codeLeft)
                    }
                    if (active?.completion == null && active?.parameterInfo == null && interaction is IdeSemanticInteraction.Hover) {
                        semanticHover(editor, codeLeft, interaction)
                    }
                }
            }
        }

        private fun selection(
            editor: IdeEditorView.Text,
            line: String,
            lineStart: Int,
            codeLeft: Int,
            rowTop: Int,
        ) {
            val start = editor.selectionStartUtf16 ?: return
            val end = editor.selectionEndUtf16 ?: return
            val localStart = (start - lineStart).coerceIn(0, line.length)
            val localEnd = (end - lineStart).coerceIn(0, line.length)
            if (localEnd <= localStart) return
            val left = codeLeft + (visualColumns(line.substring(0, localStart)) - editor.firstVisibleColumn) * font.cellWidth
            val right = codeLeft + (visualColumns(line.substring(0, localEnd)) - editor.firstVisibleColumn) * font.cellWidth
            fills +=
                IdeFillDraw(
                    IdeFillKind.Selection,
                    IdeRect(left, rowTop, right, rowTop + font.cellHeight),
                    IdeColors.SELECTION,
                    Z_SELECTION,
                )
        }

        private fun findBar(find: IdeFindView) {
            val bounds = geometry.findBar ?: return
            panel(IdePanelKind.Control, bounds, IdeColors.PANEL_ALT)
            val field = IdeRect(bounds.left + 4, bounds.top + 3, bounds.right - 166, bounds.bottom - 3)
            fills += IdeFillDraw(IdeFillKind.Border, field, if (find.focused) IdeColors.ACCENT else IdeColors.BORDER, Z_CONTENT + 1)
            val inside = IdeRect(field.left + 1, field.top + 1, field.right - 1, field.bottom - 1)
            fills += IdeFillDraw(IdeFillKind.Background, inside, IdeColors.EDITOR, Z_CONTENT + 2)
            target(IdeHitAction.FindFocus, field, true, "Find in current file (Ctrl+F)")
            val columns = ((inside.width - 6) / font.cellWidth).coerceAtLeast(1)
            val beforeCaret = visualColumns(find.query.substring(0, find.queryCaretUtf16))
            val scroll = (beforeCaret - columns + 1).coerceAtLeast(0)
            val queryGlyphs = projectGlyphs(find.query)
            val queryStart = queryGlyphs.offsetByCodePoints(0, minOf(scroll, queryGlyphs.codePointCount(0, queryGlyphs.length)))
            val visibleQuery = queryGlyphs.substring(queryStart)
            val x = inside.left + 3
            val y = inside.top + 1 + font.glyphDrawOffsetY
            if (find.focused) {
                find.querySelection?.let { selection ->
                    val start = visualColumns(find.query.substring(0, selection.startUtf16)) - scroll
                    val end = visualColumns(find.query.substring(0, selection.endUtf16)) - scroll
                    val left = (x + start * font.cellWidth).coerceIn(inside.left, inside.right)
                    val right = (x + end * font.cellWidth).coerceIn(left, inside.right)
                    fills +=
                        IdeFillDraw(
                            IdeFillKind.Selection,
                            IdeRect(left, inside.top, right, inside.bottom),
                            IdeColors.SELECTION,
                            Z_SELECTION,
                        )
                }
                val caretX = x + (beforeCaret - scroll) * font.cellWidth
                if (caretX in inside.left until inside.right) {
                    fills +=
                        IdeFillDraw(IdeFillKind.Caret, IdeRect(caretX, inside.top, caretX + 1, inside.bottom), IdeColors.CARET, Z_CARET)
                }
            }
            code(IdeTextKind.Find, visibleQuery.ifEmpty { if (find.focused) "" else "Find" }, x, y, IdeColors.TEXT, inside)
            val count = "${find.selectedIndex + 1}/${find.matches.size}"
            val counter = IdeRect(bounds.right - 160, bounds.top, bounds.right - 76, bounds.bottom)
            ui(
                IdeTextKind.Find,
                count,
                counter.left,
                bounds.top + 8,
                if (find.query.isNotEmpty() &&
                    find.matches.isEmpty()
                ) {
                    IdeColors.ERROR
                } else {
                    IdeColors.MUTED
                },
                clip = counter,
            )
            val previous = IdeRect(bounds.right - 72, bounds.top + 3, bounds.right - 50, bounds.bottom - 3)
            val next = IdeRect(bounds.right - 48, previous.top, bounds.right - 26, previous.bottom)
            val close = IdeRect(bounds.right - 24, previous.top, bounds.right - 2, previous.bottom)
            target(IdeHitAction.FindPrevious, previous, find.matches.isNotEmpty(), "Previous match (Shift+Enter)")
            target(IdeHitAction.FindNext, next, find.matches.isNotEmpty(), "Next match (Enter)")
            target(IdeHitAction.FindClose, close, true, "Close search (Escape)")
            ui(IdeTextKind.Find, "↑", previous.left + 7, previous.top + 5)
            ui(IdeTextKind.Find, "↓", next.left + 7, next.top + 5)
            ui(IdeTextKind.Find, "×", close.left + 7, close.top + 5)
        }

        private fun occurrenceHighlights(
            editor: IdeEditorView.Text,
            line: String,
            lineStart: Int,
            codeLeft: Int,
            rowTop: Int,
        ) {
            val searching = editor.find != null
            val matches = editor.find?.matches ?: editor.occurrenceRanges
            val insertion = matches.binarySearch { if (it.endUtf16 <= lineStart) -1 else 1 }
            var index = -insertion - 1
            while (index < matches.size && matches[index].startUtf16 < lineStart + line.length) {
                val match = matches[index++]
                val start = (match.startUtf16 - lineStart).coerceIn(0, line.length)
                val end = (match.endUtf16 - lineStart).coerceIn(start, line.length)
                val left =
                    (
                        codeLeft + (
                            visualColumns(
                                line.substring(0, start),
                            ) - editor.firstVisibleColumn
                        ) * font.cellWidth
                    ).coerceIn(codeLeft, geometry.editor.right)
                val right =
                    (
                        codeLeft + (
                            visualColumns(
                                line.substring(0, end),
                            ) - editor.firstVisibleColumn
                        ) * font.cellWidth
                    ).coerceIn(left, geometry.editor.right)
                fills +=
                    IdeFillDraw(
                        if (searching) IdeFillKind.SearchMatch else IdeFillKind.WordOccurrence,
                        IdeRect(left, rowTop, right, rowTop + font.cellHeight),
                        if (searching) IdeColors.SEARCH_MATCH else IdeColors.WORD_OCCURRENCE,
                        Z_SELECTION - 1,
                    )
            }
        }

        private fun styledLine(
            editor: IdeEditorView.Text,
            lineIndex: Int,
            line: String,
            lineStart: Int,
            codeLeft: Int,
            y: Int,
        ) {
            if (line.isEmpty()) return
            val lexical = editor.lexical.lines.getOrNull(lineIndex)
            val semantic = (editor.analysis as? IdeAnalysisState.Active)?.presentation
            val link =
                ((editor.analysis as? IdeAnalysisState.Active)?.interaction as? IdeSemanticInteraction.Link)
                    ?.takeIf { it.anchor.path.value == editor.path?.value }
                    ?.anchor
            val projectPath = editor.path
            val boundaries = sortedSetOf(0, line.length)
            lexical?.spans?.forEach { span ->
                boundaries += span.startUtf16.coerceIn(0, line.length)
                boundaries += span.endUtf16.coerceIn(0, line.length)
            }
            (editor.analysis as? IdeAnalysisState.Active)
                ?.presentation
                ?.semanticTokens
                ?.filter { projectPath != null && it.path.value == projectPath.value }
                ?.forEach { token ->
                    boundaries += (token.range.startUtf16 - lineStart).coerceIn(0, line.length)
                    boundaries += (token.range.endUtf16 - lineStart).coerceIn(0, line.length)
                }
            link?.tokenRange?.let { range ->
                boundaries += (range.startUtf16 - lineStart).coerceIn(0, line.length)
                boundaries += (range.endUtf16 - lineStart).coerceIn(0, line.length)
            }
            boundaries.zipWithNext().forEach { (start, end) ->
                if (end <= start) return@forEach
                val lexicalKind = lexical?.spans?.firstOrNull { start >= it.startUtf16 && start < it.endUtf16 }?.kind
                val semanticToken =
                    semantic
                        ?.semanticTokens
                        ?.firstOrNull { token ->
                            projectPath != null &&
                                token.path.value == projectPath.value &&
                                token.category.contributesTextStyle() &&
                                lineStart + start in token.range.startUtf16 until token.range.endUtf16
                        }
                val resolved =
                    semanticToken?.let { IdeTextStyle.Semantic(it.category, it.isMutable) }
                        ?: lexicalKind?.let(IdeTextStyle::Lexical)
                        ?: IdeTextStyle.Plain
                val x = codeLeft + (visualColumns(line.substring(0, start)) - editor.firstVisibleColumn) * font.cellWidth
                val absoluteRange = EditorRange(lineStart + start, lineStart + end)
                val linked =
                    link?.tokenRange?.let { absoluteRange.startUtf16 >= it.startUtf16 && absoluteRange.endUtf16 <= it.endUtf16 } == true
                code(
                    IdeTextKind.Source,
                    projectGlyphs(line.substring(start, end)),
                    x,
                    y,
                    if (linked) IdeColors.HYPERLINK else styleColor(resolved),
                    geometry.editor,
                    resolved,
                    absoluteRange,
                )
                if (linked) {
                    underline(IdeFillKind.HyperlinkUnderline, x, line.substring(start, end), y, IdeColors.HYPERLINK)
                } else if ((resolved as? IdeTextStyle.Semantic)?.isMutable == true) {
                    underline(IdeFillKind.MutableUnderline, x, line.substring(start, end), y, IdeColors.MUTABLE_UNDERLINE)
                }
            }
        }

        private fun underline(
            kind: IdeFillKind,
            x: Int,
            value: String,
            textY: Int,
            color: Int,
        ) {
            val left = maxOf(x, geometry.editor.left)
            val right = minOf(x + visualColumns(value) * font.cellWidth, geometry.editor.right)
            if (right <= left) return
            val top = (textY - font.glyphDrawOffsetY + font.cellHeight - 1).coerceIn(geometry.editor.top, geometry.editor.bottom - 1)
            fills +=
                IdeFillDraw(
                    kind,
                    IdeRect(left, top, right, top + 1),
                    color,
                    Z_TEXT,
                )
        }

        private fun semanticHover(
            editor: IdeEditorView.Text,
            codeLeft: Int,
            hover: IdeSemanticInteraction.Hover,
        ) {
            val anchor = sourceAnchor(editor, codeLeft, hover.anchor) ?: return
            val availableHeight = maxOf(anchor.top - geometry.editor.top, geometry.editor.bottom - anchor.bottom)
            val maximumRows = minOf(20, (availableHeight - POPUP_VERTICAL_PADDING) / font.cellHeight)
            if (maximumRows <= 0) return
            val maximumColumns = ((geometry.editor.width - POPUP_HORIZONTAL_PADDING) / font.cellWidth).coerceAtLeast(1)
            val lines =
                buildList {
                    hover.info.signature?.let(::add)
                    add("Type: ${hover.info.renderedType}")
                    hover.info.origin?.let { origin ->
                        add(
                            when (origin) {
                                DeclarationOrigin.Project -> "Origin: Project"
                                is DeclarationOrigin.Platform -> "Origin: ${origin.identity.name}"
                            },
                        )
                    }
                }.map(::popupText).toMutableList()
            hover.info.documentation?.let { documentation ->
                lines += ""
                lines += wrappedDocumentation(documentation, maximumColumns, maximumRows + 1)
            }
            if (lines.size > maximumRows) {
                lines.subList(maximumRows, lines.size).clear()
                lines[lines.lastIndex] = popupText(lines.last() + "…")
            }
            if (lines.isEmpty()) return
            val requestedWidth =
                maxOf(
                    SEMANTIC_POPUP_MINIMUM_WIDTH,
                    lines.maxOf(::visualColumns) * font.cellWidth + POPUP_HORIZONTAL_PADDING,
                )
            val popup = geometry.anchoredPopup(anchor, requestedWidth, lines.size * font.cellHeight + POPUP_VERTICAL_PADDING)
            semanticPopupPanel(popup.bounds)
            lines.forEachIndexed { index, value ->
                code(
                    IdeTextKind.Hover,
                    value,
                    popup.bounds.left + 4,
                    popup.bounds.top + 3 + font.glyphDrawOffsetY + index * font.cellHeight,
                    IdeColors.TEXT,
                    popup.bounds,
                    z = Z_POPUP_TEXT,
                )
            }
        }

        private fun semanticChooser(
            editor: IdeEditorView.Text,
            codeLeft: Int,
            chooser: IdeSemanticInteraction.Chooser,
        ) {
            val anchor = sourceAnchor(editor, codeLeft, chooser.anchor) ?: return
            val labels = chooser.targets.map(::declarationLabel).map(::popupText)
            val requestedWidth =
                maxOf(
                    SEMANTIC_POPUP_MINIMUM_WIDTH,
                    labels.maxOf(::visualColumns) * font.cellWidth + POPUP_HORIZONTAL_PADDING,
                )
            val requestedRows = minOf(DECLARATION_VISIBLE_ROWS, labels.size)
            val popup = geometry.anchoredPopup(anchor, requestedWidth, requestedRows * UI_LINE_HEIGHT + POPUP_VERTICAL_PADDING)
            val visibleRows = ((popup.bounds.height - POPUP_VERTICAL_PADDING) / UI_LINE_HEIGHT).coerceAtLeast(1)
            val first = (chooser.selectedIndex - visibleRows + 1).coerceIn(0, (labels.size - visibleRows).coerceAtLeast(0))
            val last = minOf(labels.size, first + visibleRows)
            semanticPopupPanel(popup.bounds)
            for (index in first until last) {
                val row = index - first
                val rowBounds =
                    IdeRect(
                        popup.bounds.left,
                        popup.bounds.top + 2 + row * UI_LINE_HEIGHT,
                        popup.bounds.right,
                        minOf(popup.bounds.bottom, popup.bounds.top + 2 + (row + 1) * UI_LINE_HEIGHT),
                    )
                if (rowBounds.height <= 0) continue
                val selected = index == chooser.selectedIndex
                if (selected) {
                    fills += IdeFillDraw(IdeFillKind.Selection, rowBounds, IdeColors.SELECTION, Z_POPUP)
                }
                ui(
                    IdeTextKind.DeclarationChoice,
                    labels[index],
                    popup.bounds.left + 4,
                    popup.bounds.top + 3 + row * UI_LINE_HEIGHT,
                    if (selected) IdeColors.ACCENT else IdeColors.TEXT,
                    popup.bounds,
                    Z_POPUP_TEXT,
                )
                hitTargets +=
                    IdeHitTarget(
                        IdeHitAction.DeclarationChoice,
                        rowBounds,
                        enabled = true,
                        tooltip = null,
                        focusGroup = IdeFocusGroup.Page,
                        zIndex = Z_POPUP_TEXT,
                        selected = selected,
                        choiceIndex = index,
                    )
            }
        }

        private fun semanticPopupPanel(bounds: IdeRect) {
            panel(IdePanelKind.Dialog, bounds, IdeColors.PANEL_ALT, Z_POPUP)
            scissors += IdeScissorDraw(IdeScissorKind.SemanticPopup, bounds, Z_POPUP)
        }

        private fun sourceAnchor(
            editor: IdeEditorView.Text,
            codeLeft: Int,
            anchor: IdeSemanticAnchor,
        ): IdeRect? {
            val visibleIndex = editor.visibleLineStartsUtf16.indexOfLast { it <= anchor.tokenRange.startUtf16 }
            if (visibleIndex !in editor.visibleLines.indices) return null
            val line = editor.visibleLines[visibleIndex]
            val lineStart = editor.visibleLineStartsUtf16[visibleIndex]
            val localStart = anchor.tokenRange.startUtf16 - lineStart
            if (localStart !in 0..line.length) return null
            val localEnd = (anchor.tokenRange.endUtf16 - lineStart).coerceIn(localStart, line.length)
            val left = codeLeft + (visualColumns(line.substring(0, localStart)) - editor.firstVisibleColumn) * font.cellWidth
            val right =
                maxOf(
                    left + font.cellWidth,
                    codeLeft + (visualColumns(line.substring(0, localEnd)) - editor.firstVisibleColumn) * font.cellWidth,
                )
            val top = geometry.editor.top + visibleIndex * font.cellHeight
            return IdeRect(left, top, right, top + font.cellHeight)
        }

        private fun declarationLabel(target: IdeDeclarationTarget): String =
            when (target) {
                is IdeDeclarationTarget.Project -> "Project · ${target.path.value}"
                is IdeDeclarationTarget.AttachedSource -> "${target.module.name} · ${target.path.value}"
            }

        private fun popupText(value: String): String {
            val maximumColumns = (geometry.editor.width / font.cellWidth - 2).coerceAtLeast(1)
            if (visualColumns(value) <= maximumColumns) return value
            val result = StringBuilder(minOf(value.length, maximumColumns))
            var offset = 0
            var columns = 0
            while (offset < value.length && columns < maximumColumns - 1) {
                val codePoint = value.codePointAt(offset)
                result.appendCodePoint(codePoint)
                offset += Character.charCount(codePoint)
                columns++
            }
            return result.append('…').toString()
        }

        private fun completion(
            editor: IdeEditorView.Text,
            codeLeft: Int,
        ) {
            val completion = (editor.analysis as? IdeAnalysisState.Active)?.completion ?: return
            val caret = caretBounds(editor, codeLeft) ?: return
            val visibleItems = completion.visibleEntries
            val rows = visibleItems.map(::completionRow)
            val results =
                visibleItems.map {
                    it.proposal.callablePresentation
                        ?.returnType
                        .orEmpty()
                }
            val resultColumns = results.maxOf(::visualColumns)
            val contentColumns = rows.maxOf(::visualColumns) + 2 + if (resultColumns > 0) resultColumns + 2 else 0
            val contentWidth = contentColumns * font.cellWidth + COMPLETION_HORIZONTAL_PADDING
            val popup =
                geometry.completionPopup(
                    caret,
                    maxOf(COMPLETION_MINIMUM_WIDTH, contentWidth),
                    visibleItems.size * font.cellHeight + 4,
                )
            panel(IdePanelKind.Dialog, popup.bounds, IdeColors.PANEL_ALT, Z_POPUP)
            scissors += IdeScissorDraw(IdeScissorKind.Completion, popup.bounds, Z_POPUP)
            val innerLeft = minOf(popup.bounds.left + 4, popup.bounds.right)
            val innerRight = maxOf(innerLeft, popup.bounds.right - 4)
            val left = minOf(innerLeft + 2 * font.cellWidth, innerRight)
            val available = innerRight - left
            val resultWidth = minOf(resultColumns * font.cellWidth, available / 2 / font.cellWidth * font.cellWidth)
            val gap = if (resultWidth > 0) minOf(2 * font.cellWidth, available - resultWidth) else 0
            val leftRight = innerRight - resultWidth - gap
            val leftClip = IdeRect(left, popup.bounds.top, leftRight, popup.bounds.bottom)
            val resultClip = IdeRect(innerRight - resultWidth, popup.bounds.top, innerRight, popup.bounds.bottom)
            rows.forEachIndexed { index, row ->
                val selected = completion.firstVisibleIndex + index == completion.selectedIndex
                val y = popup.bounds.top + 3 + font.glyphDrawOffsetY + index * font.cellHeight
                completionBadge(visibleItems[index].proposal.kind)?.let { (letter, color) ->
                    code(IdeTextKind.CompletionBadge, letter, innerLeft, y, color, popup.bounds, z = Z_POPUP_TEXT)
                }
                val visible = completionText(row, leftClip.width)
                var offset = 0
                val nameLimit = if (visible != row && visible.endsWith('…')) visible.length - 1 else visible.length
                for (range in visibleItems[index].proposal.matchedNameRanges) {
                    if (range.startUtf16 >= nameLimit) break
                    val end = minOf(range.endUtf16, nameLimit)
                    completionFragment(visible, offset, range.startUtf16, left, y, selected, false, leftClip)
                    completionFragment(visible, range.startUtf16, end, left, y, selected, true, leftClip)
                    offset = end
                }
                completionFragment(visible, offset, visible.length, left, y, selected, false, leftClip)
                if (results[index].isNotEmpty() && resultWidth > 0) {
                    val result = completionText(results[index], resultWidth)
                    code(
                        IdeTextKind.CompletionReturnType,
                        result,
                        innerRight - visualColumns(result) * font.cellWidth,
                        y,
                        if (selected) IdeColors.ACCENT else IdeColors.MUTED,
                        resultClip,
                        z = Z_POPUP_TEXT,
                    )
                }
            }
        }

        private fun completionFragment(
            value: String,
            start: Int,
            end: Int,
            x: Int,
            y: Int,
            selected: Boolean,
            matched: Boolean,
            clip: IdeRect,
        ) {
            if (start >= end) return
            code(
                if (matched) IdeTextKind.CompletionMatch else IdeTextKind.Completion,
                value.substring(start, end),
                x + visualColumns(value.substring(0, start)) * font.cellWidth,
                y,
                if (matched) {
                    IdeColors.COMPLETION_MATCH
                } else if (selected) {
                    IdeColors.ACCENT
                } else {
                    IdeColors.TEXT
                },
                clip,
                z = Z_POPUP_TEXT,
            )
        }

        private fun wrappedDocumentation(
            value: String,
            columns: Int,
            maximumRows: Int,
        ): List<String> {
            val lines = mutableListOf<String>()
            for (line in value.replace("\t", "    ").lines()) {
                var remaining = line
                while (visualColumns(remaining) > columns && lines.size < maximumRows) {
                    val cutoff = remaining.offsetByCodePoints(0, columns)
                    val space = remaining.lastIndexOf(' ', cutoff - 1)
                    val end = if (space > 0) space else cutoff
                    lines += remaining.substring(0, end)
                    remaining = remaining.substring(end).trimStart()
                }
                if (lines.size >= maximumRows) break
                lines += remaining
            }
            return lines
        }

        private fun completionText(
            value: String,
            width: Int,
        ): String {
            val columns = width / font.cellWidth
            if (columns <= 0) return ""
            if (visualColumns(value) <= columns) return value
            val result = StringBuilder()
            var offset = 0
            var written = 0
            while (offset < value.length && written < columns - 1) {
                val point = value.codePointAt(offset)
                result.appendCodePoint(point)
                offset += Character.charCount(point)
                written++
            }
            return result.append('…').toString()
        }

        private fun parameterInfo(
            editor: IdeEditorView.Text,
            codeLeft: Int,
            info: IdeParameterInfoState,
        ) {
            val caret = caretBounds(editor, codeLeft) ?: return
            val visibleItems = info.items.take(PARAMETER_INFO_VISIBLE_ROWS)
            val contentWidth = visibleItems.maxOf { visualColumns(it.signature) } * font.cellWidth + POPUP_HORIZONTAL_PADDING
            val popup =
                geometry.anchoredPopup(
                    caret,
                    maxOf(SEMANTIC_POPUP_MINIMUM_WIDTH, contentWidth),
                    visibleItems.size * UI_LINE_HEIGHT + POPUP_VERTICAL_PADDING,
                )
            semanticPopupPanel(popup.bounds)
            visibleItems.forEachIndexed { index, item ->
                val y = popup.bounds.top + 3 + index * UI_LINE_HEIGHT
                val x = popup.bounds.left + 4
                val activeParameter = item.activeParameter
                if (activeParameter == null) {
                    ui(IdeTextKind.ParameterInfo, item.signature, x, y, IdeColors.TEXT, popup.bounds, Z_POPUP_TEXT)
                } else {
                    val prefix = item.signature.substring(0, activeParameter.startUtf16)
                    val active = item.signature.substring(activeParameter.startUtf16, activeParameter.endUtf16)
                    val suffix = item.signature.substring(activeParameter.endUtf16)
                    ui(IdeTextKind.ParameterInfo, prefix, x, y, IdeColors.TEXT, popup.bounds, Z_POPUP_TEXT)
                    val activeX = x + visualColumns(prefix) * font.cellWidth
                    ui(IdeTextKind.ParameterInfo, active, activeX, y, IdeColors.ACCENT, popup.bounds, Z_POPUP_TEXT)
                    ui(
                        IdeTextKind.ParameterInfo,
                        suffix,
                        activeX + visualColumns(active) * font.cellWidth,
                        y,
                        IdeColors.TEXT,
                        popup.bounds,
                        Z_POPUP_TEXT,
                    )
                }
            }
        }

        private fun caretBounds(
            editor: IdeEditorView.Text,
            codeLeft: Int,
        ): IdeRect? {
            val visibleIndex = editor.visibleLineStartsUtf16.indexOfLast { it <= editor.caretUtf16 }
            if (visibleIndex !in editor.visibleLines.indices) return null
            val line = editor.visibleLines[visibleIndex]
            val local = (editor.caretUtf16 - editor.visibleLineStartsUtf16[visibleIndex]).coerceIn(0, line.length)
            return IdeRect(
                codeLeft + (visualColumns(line.substring(0, local)) - editor.firstVisibleColumn) * font.cellWidth,
                geometry.editor.top + visibleIndex * font.cellHeight,
                codeLeft + (visualColumns(line.substring(0, local)) - editor.firstVisibleColumn) * font.cellWidth + font.cellWidth,
                geometry.editor.top + (visibleIndex + 1) * font.cellHeight,
            )
        }

        private fun completionRow(entry: ru.lazyhat.compukters.ide.client.analysis.IdeCompletionEntry): String =
            buildString {
                append(entry.proposal.label)
                val presentation = entry.proposal.callablePresentation
                presentation?.receiverType?.let { append(" (for $it)") }
                presentation?.packageName?.let { append(" · in $it") }
                if (completionBadge(entry.proposal.kind) == null) {
                    append(" · ")
                    append(
                        entry.proposal.kind.name
                            .lowercase(),
                    )
                }
                entry.actionText?.let {
                    append(" · ")
                    append(it)
                }
            }

        private fun completionBadge(kind: CompletionKind): Pair<String, Int>? =
            when (kind) {
                CompletionKind.Function, CompletionKind.ExtensionFunction -> {
                    "F" to IdeColors.COMPLETION_CALLABLE
                }

                CompletionKind.MemberFunction -> {
                    "M" to IdeColors.COMPLETION_CALLABLE
                }

                CompletionKind.Property, CompletionKind.LocalVariable, CompletionKind.Parameter, CompletionKind.EnumEntry -> {
                    "V" to IdeColors.COMPLETION_VARIABLE
                }

                CompletionKind.Class, CompletionKind.Object, CompletionKind.TypeParameter, CompletionKind.TypeAlias -> {
                    "C" to IdeColors.COMPLETION_CLASS
                }

                CompletionKind.PeripheralProvider -> {
                    "P" to IdeColors.COMPLETION_PROVIDER
                }

                CompletionKind.Interface -> {
                    "I" to IdeColors.COMPLETION_INTERFACE
                }

                CompletionKind.Package, CompletionKind.Keyword -> {
                    null
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
                ui(IdeTextKind.ToolWindow, label, left + 6, header.top + 4, clip = rect)
            }
            tab("Problems", header.left, header.left + 74, IdeBottomTab.Problems)
            tab("Git Log", header.left + 74, header.left + 140, IdeBottomTab.GitLog)
            val close = IdeRect(header.right - 22, header.top, header.right, header.bottom)
            target(IdeHitAction.GitOperation, close, true, "Hide tool window", gitCommand = IdeCommand.BottomTab(null))
            ui(IdeTextKind.ToolWindow, "×", close.left + 6, close.top + 4)
            val bounds = IdeRect(panelBounds.left, header.bottom, panelBounds.right, panelBounds.bottom)
            scissors += IdeScissorDraw(IdeScissorKind.Diagnostics, bounds, Z_CLIP)
            if (workspace.bottom.tab == IdeBottomTab.GitLog) {
                val refresh = IdeRect(header.left + 146, header.top, header.left + 210, header.bottom)
                target(IdeHitAction.GitOperation, refresh, true, "Refresh Git Log", gitCommand = IdeCommand.BottomTab(IdeBottomTab.GitLog))
                ui(IdeTextKind.ToolWindow, "Refresh", refresh.left + 4, refresh.top + 4, clip = refresh)
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
                ui(IdeTextKind.Diagnostic, "×", close.left + 6, close.top + 4, IdeColors.MUTED, bounds)
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
                    diagnosticColor(diagnostic.severity),
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

        private fun status(
            workspace: ru.lazyhat.compukters.ide.client.state.IdeWorkspaceView,
            targetState: IdeTargetState,
            toolingState: IdeToolingState,
            busy: Set<IdeBusyOperation>,
        ) {
            val parts = linkedSetOf<String>()
            val errors = workspace.diagnostics.errors
            val warnings = workspace.diagnostics.warnings
            parts += "$errors ${if (errors == 1) "error" else "errors"} · $warnings ${if (warnings == 1) "warning" else "warnings"}"
            (workspace.editor as? IdeEditorView.Text)?.let { editor ->
                parts +=
                    if (editor.conflict) {
                        "Conflict"
                    } else if (editor.dirty) {
                        "Modified"
                    } else {
                        "Saved"
                    }
                parts += "UTF-16 ${editor.caretUtf16}"
                when (val analysis = editor.analysis) {
                    is IdeAnalysisState.Loading -> parts += "Analysis…"
                    is IdeAnalysisState.Unavailable -> parts += analysis.status
                    else -> Unit
                }
            }
            when (val build = workspace.build) {
                is IdeBuildState.Succeeded -> parts += "Artifact ${build.bytes} B · ${if (build.cacheHit) "cache hit" else "compiled"}"
                is IdeBuildState.Failed -> parts += build.detail
                is IdeBuildState.Compiling -> parts += "Building…"
                is IdeBuildState.Saving -> parts += "Saving…"
                else -> Unit
            }
            if (IdeBusyOperation.Format in busy) parts += "Formatting…"
            targetStatus(targetState)?.let(parts::add)
            when (toolingState) {
                IdeToolingState.Preparing -> parts += "Kotlin tooling is starting…"
                is IdeToolingState.Unavailable -> parts += toolingState.detail
                IdeToolingState.Ready -> Unit
            }
            workspace.status?.let { parts += it.message }
            when (val transfer = workspace.computerTransfer) {
                IdeComputerTransferState.Idle -> {
                    Unit
                }

                is IdeComputerTransferState.Downloading -> {
                    parts +=
                        "Copying ${transfer.progress.filesComplete}/${transfer.progress.filesTotal} · ${transfer.progress.bytesComplete}/${transfer.progress.bytesTotal} B"
                }

                is IdeComputerTransferState.ConfirmationRequired -> {
                    parts += "Copy ready · confirmation required"
                }

                is IdeComputerTransferState.Failed -> {
                    parts += transfer.detail
                }
            }
            ui(
                IdeTextKind.Status,
                parts.joinToString(" · "),
                geometry.status.left + 6,
                geometry.status.top + 5,
                IdeColors.MUTED,
                geometry.status,
            )
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
                        "File conflict" to dialog.path.value
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
            ui(IdeTextKind.Dialog, "Confirm", confirm.left + 9, confirm.top + 5, z = Z_DIALOG_TEXT)
            ui(IdeTextKind.Dialog, "Cancel", dismiss.left + 12, dismiss.top + 5, z = Z_DIALOG_TEXT)
        }

        fun prompt(prompt: IdePromptState) {
            for (index in hitTargets.indices) hitTargets[index] = hitTargets[index].copy(enabled = false)
            fills += IdeFillDraw(IdeFillKind.DialogScrim, geometry.panel, IdeColors.DIM, Z_DIALOG_SCRIM)
            val width = minOf(420, geometry.panel.width - 24)
            val height = minOf(132, geometry.panel.height - 24)
            val left = geometry.panel.left + (geometry.panel.width - width) / 2
            val top = geometry.panel.top + (geometry.panel.height - height) / 2
            val bounds = IdeRect(left, top, left + width, top + height)
            panel(IdePanelKind.Dialog, bounds, IdeColors.PANEL_ALT, Z_DIALOG)
            ui(IdeTextKind.Dialog, prompt.title, bounds.left + 10, bounds.top + 10, clip = bounds, z = Z_DIALOG_TEXT)
            ui(IdeTextKind.Dialog, prompt.fieldLabel + ":", bounds.left + 10, bounds.top + 30, clip = bounds, z = Z_DIALOG_TEXT)
            val field = IdeRect(bounds.left + 10, bounds.top + 44, bounds.right - 10, bounds.top + 66)
            fills += IdeFillDraw(IdeFillKind.Border, field, IdeColors.ACCENT, Z_DIALOG + 1)
            val inside = IdeRect(field.left + 1, field.top + 1, field.right - 1, field.bottom - 1)
            fills += IdeFillDraw(IdeFillKind.Background, inside, IdeColors.EDITOR, Z_DIALOG + 2)
            val columns = ((inside.width - 8) / font.cellWidth).coerceAtLeast(1)
            val glyphs = projectGlyphs(prompt.displayValue)
            val length = glyphs.codePointCount(0, glyphs.length)
            val start = glyphs.offsetByCodePoints(0, (length - columns + 1).coerceAtLeast(0))
            val visible = glyphs.substring(start)
            val x = inside.left + 4
            val rowTop = inside.top + (inside.height - font.cellHeight) / 2
            code(IdeTextKind.Dialog, visible, x, rowTop + font.glyphDrawOffsetY, IdeColors.TEXT, inside, z = Z_DIALOG_TEXT)
            val caretX = x + visible.codePointCount(0, visible.length) * font.cellWidth
            fills +=
                IdeFillDraw(
                    IdeFillKind.Caret,
                    IdeRect(caretX, rowTop, caretX + 1, rowTop + font.cellHeight),
                    IdeColors.CARET,
                    Z_DIALOG_TEXT,
                )
            prompt.error?.let { ui(IdeTextKind.Dialog, it, bounds.left + 10, bounds.top + 76, IdeColors.ERROR, bounds, Z_DIALOG_TEXT) }
            val dismiss = IdeRect(bounds.right - 78, bounds.bottom - 26, bounds.right - 10, bounds.bottom - 8)
            val confirm = IdeRect(dismiss.left - 76, dismiss.top, dismiss.left - 8, dismiss.bottom)
            target(IdeHitAction.Confirm, confirm, true, focusGroup = IdeFocusGroup.Dialog, z = Z_DIALOG_TARGET)
            target(IdeHitAction.Dismiss, dismiss, true, focusGroup = IdeFocusGroup.Dialog, z = Z_DIALOG_TARGET)
            ui(IdeTextKind.Dialog, "Confirm", confirm.left + 9, confirm.top + 5, z = Z_DIALOG_TEXT)
            ui(IdeTextKind.Dialog, "Cancel", dismiss.left + 12, dismiss.top + 5, z = Z_DIALOG_TEXT)
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

        private fun code(
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

        private fun panel(
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

        private fun target(
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
        ) {
            panels +=
                IdePanelDraw(
                    IdePanelKind.Control,
                    bounds,
                    when {
                        selected -> IdeColors.ACCENT
                        enabled -> IdeColors.BORDER
                        else -> IdeColors.PANEL_ALT
                    },
                    z - CONTROL_BACKGROUND_OFFSET,
                )
            hitTargets +=
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
                )
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

        private fun projectGlyphs(source: String): String {
            val result = StringBuilder(source.length)
            var offset = 0
            var columns = 0
            while (offset < source.length) {
                val codePoint = source.codePointAt(offset)
                if (codePoint == '\t'.code) {
                    val spaces = TAB_WIDTH - columns % TAB_WIDTH
                    repeat(spaces) { result.append(' ') }
                    columns += spaces
                } else {
                    result.appendCodePoint(codePoint)
                    columns++
                }
                offset += Character.charCount(codePoint)
            }
            return result.toString()
        }

        private fun visualColumns(value: String): Int {
            var columns = 0
            var offset = 0
            while (offset < value.length) {
                val codePoint = value.codePointAt(offset)
                columns =
                    if (codePoint == '\t'.code) {
                        columns + TAB_WIDTH - columns % TAB_WIDTH
                    } else {
                        columns + 1
                    }
                offset += Character.charCount(codePoint)
            }
            return columns
        }
    }

    private fun targetLabel(state: IdeTargetState): String =
        when (state) {
            IdeTargetState.LocalOnly -> "Local only"
            is IdeTargetState.Attaching -> "Attaching…"
            is IdeTargetState.Detached -> "Target detached"
            is IdeTargetState.Failed -> state.target?.displayName ?: "Target unavailable"
            else -> checkNotNull(state.attachedTarget).displayName
        }

    private fun targetStatus(state: IdeTargetState): String? =
        when (state) {
            IdeTargetState.LocalOnly,
            is IdeTargetState.Attached,
            -> null

            is IdeTargetState.Attaching -> "Attaching target…"

            is IdeTargetState.Uploading -> "Verifying…"

            is IdeTargetState.Verified -> "Verified"

            is IdeTargetState.Observing -> "Checking destination…"

            is IdeTargetState.ConfirmationRequired -> "Overwrite confirmation required"

            is IdeTargetState.Deploying -> "Deploying…"

            is IdeTargetState.Deployed -> "Deployed ${state.path.value}"

            is IdeTargetState.Submitting -> "Submitting command…"

            is IdeTargetState.CommandSubmitted -> state.message

            is IdeTargetState.Detached -> state.failure.detail

            is IdeTargetState.Failed -> state.failure.detail
        }

    private val IdeTargetState.attachedTarget: IdeAttachedTarget?
        get() =
            when (this) {
                IdeTargetState.LocalOnly,
                is IdeTargetState.Attaching,
                is IdeTargetState.Detached,
                -> null

                is IdeTargetState.Attached -> target

                is IdeTargetState.Uploading -> target

                is IdeTargetState.Verified -> target

                is IdeTargetState.Observing -> target

                is IdeTargetState.ConfirmationRequired -> target

                is IdeTargetState.Deploying -> target

                is IdeTargetState.Deployed -> target

                is IdeTargetState.Submitting -> target

                is IdeTargetState.CommandSubmitted -> target

                is IdeTargetState.Failed -> target
            }

    private val IdeTargetState.isReadyForAction: Boolean
        get() =
            this is IdeTargetState.Attached ||
                this is IdeTargetState.Verified ||
                this is IdeTargetState.Deployed ||
                this is IdeTargetState.CommandSubmitted ||
                (this is IdeTargetState.Failed && target != null)

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

    private fun styleColor(style: IdeTextStyle): Int =
        when (style) {
            IdeTextStyle.Ui -> IdeColors.TEXT
            IdeTextStyle.Plain -> IdeColors.EDITOR_TEXT
            is IdeTextStyle.Lexical -> lexicalColor(style.kind)
            is IdeTextStyle.Semantic -> semanticColor(style.category)
        }

    private fun lexicalColor(kind: KotlinLexicalKind): Int =
        when (kind) {
            KotlinLexicalKind.Keyword -> IdeColors.KEYWORD

            KotlinLexicalKind.String,
            KotlinLexicalKind.Character,
            KotlinLexicalKind.MultilineString,
            -> IdeColors.STRING

            KotlinLexicalKind.Escape -> IdeColors.STRING_ESCAPE

            KotlinLexicalKind.FunctionCall -> IdeColors.FUNCTION

            KotlinLexicalKind.Number -> IdeColors.NUMBER

            KotlinLexicalKind.LineComment, KotlinLexicalKind.BlockComment -> IdeColors.COMMENT

            KotlinLexicalKind.TypeLike -> IdeColors.TYPE

            KotlinLexicalKind.Annotation -> IdeColors.ANNOTATION

            else -> IdeColors.EDITOR_TEXT
        }

    private fun semanticColor(category: SemanticCategory): Int =
        when (category) {
            SemanticCategory.Class,
            SemanticCategory.Interface,
            SemanticCategory.Object,
            -> IdeColors.TYPE

            SemanticCategory.PeripheralProvider -> IdeColors.PERIPHERAL_PROVIDER

            SemanticCategory.TypeParameter -> IdeColors.TYPE_PARAMETER

            SemanticCategory.EnumEntry -> IdeColors.PROPERTY

            SemanticCategory.Function, SemanticCategory.ExtensionFunction -> IdeColors.FUNCTION

            SemanticCategory.Property -> IdeColors.PROPERTY

            SemanticCategory.LocalVariable, SemanticCategory.Parameter -> IdeColors.LOCAL_VARIABLE

            SemanticCategory.InferredExpression,
            SemanticCategory.SmartCastExpression,
            -> IdeColors.EDITOR_TEXT
        }

    private fun diagnosticColor(severity: EditorDiagnosticSeverity): Int =
        when (severity) {
            EditorDiagnosticSeverity.Info -> IdeColors.INFO
            EditorDiagnosticSeverity.Warning -> IdeColors.WARNING
            EditorDiagnosticSeverity.Error -> IdeColors.ERROR
        }

    private fun problemColor(name: String): Int =
        when (name) {
            "Error" -> IdeColors.ERROR
            "Warning" -> IdeColors.WARNING
            else -> IdeColors.INFO
        }

    private const val TAB_WIDTH = 4
    private const val UI_LINE_HEIGHT = 12
    private const val PROJECT_CONTROL_MINIMUM_WIDTH = 80
    private const val PROJECT_SWITCHER_WIDTH = 540
    private const val PROJECT_ROW_HEIGHT = 40
    private const val PROJECT_ACTION_HEIGHT = 18
    private const val TOOLBAR_ICON_CONTROL_WIDTH = 22
    private const val TOOLBAR_GROUP_GAP = 6
    private const val TOOLTIP_MARGIN = 4
    private const val TOOLTIP_HORIZONTAL_PADDING = 5
    private const val TOOLTIP_POINTER_OFFSET = 12
    private const val TOOLTIP_HEIGHT = 18
    private const val COMPLETION_MINIMUM_WIDTH = 220
    private const val COMPLETION_HORIZONTAL_PADDING = 8
    private const val SEMANTIC_POPUP_MINIMUM_WIDTH = 180
    private const val POPUP_HORIZONTAL_PADDING = 8
    private const val POPUP_VERTICAL_PADDING = 4
    private const val DECLARATION_VISIBLE_ROWS = 8
    private const val PARAMETER_INFO_VISIBLE_ROWS = 6
    private const val NO_TARGET = "No target attached"
    private const val TERMINAL_UNAVAILABLE = "Target terminal is unavailable"
    private const val CONTROL_BACKGROUND_OFFSET = 20
    private const val Z_BACKGROUND = 0
    private const val Z_PANEL = 10
    private const val Z_CONTENT = 20
    private const val Z_SELECTION = 24
    private const val Z_CLIP = 25
    private const val Z_TEXT = 30
    private const val Z_CARET = 35
    private const val Z_TARGET = 40
    private const val Z_POPUP = 50
    private const val Z_POPUP_TARGET = Z_POPUP + CONTROL_BACKGROUND_OFFSET + 1
    private const val Z_DRAG = 60
    private const val Z_PROJECT_SWITCHER = 70
    private const val Z_PROJECT_SWITCHER_SELECTION = 72
    private const val Z_PROJECT_SWITCHER_TEXT = 75
    private const val Z_PROJECT_SWITCHER_TARGET = 80
    private const val Z_TOOLTIP = 82
    private const val Z_TOOLTIP_TEXT = 85
    private const val Z_POPUP_TEXT = 55
    private const val Z_DIALOG_SCRIM = 90
    private const val Z_DIALOG = 100
    private const val Z_DIALOG_TEXT = 110
    private const val Z_DIALOG_TARGET = 120
}

private fun SemanticCategory.contributesTextStyle(): Boolean =
    this != SemanticCategory.InferredExpression && this != SemanticCategory.SmartCastExpression

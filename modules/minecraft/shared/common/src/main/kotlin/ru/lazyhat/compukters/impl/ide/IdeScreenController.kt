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

import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState
import ru.lazyhat.compukters.ide.client.analysis.IdeSemanticInteraction
import ru.lazyhat.compukters.ide.client.analysis.IdeVisibleLatencyTrace
import ru.lazyhat.compukters.ide.client.controller.IdeClientController
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdePageState
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeTargetState
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReference
import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalClient
import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalState
import ru.lazyhat.compukters.impl.ui.CompuktersUiViewport

/** Minecraft-specific effects; screen state and routing are owned by the shared controller. */
interface IdeScreenHost {
    var clipboard: String

    fun viewport(): CompuktersUiViewport

    fun clearFocus()

    fun restoreParent()

    fun abandonParent()

    fun closeSession()
}

data class IdePointerInput(
    val mouseX: Double,
    val mouseY: Double,
    val mouseButton: Int,
    val keys: Int,
) {
    fun x(): Double = mouseX

    fun y(): Double = mouseY

    fun button(): Int = mouseButton

    fun modifiers(): Int = keys

    fun map(viewport: CompuktersUiViewport): IdePointerInput =
        copy(mouseX = viewport.toVirtualX(mouseX), mouseY = viewport.toVirtualY(mouseY))
}

class IdeScreenController(
    private val controller: IdeClientController,
    private val preferences: IdeClientPreferences,
    targetTerminal: IdeTargetTerminalClient,
    private val visibleLatency: IdeVisibleLatencyTrace,
    private val host: IdeScreenHost,
) {
    private val prompt = IdePromptController()
    private val input =
        IdeInputAdapter(
            controller::dispatch,
            IdeClipboard { host.clipboard },
            IdeClientLimits(),
            IdeUiActionSink(::activateUiAction),
            IdeClipboardWriter { host.clipboard = it },
            IdeSelectionSource(controller::selectedText),
            IdeProjectActionSink(::activateProjectAction),
        )
    private val splitters = IdeSplitterInteraction(preferences.layout(), preferences::saveLayout)
    private val terminalOverlay = IdeTerminalOverlayController(targetTerminal)
    var focusArea = IdeFocusState.Initial.area
        private set
    private var selectedTreePath: ProjectPath? = null
    private var returningToParent = false
    private var sessionClosed = false
    private var controlDown = false
    private var pointerX: Double? = null
    private var pointerY: Double? = null
    private var projectSwitcherOpen = false

    fun mouseClicked(
        event: IdePointerInput,
        doubleClick: Boolean,
    ): Boolean {
        val viewport = viewport()
        val uiEvent = event.map(viewport)
        if (uiEvent.button() == 0 && (!viewport.supported || (prompt.state == null && controller.viewState().dialog == null))) {
            IdeUiScaleControl.hit(IdeRect(0, 0, viewport.width, viewport.height), uiEvent.x(), uiEvent.y())?.let { mode ->
                preferences.saveUiScale(mode)
                return true
            }
        }
        if (!viewport.supported) return true
        val geometry = geometry()
        val state = controller.viewState()
        if (prompt.state != null || state.dialog != null) {
            input.pointerClicked(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), pointerContext(geometry), doubleClick)
            host.clearFocus()
            return true
        }
        if (projectSwitcherOpen) {
            val switcherContext = pointerContext(geometry)
            val switcherTarget =
                switcherContext.hitTargets
                    .asReversed()
                    .firstOrNull { it.enabled && it.bounds.contains(uiEvent.x(), uiEvent.y()) }
            if (switcherTarget?.isProjectSwitcherControl != true) {
                projectSwitcherOpen = false
                input.pointerActivity()
                return true
            }
            input.pointerClicked(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), switcherContext, doubleClick)
            if (switcherTarget?.action == IdeHitAction.ProjectChoice || controller.viewState().dialog != null) {
                projectSwitcherOpen = false
            }
            focusArea = IdeFocusArea.Panel
            host.clearFocus()
            terminalOverlay.focusLost()
            return true
        }
        val overlay = terminalOverlayGeometry(geometry)
        if (terminalOverlay.visible && overlay.panel.contains(uiEvent.x(), uiEvent.y())) {
            focusArea = IdeFocusArea.Terminal
            terminalOverlay.focus()
            if (overlay.title.contains(uiEvent.x(), uiEvent.y())) terminalOverlay.retry()
            host.clearFocus()
            input.pointerActivity()
            return true
        }
        if (splitters.press(uiEvent.x().toInt(), uiEvent.y().toInt(), geometry)) {
            input.pointerActivity()
            return true
        }
        val pointerContext = pointerContext(geometry)
        if (input.explorerPressed(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), pointerContext)) {
            focusArea = IdeFocusArea.Tree
            selectedTreePath = null
            host.clearFocus()
            terminalOverlay.focusLost()
            return true
        }
        selectTreeRow(uiEvent.x(), uiEvent.y(), geometry)
        val hitAction =
            pointerContext.hitTargets
                .asReversed()
                .firstOrNull { it.enabled && it.bounds.contains(uiEvent.x(), uiEvent.y()) }
                ?.action
        if (input.pointerClicked(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), pointerContext, doubleClick)) {
            if (hitAction == IdeHitAction.ProjectChoice) projectSwitcherOpen = false
            focusArea =
                when {
                    hitAction == IdeHitAction.Terminal && terminalOverlay.visible -> IdeFocusArea.Terminal

                    hitAction in
                        setOf(
                            IdeHitAction.FindFocus,
                            IdeHitAction.FindPrevious,
                            IdeHitAction.FindNext,
                            IdeHitAction.FindClose,
                            IdeHitAction.DiagnosticChoice,
                        )
                    -> IdeFocusArea.Editor

                    geometry.editor.contains(uiEvent.x(), uiEvent.y()) -> IdeFocusArea.Editor

                    geometry.tree?.contains(uiEvent.x(), uiEvent.y()) == true -> IdeFocusArea.Tree

                    else -> IdeFocusArea.Panel
                }
            host.clearFocus()
            if (focusArea == IdeFocusArea.Terminal) terminalOverlay.focus() else terminalOverlay.focusLost()
            return true
        }
        focusArea =
            when {
                geometry.editor.contains(uiEvent.x(), uiEvent.y()) -> IdeFocusArea.Editor
                geometry.tree?.contains(uiEvent.x(), uiEvent.y()) == true -> IdeFocusArea.Tree
                geometry.panel.contains(uiEvent.x(), uiEvent.y()) -> IdeFocusArea.Panel
                else -> IdeFocusArea.None
            }
        terminalOverlay.focusLost()
        input.pointerActivity()
        return focusArea != IdeFocusArea.None
    }

    fun mouseDragged(event: IdePointerInput): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val uiEvent = event.map(viewport)
        val geometry = geometry()
        if (terminalOverlay.visible && terminalOverlayGeometry(geometry).panel.contains(uiEvent.x(), uiEvent.y())) return true
        if (splitters.drag(uiEvent.x().toInt(), uiEvent.y().toInt(), geometry)) return true
        if (input.explorerDragged(uiEvent.x(), uiEvent.y(), pointerContext(geometry))) return true
        if (focusArea == IdeFocusArea.Editor) {
            return input.pointerClicked(
                uiEvent.x(),
                uiEvent.y(),
                uiEvent.modifiers() or IdeModifier.SHIFT,
                pointerContext(geometry),
            )
        }
        return false
    }

    fun mouseReleased(event: IdePointerInput): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val uiEvent = event.map(viewport)
        if (splitters.release()) return true
        if (input.explorerReleased(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), pointerContext(geometry()))) return true
        return false
    }

    fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val viewport = viewport()
        if (!viewport.supported) return true
        val uiMouseX = viewport.toVirtualX(mouseX)
        val uiMouseY = viewport.toVirtualY(mouseY)
        val geometry = geometry()
        if (terminalOverlay.visible && terminalOverlayGeometry(geometry).panel.contains(uiMouseX, uiMouseY)) return true
        return input.scroll(uiMouseX, uiMouseY, scrollX, scrollY, pointerContext(geometry))
    }

    fun mouseMoved(
        mouseX: Double,
        mouseY: Double,
    ) {
        val viewport = viewport()
        if (viewport.supported) {
            val uiX = viewport.toVirtualX(mouseX)
            val uiY = viewport.toVirtualY(mouseY)
            pointerX = uiX
            pointerY = uiY
            val modifiers = if (controlDown) IdeModifier.CONTROL else 0
            input.pointerMoved(
                uiX,
                uiY,
                modifiers,
                pointerContext(geometry()),
            )
        }
    }

    fun keyPressed(event: IdeKeyInput): Boolean {
        if (
            event.key == IdeKeyCode.LEFT_CONTROL || event.key == IdeKeyCode.RIGHT_CONTROL ||
            event.modifiers and IdeModifier.CONTROL != 0
        ) {
            controlDown = true
        }
        if (!viewport().supported) return event.key != IdeKeyCode.ESCAPE
        if (prompt.state != null) {
            return when {
                event.key == IdeKeyCode.ESCAPE -> prompt.cancel()
                event.key == IdeKeyCode.ENTER -> confirmPrompt()
                event.key == IdeKeyCode.BACKSPACE -> prompt.backspace()
                event.paste -> prompt.type(host.clipboard)
                else -> true
            }
        }
        if (controller.viewState().dialog != null) return input.keyPressed(event, focusState())
        if (event.key == IdeKeyCode.ESCAPE && projectSwitcherOpen) {
            projectSwitcherOpen = false
            return true
        }
        if (event.key == IdeKeyCode.ESCAPE && input.cancelExplorerDrag()) return true
        if (splitters.captured) return true
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.keyPressed(event, host.clipboard)) {
            return true
        }
        if (
            focusArea == IdeFocusArea.Editor &&
            (event.key == IdeKeyCode.LEFT_CONTROL || event.key == IdeKeyCode.RIGHT_CONTROL)
        ) {
            val x = pointerX
            val y = pointerY
            if (x != null && y != null) input.pointerMoved(x, y, IdeModifier.CONTROL, pointerContext(geometry()))
        }
        return input.keyPressed(event, focusState())
    }

    fun keyReleased(event: IdeKeyInput): Boolean {
        if (event.key == IdeKeyCode.LEFT_CONTROL || event.key == IdeKeyCode.RIGHT_CONTROL) controlDown = false
        if (!viewport().supported) return true
        val semanticHandled = input.keyReleased(event)
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.keyReleased(event.key)) return true
        return semanticHandled
    }

    fun charTyped(event: IdeCharacterInput): Boolean {
        if (!viewport().supported) return true
        if (prompt.state != null) return prompt.type(event.text)
        val inputEvent = IdeCharacterInput(event.text)
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.charTyped(inputEvent)) return true
        return input.charTyped(inputEvent, focusState())
    }

    fun removed() {
        input.cancelExplorerDrag()
        splitters.focusLost()
        terminalOverlay.focusLost()
        if (!sessionClosed) controller.dispatch(IdeCommand.EditorFocusLost)
        if (!returningToParent) {
            host.abandonParent()
            closeSession()
        }
    }

    fun tick() {
        controller.tick()
        terminalOverlay.setTarget(
            controller
                .viewState()
                .target
                .terminalReference(),
        )
        if (controller.isCloseReady()) restoreParent()
    }

    fun frame(
        mouseX: Int,
        mouseY: Int,
    ): IdeScreenFrame {
        val viewport = viewport()
        val geometry = geometry(viewport)
        val treeFirstRow = admittedTreeFirstRow(geometry)
        val state = controller.viewState()
        val model =
            IdeRenderer.extract(
                state,
                geometry,
                prompt = prompt.state,
                treeFirstRow = treeFirstRow,
                selectedTreePath = selectedTreePath,
                terminalState = terminalOverlay.state().presentationStatus(),
                terminalVisible = terminalOverlay.visible,
                explorerDrag = input.explorerDragVisual,
                projectFirstRow = input.projectFirstRow,
                projectSwitcherOpen = projectSwitcherOpen && prompt.state == null && state.dialog == null,
                uiScale = preferences.uiScale(),
                viewportSupported = viewport().supported,
                pointerX = viewport.toVirtualX(mouseX.toDouble()).toInt(),
                pointerY = viewport.toVirtualY(mouseY.toDouble()).toInt(),
            )
        IdeVisibleFrameEvidence.from(state, model)?.let { evidence ->
            visibleLatency.frameExtracted(
                evidence.documentRevision,
                evidence.presentationVisible,
                evidence.completionVisible,
            )
        }
        return IdeScreenFrame(
            viewport,
            geometry,
            model,
            terminalOverlay.state(),
            terminalOverlay.visible && viewport.supported,
            terminalOverlay.focused,
        )
    }

    private var projectTreeVisible = true

    private fun geometry(viewport: CompuktersUiViewport = viewport()): IdeRenderGeometry {
        val layout = splitters.layout
        val workspace = (controller.viewState().page as? IdePageState.Workspace)?.value
        return IdeRenderGeometry.compute(
            viewport.width,
            viewport.height,
            layout.treeWidth,
            layout.diagnosticsHeight,
            workspace?.bottom?.tab != null,
            treeVisible = workspace != null && projectTreeVisible,
            IdeCodeFontProfile.DEFAULT,
            findVisible =
                (workspace?.editor as? IdeEditorView.Text)?.find != null,
        )
    }

    private fun viewport(): CompuktersUiViewport = host.viewport()

    private fun focusState(): IdeFocusState {
        val state = controller.viewState()
        val editor = ((state.page as? IdePageState.Workspace)?.value?.editor as? IdeEditorView.Text)
        val analysis = editor?.analysis as? IdeAnalysisState.Active
        val completion = analysis?.completion != null
        val chooser =
            (analysis?.interaction as? IdeSemanticInteraction.Chooser) != null
        return IdeFocusState(
            if ((state.page as? IdePageState.Workspace)?.value?.git?.visible == true) IdeFocusArea.Editor else focusArea,
            completion,
            chooser,
            state.dialog,
            geometry().codeRows.coerceAtLeast(1),
            analysis?.parameterInfo != null,
            gitVisible = (state.page as? IdePageState.Workspace)?.value?.git?.visible == true,
            gitScrollMaximum = pointerContext(geometry()).gitScrollMaximum,
            gitFieldFocusScroll = pointerContext(geometry()).gitFieldFocusScroll,
            gitDraft = (state.page as? IdePageState.Workspace)?.value?.git?.draft,
            gitMenu = (state.page as? IdePageState.Workspace)?.value?.git?.menu,
            findVisible = editor?.find != null,
            usagesFocused =
                (state.page as? IdePageState.Workspace)?.value?.let {
                    it.bottom.tab == ru.lazyhat.compukters.ide.client.state.IdeBottomTab.Problems && it.usages?.focused == true
                } == true,
            findFocused = editor?.find?.focused == true,
            findSelectedText = editor?.find?.let { find -> find.querySelection?.let { find.query.substring(it.startUtf16, it.endUtf16) } },
        )
    }

    private fun pointerContext(geometry: IdeRenderGeometry): IdePointerContext {
        val state = controller.viewState()
        val treeFirstRow = admittedTreeFirstRow(geometry)
        val model =
            IdeRenderer.extract(
                state,
                geometry,
                prompt = prompt.state,
                treeFirstRow = treeFirstRow,
                selectedTreePath = selectedTreePath,
                terminalState = terminalOverlay.state().presentationStatus(),
                terminalVisible = terminalOverlay.visible,
                explorerDrag = input.explorerDragVisual,
                projectFirstRow = input.projectFirstRow,
                projectSwitcherOpen = projectSwitcherOpen && prompt.state == null && state.dialog == null,
                uiScale = preferences.uiScale(),
                viewportSupported = viewport().supported,
            )
        return when (val page = state.page) {
            IdePageState.Opening -> {
                IdePointerContext(geometry)
            }

            is IdePageState.Start -> {
                IdePointerContext(
                    geometry,
                    projects = page.projects,
                    hitTargets = model.hitTargets,
                    dialog = state.dialog,
                )
            }

            is IdePageState.Workspace -> {
                IdePointerContext(
                    geometry,
                    editor = (page.value.editor as? IdeEditorView.Text).takeUnless { page.value.git.visible },
                    gitVisible = page.value.git.visible,
                    gitScrollMaximum = model.gitScrollMaximum ?: 0,
                    gitDraft = page.value.git.draft,
                    gitPreviewScrollMaximum = model.gitPreviewScrollMaximum ?: 0,
                    gitPreviewBounds = model.gitPreviewBounds,
                    bottomTab = page.value.bottom.tab,
                    bottomScrollMaximum = model.bottomScrollMaximum,
                    gitFieldFocusScroll = model.gitFieldFocusScroll,
                    usages =
                        page.value.usages.takeIf {
                            page.value.bottom.tab ==
                                ru.lazyhat.compukters.ide.client.state.IdeBottomTab.Problems
                        },
                    projects = page.value.projects,
                    tree = page.value.tree.flatten(),
                    explorer = page.value.explorerRows(),
                    treeFirstRow = treeFirstRow,
                    hitTargets = model.hitTargets,
                    dialog = state.dialog,
                )
            }
        }
    }

    private fun activateUiAction(action: IdeHitAction): Boolean {
        when (action) {
            IdeHitAction.ScaleAuto, IdeHitAction.ScaleTwo, IdeHitAction.ScaleThree -> {
                preferences.saveUiScale(IdeUiScale.entries.single { it.action == action })
            }

            IdeHitAction.CreateProject -> {
                projectSwitcherOpen = false
                prompt.open(IdePromptKind.CreateProject)
            }

            IdeHitAction.OpenProject -> {
                focusArea = IdeFocusArea.Editor
            }

            IdeHitAction.ProjectSwitcher -> {
                projectSwitcherOpen = !projectSwitcherOpen
                if (projectSwitcherOpen) terminalOverlay.hide()
            }

            IdeHitAction.OpenExisting -> {
                projectSwitcherOpen = false
                prompt.open(IdePromptKind.OpenExisting)
            }

            IdeHitAction.CloneProject -> {
                projectSwitcherOpen = false
                prompt.open(IdePromptKind.CloneRemote)
            }

            IdeHitAction.ProjectTool -> {
                projectTreeVisible = !projectTreeVisible
                focusArea = if (projectTreeVisible) IdeFocusArea.Tree else IdeFocusArea.Editor
            }

            IdeHitAction.GitToggle -> {
                val page = controller.viewState().page as? IdePageState.Workspace ?: return false
                controller.dispatch(IdeCommand.GitVisible(!page.value.git.visible))
                focusArea = IdeFocusArea.Editor
            }

            IdeHitAction.GitRemote -> {
                controller.dispatch(IdeCommand.GitMenu(null))
                prompt.open(IdePromptKind.GitRemoteUrl)
            }

            IdeHitAction.GitBranch -> {
                controller.dispatch(IdeCommand.GitMenu(null))
                prompt.open(IdePromptKind.GitBranch)
            }

            IdeHitAction.GitCommit -> {
                controller.dispatch(IdeCommand.GitCommitDraft())
            }

            IdeHitAction.GitAuthenticate -> {
                controller.dispatch(IdeCommand.GitMenu(null))
                projectSwitcherOpen = false
                prompt.open(IdePromptKind.GitUsername)
            }

            IdeHitAction.CreateText -> {
                prompt.open(IdePromptKind.CreateText, "src/")
            }

            IdeHitAction.CreateDirectory -> {
                prompt.open(IdePromptKind.CreateDirectory, "src/")
            }

            IdeHitAction.Rename -> {
                val path = selectedTreePath ?: activeFile() ?: return false
                prompt.open(IdePromptKind.Rename(path), path.value)
            }

            IdeHitAction.RenameSymbol -> {
                val workspace = controller.viewState().page as? IdePageState.Workspace ?: return false
                val editor = workspace.value.editor as? IdeEditorView.Text ?: return false
                if (editor.readOnly || editor.path?.value?.endsWith(".kt") != true) return false
                prompt.open(IdePromptKind.RenameSymbol)
            }

            IdeHitAction.Delete -> {
                val path = selectedTreePath ?: activeFile() ?: return false
                controller.dispatch(IdeCommand.RequestDelete(path))
            }

            IdeHitAction.Terminal -> {
                terminalOverlay.toggle()
                focusArea = if (terminalOverlay.visible) IdeFocusArea.Terminal else IdeFocusArea.Editor
            }

            IdeHitAction.Confirm -> {
                return confirmPrompt()
            }

            IdeHitAction.Dismiss -> {
                return prompt.cancel()
            }

            else -> {
                return false
            }
        }
        host.clearFocus()
        return true
    }

    private fun terminalOverlayGeometry(geometry: IdeRenderGeometry): IdeTerminalOverlayGeometry =
        IdeTerminalOverlayGeometry.compute(geometry.content)

    private fun IdeTargetState.terminalReference(): IdeTargetReference? =
        attachedTarget()?.takeIf { it.capabilities.terminal }?.let { IdeTargetReference(it.id, it.profile) }

    private fun IdeTargetState.attachedTarget(): IdeAttachedTarget? =
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

    private fun activateProjectAction(
        action: IdeHitAction,
        project: ru.lazyhat.compukters.ide.client.state.IdeProjectSummary,
    ): Boolean {
        if (action != IdeHitAction.RenameProjectFolder || project.external) return false
        projectSwitcherOpen = false
        prompt.open(IdePromptKind.RenameProject(project.directoryName), project.displayName)
        return true
    }

    private fun confirmPrompt(): Boolean {
        val command = prompt.confirm() ?: return true
        controller.dispatch(command)
        return true
    }

    private fun restoreParent() {
        returningToParent = true
        closeSession()
        host.restoreParent()
    }

    private fun closeSession() {
        if (sessionClosed) return
        sessionClosed = true
        host.closeSession()
    }

    private fun activeFile(): ProjectPath? = ((controller.viewState().page as? IdePageState.Workspace)?.value?.activeFile)

    private fun admittedTreeFirstRow(geometry: IdeRenderGeometry): Int {
        val entries = (controller.viewState().page as? IdePageState.Workspace)?.value?.explorerRows()?.size ?: 0
        return input.clampTree(entries, geometry.tree?.height ?: 0)
    }

    private fun selectTreeRow(
        x: Double,
        y: Double,
        geometry: IdeRenderGeometry,
    ) {
        val treeBounds = geometry.tree ?: return
        if (!treeBounds.contains(x, y)) return
        val workspace = (controller.viewState().page as? IdePageState.Workspace)?.value ?: return
        val row = input.treeFirstRow + ((y - treeBounds.top - TREE_ROWS_TOP).toInt() / UI_LINE_HEIGHT)
        selectedTreePath = (workspace.explorerRows().getOrNull(row) as? IdeExplorerRow.ProjectEntry)?.entry?.path
    }

    private fun IdeRect.contains(
        x: Double,
        y: Double,
    ): Boolean = x >= left && x < right && y >= top && y < bottom

    private companion object {
        const val UI_LINE_HEIGHT = 12
        const val TREE_ROWS_TOP = 4
    }
}

private fun IdeTargetTerminalState.presentationStatus(): IdeTerminalStatus =
    when (this) {
        IdeTargetTerminalState.Closed -> IdeTerminalStatus.Closed
        is IdeTargetTerminalState.Opening -> IdeTerminalStatus.Opening
        is IdeTargetTerminalState.Active -> IdeTerminalStatus.Active
        is IdeTargetTerminalState.Resyncing -> IdeTerminalStatus.Resyncing
        is IdeTargetTerminalState.Failed -> IdeTerminalStatus.Failed(detail, retryable)
    }

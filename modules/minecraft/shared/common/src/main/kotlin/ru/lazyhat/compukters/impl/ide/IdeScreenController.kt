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
import ru.lazyhat.compukters.ide.client.state.IdeViewState
import ru.lazyhat.compukters.ide.client.target.IdeAttachedTarget
import ru.lazyhat.compukters.ide.client.target.IdeTargetState
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import ru.lazyhat.compukters.impl.ide.target.IdeNamedPeripheral
import ru.lazyhat.compukters.impl.ide.target.IdePeripheralReference
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReference
import ru.lazyhat.compukters.impl.ide.target.IdeTargetReply
import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalClient
import ru.lazyhat.compukters.impl.ide.target.IdeTargetTerminalState
import ru.lazyhat.compukters.impl.ui.CompuktersUiViewport
import java.util.concurrent.CompletableFuture

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
    private val peripheralRequest: (
        IdeTargetReference,
    ) -> CompletableFuture<IdeTargetReply>? = { null },
    private val peripheralRename: (
        IdeTargetReference,
        IdePeripheralReference,
        String,
        String,
    ) -> CompletableFuture<IdeTargetReply>? = { _, _, _, _ -> null },
    private val peripheralInspection: (IdeTargetReference) -> CompletableFuture<IdeTargetReply>? = { null },
) {
    private val overlays =
        IdeLocalOverlays(
            ::openQuickFile,
            ::confirmPrompt,
            { event, doubleClick, geometry ->
                input.pointerClicked(event.x(), event.y(), event.modifiers(), pointerContext(geometry), doubleClick)
            },
            { host.clipboard },
            { host.clipboard = it },
        )
    private val prompt get() = overlays.prompt
    private val quickOpen get() = overlays.quickOpen
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
    private val peripheralObserver = IdePeripheralObserver(peripheralInspection, controller::observePeripherals)
    private var peripheralPicker: IdePeripheralPickerView? = null
    private var peripheralFuture: CompletableFuture<IdeTargetReply>? = null
    private var peripheralTarget: IdeTargetReference? = null
    private var peripheralRenameEntry: IdeNamedPeripheral? = null
    private var peripheralRenameFuture: CompletableFuture<IdeTargetReply>? = null

    private var fileTabFirstIndex = 0
    private var lastActiveTab: ProjectPath? = null
    private var lastFileTabWidth = 0

    fun mouseClicked(
        event: IdePointerInput,
        doubleClick: Boolean,
    ): Boolean {
        val viewport = viewport()
        val uiEvent = event.map(viewport)
        if (uiEvent.button() == 0 &&
            (!viewport.supported || (!overlays.active && controller.viewState().dialog == null))
        ) {
            IdeUiScaleControl.hit(IdeRect(0, 0, viewport.width, viewport.height), uiEvent.x(), uiEvent.y())?.let { mode ->
                preferences.saveUiScale(mode)
                return true
            }
        }
        if (!viewport.supported) return true
        val geometry = geometry()
        val state = controller.viewState()
        synchronizeQuickOpen()
        if (overlays.press(uiEvent, doubleClick, geometry)) {
            input.pointerActivity()
            host.clearFocus()
            return true
        }
        if (state.dialog != null) {
            input.pointerClicked(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), pointerContext(geometry), doubleClick)
            host.clearFocus()
            return true
        }
        if ((state.page as? IdePageState.Workspace)?.value?.git?.menu != null) {
            input.pointerClicked(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), pointerContext(geometry), doubleClick)
            focusArea =
                if ((controller.viewState().page as? IdePageState.Workspace)?.value?.git?.menu !=
                    null
                ) {
                    IdeFocusArea.Git
                } else {
                    IdeFocusArea.Editor
                }
            host.clearFocus()
            return true
        }
        peripheralPicker?.takeIf { !projectSwitcherOpen && !terminalOverlay.visible }?.let {
            if (IdePeripheralPicker.bounds(geometry.content).contains(uiEvent.x(), uiEvent.y())) {
                val hit =
                    presentation(geometry).model.hitTargets.asReversed().firstOrNull { target ->
                        target.bounds.contains(uiEvent.x(), uiEvent.y())
                    }
                focusArea = IdeFocusArea.Peripherals
                terminalOverlay.focusLost()
                if (uiEvent.button() == 0 && hit?.enabled == true) {
                    if (hit.action == IdeHitAction.PeripheralChoice) {
                        val sameDevice = it.selectedIndex == hit.choiceIndex
                        peripheralPicker = it.copy(selectedIndex = hit.choiceIndex, lookupIndex = 0, message = null)
                        if (doubleClick && sameDevice) activateUiAction(IdeHitAction.PeripheralInsert)
                    } else {
                        activateUiAction(hit.action)
                    }
                }
                input.pointerActivity()
                host.clearFocus()
                return true
            }
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
            overlay.grid?.let { terminalOverlay.press(it, uiEvent.x(), uiEvent.y(), uiEvent.button()) }
            host.clearFocus()
            input.pointerActivity()
            return true
        }
        if (splitters.press(uiEvent.x().toInt(), uiEvent.y().toInt(), geometry)) {
            input.pointerActivity()
            return true
        }
        val scrollModel = presentation(geometry).model
        val bars =
            if (scrollModel.hoverBounds?.contains(uiEvent.x(), uiEvent.y()) == true) {
                scrollModel.scrollbars.filter { it.target == IdeScrollTarget.Hover }
            } else {
                scrollModel.scrollbars
            }
        if (uiEvent.button() == 0 && scrollbarInteraction.press(bars, uiEvent.x(), uiEvent.y(), ::moveScrollbar)) return true
        if (scrollModel.hoverBounds?.contains(uiEvent.x(), uiEvent.y()) == true) return true
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
                    hitAction == IdeHitAction.StatusDetails -> {
                        focusArea
                    }

                    hitAction == IdeHitAction.Peripherals && peripheralPicker != null -> {
                        IdeFocusArea.Peripherals
                    }

                    hitAction == IdeHitAction.Terminal && terminalOverlay.visible -> {
                        IdeFocusArea.Terminal
                    }

                    hitAction in
                        setOf(
                            IdeHitAction.FindFocus,
                            IdeHitAction.FindPrevious,
                            IdeHitAction.FindNext,
                            IdeHitAction.FindClose,
                            IdeHitAction.DiagnosticChoice,
                            IdeHitAction.FileTab,
                            IdeHitAction.CloseFileTab,
                            IdeHitAction.PreviousFileTabs,
                            IdeHitAction.NextFileTabs,
                        )
                    -> {
                        IdeFocusArea.Editor
                    }

                    geometry.editor.contains(uiEvent.x(), uiEvent.y()) -> {
                        IdeFocusArea.Editor
                    }

                    geometry.tree?.contains(uiEvent.x(), uiEvent.y()) == true -> {
                        if (pointerContext.gitVisible) IdeFocusArea.Git else IdeFocusArea.Tree
                    }

                    else -> {
                        IdeFocusArea.Panel
                    }
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
        synchronizeQuickOpen()
        if (overlays.drag(uiEvent, geometry.panel)) return true
        if (controller.viewState().dialog != null) {
            scrollbarInteraction.release()
            return true
        }
        if (scrollbarInteraction.drag(uiEvent.x(), uiEvent.y(), ::moveScrollbar)) return true
        if (terminalOverlay.visible) {
            val overlay = terminalOverlayGeometry(geometry)
            if (overlay.grid?.let { terminalOverlay.drag(it, uiEvent.x(), uiEvent.y()) } == true) return true
            if (overlay.panel.contains(uiEvent.x(), uiEvent.y())) return true
        }
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
        synchronizeQuickOpen()
        if (terminalOverlay.release()) return true
        if (scrollbarInteraction.release()) return true
        if (overlays.release()) return true
        if (controller.viewState().dialog != null) return true
        if (splitters.release()) return true
        if (input.explorerReleased(uiEvent.x(), uiEvent.y(), uiEvent.modifiers(), pointerContext(geometry()))) return true
        return false
    }

    private fun moveScrollbar(
        target: IdeScrollTarget,
        position: Int,
    ) {
        if (target == IdeScrollTarget.Hover) {
            hoverViewport.move(position)
            return
        }
        val workspace = (controller.viewState().page as? IdePageState.Workspace)?.value ?: return
        val editor = workspace.editor as? IdeEditorView.Text ?: return
        controller.dispatch(
            IdeCommand.ScrollEditor(
                if (target == IdeScrollTarget.EditorLines) position - editor.firstVisibleLine else 0,
                if (target == IdeScrollTarget.EditorColumns) position - editor.firstVisibleColumn else 0,
            ),
        )
    }

    private val hoverViewport = IdeHoverViewport()
    private val scrollbarInteraction = IdeScrollbarInteraction()
    private var scrollbarDocument: Pair<ru.lazyhat.compukters.ide.client.state.IdeEditorSource, Long>? = null

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
        synchronizeQuickOpen()
        if (overlays.scroll(uiMouseX, uiMouseY, scrollX, scrollY, geometry.panel)) return true
        if (controller.viewState().dialog != null) return true
        if ((controller.viewState().page as? IdePageState.Workspace)?.value?.git?.menu != null) {
            return input.scroll(uiMouseX, uiMouseY, scrollX, scrollY, pointerContext(geometry))
        }
        peripheralPicker?.takeIf { !projectSwitcherOpen && !terminalOverlay.visible }?.let { view ->
            if (IdePeripheralPicker.bounds(geometry.content).contains(uiMouseX, uiMouseY)) {
                val maximum = (view.entries.size - IdePeripheralPicker.visibleRows(geometry.content)).coerceAtLeast(0)
                peripheralPicker = view.copy(firstRow = (view.firstRow - scrollY.toInt()).coerceIn(0, maximum))
                return true
            }
        }
        val hoverModel = presentation(geometry).model
        if (hoverViewport.scroll(hoverModel, uiMouseX, uiMouseY, scrollY)) return true
        if (terminalOverlay.visible && terminalOverlayGeometry(geometry).panel.contains(uiMouseX, uiMouseY)) {
            terminalOverlay.scroll(scrollY)
            return true
        }
        if (geometry.fileTabs?.contains(uiMouseX, uiMouseY) == true) {
            if (scrollY != 0.0 ||
                scrollX != 0.0
            ) {
                fileTabFirstIndex = (fileTabFirstIndex + if (scrollY < 0 || scrollX > 0) 1 else -1).coerceAtLeast(0)
            }
            return true
        }
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
            if (overlays.active || scrollbarInteraction.active) return
            if (presentation().model.hoverBounds?.contains(uiX, uiY) == true) return
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
        synchronizeQuickOpen()
        if (overlays.key(event, geometry().panel)) return true
        if (controller.viewState().dialog != null) return input.keyPressed(event, focusState())
        if ((controller.viewState().page as? IdePageState.Workspace)?.value?.git?.menu != null) {
            val handled = input.keyPressed(event, focusState())
            if ((controller.viewState().page as? IdePageState.Workspace)?.value?.git?.menu == null) focusArea = IdeFocusArea.Editor
            return handled
        }
        if (event.key == IdeKeyCode.N &&
            event.modifiers and (IdeModifier.CONTROL or IdeModifier.SHIFT) == (IdeModifier.CONTROL or IdeModifier.SHIFT) &&
            event.modifiers and IdeModifier.ALT == 0 && focusArea != IdeFocusArea.Terminal
        ) {
            return openQuickOpen()
        }
        if (event.key == IdeKeyCode.ESCAPE && peripheralPicker != null && !projectSwitcherOpen && !terminalOverlay.visible) {
            closePeripherals()
            return true
        }
        if (focusArea == IdeFocusArea.Peripherals && peripheralPicker != null && !projectSwitcherOpen && !terminalOverlay.visible) {
            if (event.key == IdeKeyCode.C &&
                event.modifiers and (IdeModifier.CONTROL or IdeModifier.SHIFT or IdeModifier.ALT) == IdeModifier.CONTROL
            ) {
                activateUiAction(IdeHitAction.PeripheralCopyName)
            }
            return true
        }
        if (event.key == IdeKeyCode.ESCAPE && projectSwitcherOpen) {
            projectSwitcherOpen = false
            return true
        }
        if (event.key == IdeKeyCode.ESCAPE && input.cancelExplorerDrag()) return true
        if (splitters.captured) return true
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.keyPressed(event, host.clipboard) { host.clipboard = it }) {
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
        if (overlays.active) return true
        val semanticHandled = input.keyReleased(event)
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.keyReleased(event.key)) return true
        return semanticHandled
    }

    fun charTyped(event: IdeCharacterInput): Boolean {
        if (!viewport().supported) return true
        synchronizeQuickOpen()
        if (overlays.type(event.text)) return true
        if (focusArea == IdeFocusArea.Peripherals && peripheralPicker != null && !projectSwitcherOpen &&
            !terminalOverlay.visible
        ) {
            return true
        }
        val inputEvent = IdeCharacterInput(event.text)
        if (focusArea == IdeFocusArea.Terminal && terminalOverlay.charTyped(inputEvent)) return true
        return input.charTyped(inputEvent, focusState())
    }

    fun removed() {
        overlays.close()
        input.cancelExplorerDrag()
        splitters.focusLost()
        terminalOverlay.focusLost()
        if (!sessionClosed && !controller.isClosed()) controller.dispatch(IdeCommand.EditorFocusLost)
        if (!returningToParent) {
            host.abandonParent()
            closeSession()
        }
    }

    fun tick() {
        if (sessionClosed || controller.isClosed()) return
        controller.tick()
        prompt.pollTokenCheck()
        peripheralObserver.tick(controller.viewState().target.attachedTarget())
        pollPeripherals()
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
        val snapshot =
            presentation(
                viewport = viewport,
                pointerX = viewport.toVirtualX(mouseX.toDouble()).toInt(),
                pointerY = viewport.toVirtualY(mouseY.toDouble()).toInt(),
            )
        val geometry = snapshot.geometry
        val state = snapshot.state
        val model = snapshot.model
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

    private fun geometry(
        viewport: CompuktersUiViewport = viewport(),
        state: IdeViewState = controller.viewState(),
    ): IdeRenderGeometry {
        val layout = splitters.layout
        val workspace = (state.page as? IdePageState.Workspace)?.value
        return IdeRenderGeometry.compute(
            viewport.width,
            viewport.height,
            layout.treeWidth,
            layout.diagnosticsHeight,
            workspace?.bottom?.tab != null,
            treeVisible = workspace != null && (projectTreeVisible || workspace.git.visible),
            IdeCodeFontProfile.DEFAULT,
            findVisible =
                (workspace?.editor as? IdeEditorView.Text)?.find != null,
            tabsVisible = workspace?.openFiles?.isNotEmpty() == true,
        )
    }

    private fun viewport(): CompuktersUiViewport = host.viewport()

    private fun admitFileTabScroll(
        geometry: IdeRenderGeometry,
        state: IdeViewState,
    ) {
        val workspace = (state.page as? IdePageState.Workspace)?.value
        val active = workspace?.activeFile
        val bounds = geometry.fileTabs
        if (workspace != null && bounds != null) {
            fileTabFirstIndex =
                IdeFileTabBar
                    .layout(
                        bounds,
                        workspace.openFiles,
                        fileTabFirstIndex,
                        if (active != lastActiveTab || bounds.width != lastFileTabWidth) {
                            active
                        } else {
                            null
                        },
                    ).firstIndex
        } else {
            fileTabFirstIndex = 0
        }
        lastActiveTab = active
        lastFileTabWidth = bounds?.width ?: 0
    }

    private fun focusState(): IdeFocusState = presentation().focus(focusArea)

    private fun pointerContext(geometry: IdeRenderGeometry): IdePointerContext = presentation(geometry).pointer

    private fun presentation(
        geometryOverride: IdeRenderGeometry? = null,
        viewport: CompuktersUiViewport = viewport(),
        pointerX: Int? = null,
        pointerY: Int? = null,
    ): IdePresentationSnapshot {
        val state = controller.viewState()
        val geometry = geometryOverride ?: geometry(viewport, state)
        synchronizeQuickOpen(state)
        prompt.admitLayout(geometry.panel)
        admitFileTabScroll(geometry, state)
        val treeFirstRow = admittedTreeFirstRow(geometry, state)
        val workspace = (state.page as? IdePageState.Workspace)?.value
        val analysis = (workspace?.editor as? IdeEditorView.Text)?.analysis as? IdeAnalysisState.Active
        val editor = workspace?.editor as? IdeEditorView.Text
        val document = editor?.let { it.source to it.contentRevision }
        if (document != scrollbarDocument) scrollbarInteraction.release()
        scrollbarDocument = document
        val hover = analysis?.interaction as? IdeSemanticInteraction.Hover
        hoverViewport.admit(hover)
        val model =
            IdeRenderer.extract(
                state,
                geometry,
                prompt = prompt.state,
                hoverScroll = hoverViewport.scroll,
                hoverDocumentation = hoverViewport.documentation,
                statusDetails = if (viewport.supported && geometry.supported) overlays.details.view(geometry.panel) else null,
                quickOpen = if (viewport.supported && geometry.supported) quickOpen.view(geometry.panel) else null,
                treeFirstRow = treeFirstRow,
                selectedTreePath = selectedTreePath,
                terminalState = terminalOverlay.state().presentationStatus(),
                terminalVisible = terminalOverlay.visible,
                explorerDrag = input.explorerDragVisual,
                projectFirstRow = input.projectFirstRow,
                fileTabFirstIndex = fileTabFirstIndex,
                peripheralPicker =
                    peripheralPicker
                        ?.takeIf {
                            !overlays.active && state.dialog == null && !projectSwitcherOpen &&
                                !terminalOverlay.visible
                        }?.copy(
                            writable =
                                (workspace?.editor as? IdeEditorView.Text)?.let {
                                    !it.readOnly && it.path?.value?.endsWith(
                                        ".kt",
                                    ) == true
                                } ==
                                    true,
                        ),
                projectSwitcherOpen = projectSwitcherOpen && !overlays.active && state.dialog == null,
                uiScale = preferences.uiScale(),
                viewportSupported = viewport.supported,
                pointerX = pointerX,
                pointerY = pointerY,
            )
        return IdePresentationSnapshot(state, geometry, model, treeFirstRow)
    }

    private fun cancelPeripheralRename() {
        peripheralRenameFuture?.cancel(false)
        peripheralRenameFuture = null
        peripheralRenameEntry = null
        if (prompt.state?.kind is IdePromptKind.RenamePeripheral) prompt.cancel()
    }

    private fun closePeripherals() {
        if (focusArea == IdeFocusArea.Peripherals) focusArea = IdeFocusArea.Editor
        cancelPeripheralRename()
        peripheralPicker = null
        peripheralFuture?.cancel(false)
        peripheralFuture = null
        peripheralTarget = null
    }

    private fun refreshPeripherals() {
        cancelPeripheralRename()
        peripheralFuture?.cancel(false)
        peripheralFuture = null
        peripheralTarget =
            controller
                .viewState()
                .target
                .attachedTarget()
                ?.let { IdeTargetReference(it.id, it.profile) }
        val target = peripheralTarget
        if (target == null) {
            peripheralPicker = IdePeripheralPickerView(message = "Attach to a computer")
            return
        }
        peripheralFuture = peripheralRequest(target)
        peripheralPicker =
            IdePeripheralPickerView(
                loading = peripheralFuture != null,
                message =
                    if (peripheralFuture ==
                        null
                    ) {
                        "Discovery unavailable"
                    } else {
                        null
                    },
            )
    }

    private fun pollPeripherals() {
        if (peripheralPicker == null) return
        if (controller.viewState().page !is IdePageState.Workspace) {
            closePeripherals()
            return
        }
        val target =
            controller
                .viewState()
                .target
                .attachedTarget()
                ?.let { IdeTargetReference(it.id, it.profile) }
        if (target != peripheralTarget) {
            refreshPeripherals()
            return
        }
        pollPeripheralRename()
        val future = peripheralFuture ?: return
        if (!future.isDone) return
        peripheralFuture = null
        val reply = runCatching { future.join() }.getOrNull()
        peripheralPicker =
            when (reply) {
                is IdeTargetReply.NamedPeripherals -> {
                    IdePeripheralPickerView(
                        reply.entries,
                        if (reply.truncated) "List truncated" else null,
                    )
                }

                is IdeTargetReply.Failed -> {
                    IdePeripheralPickerView(message = reply.failure.detail)
                }

                else -> {
                    IdePeripheralPickerView(message = "Peripheral discovery failed")
                }
            }
    }

    private fun activateUiAction(action: IdeHitAction): Boolean {
        when (action) {
            IdeHitAction.Peripherals -> {
                if (peripheralPicker != null) {
                    closePeripherals()
                } else {
                    projectSwitcherOpen = false
                    terminalOverlay.hide()
                    peripheralPicker = IdePeripheralPickerView()
                    refreshPeripherals()
                }
            }

            IdeHitAction.PeripheralRefresh -> {
                refreshPeripherals()
            }

            IdeHitAction.PeripheralClose -> {
                closePeripherals()
            }

            IdeHitAction.PeripheralInsert -> {
                val entry = peripheralPicker?.let(IdePeripheralPicker::selected) ?: return false
                val editor = (controller.viewState().page as? IdePageState.Workspace)?.value?.editor as? IdeEditorView.Text
                if (entry.duplicate || peripheralPicker?.loading == true || editor == null || editor.readOnly ||
                    editor.path?.value?.endsWith(".kt") != true
                ) {
                    return false
                }
                controller.dispatch(IdeCommand.InsertPeripheralConstant(entry.name))
            }

            IdeHitAction.PeripheralCopyName -> {
                val view = peripheralPicker ?: return false
                if (view.loading) return false
                host.clipboard = IdePeripheralPicker.selected(view)?.name ?: return false
                peripheralPicker = view.copy(message = "Name copied")
            }

            IdeHitAction.PeripheralCopyLookup -> {
                val view = peripheralPicker ?: return false
                if (view.loading) return false
                host.clipboard = IdePeripheralPicker.lookup(view) ?: return false
                peripheralPicker = view.copy(message = "Lookup copied")
            }

            IdeHitAction.PeripheralLookupType -> {
                val view = peripheralPicker ?: return false
                val count = IdePeripheralPicker.selected(view)?.lookupTypes?.size ?: return false
                if (count == 0 || view.loading) return false
                peripheralPicker = view.copy(lookupIndex = (view.lookupIndex + 1) % count, message = null)
            }

            IdeHitAction.PeripheralRename -> {
                val view = peripheralPicker ?: return false
                val entry = IdePeripheralPicker.selected(view) ?: return false
                if (entry.reference == null || view.loading || peripheralTarget == null) return false
                peripheralRenameEntry = entry
                prompt.open(IdePromptKind.RenamePeripheral(entry.name), entry.name)
                controller.dispatch(IdeCommand.EditorFocusLost)
                host.clearFocus()
            }

            IdeHitAction.StatusDetails -> {
                val state = controller.viewState()
                val workspace = (state.page as? IdePageState.Workspace)?.value ?: return false
                if (overlays.active || state.dialog != null || splitters.captured) return false
                projectSwitcherOpen = false
                input.cancelExplorerDrag()
                terminalOverlay.hide()
                if (focusArea == IdeFocusArea.Terminal) focusArea = IdeFocusArea.Editor
                controller.dispatch(IdeCommand.EditorFocusLost)
                overlays.details.open(
                    workspace.project.directoryName,
                    IdeStatusBar.messages(workspace, state.target, state.tooling, state.busy),
                )
                host.clearFocus()
                return true
            }

            IdeHitAction.QuickOpen -> {
                return openQuickOpen()
            }

            IdeHitAction.PreviousFileTabs -> {
                fileTabFirstIndex = (fileTabFirstIndex - 1).coerceAtLeast(0)
            }

            IdeHitAction.NextFileTabs -> {
                fileTabFirstIndex++
            }

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
                val workspace = (controller.viewState().page as? IdePageState.Workspace)?.value
                if (workspace?.git?.visible == true) {
                    controller.dispatch(IdeCommand.GitVisible(false))
                    projectTreeVisible = true
                } else {
                    projectTreeVisible = !projectTreeVisible
                }
                focusArea = if (projectTreeVisible) IdeFocusArea.Tree else IdeFocusArea.Editor
            }

            IdeHitAction.GitToggle -> {
                val page = controller.viewState().page as? IdePageState.Workspace ?: return false
                controller.dispatch(IdeCommand.GitVisible(!page.value.git.visible))
                focusArea = IdeFocusArea.Git
            }

            IdeHitAction.GitRemote -> {
                val workspace = (controller.viewState().page as? IdePageState.Workspace)?.value
                prompt.open(
                    IdePromptKind.GitRemoteUrl,
                    workspace
                        ?.git
                        ?.status
                        ?.remote
                        .orEmpty(),
                )
            }

            IdeHitAction.GitBranch -> {
                controller.dispatch(IdeCommand.GitMenu(null))
                prompt.open(IdePromptKind.GitBranch)
            }

            IdeHitAction.GitCommit -> {
                controller.dispatch(IdeCommand.GitCommitDraft())
            }

            IdeHitAction.GitAuthenticate -> {
                projectSwitcherOpen = false
                prompt.open(IdePromptKind.GitServer, "https://${controller.gitCredentialScope().authority}")
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

    private fun synchronizeQuickOpen(state: IdeViewState = controller.viewState()) {
        overlays.synchronize(state)
    }

    private fun openQuickOpen(): Boolean {
        val state = controller.viewState()
        val workspace = (state.page as? IdePageState.Workspace)?.value ?: return false
        if (prompt.state != null || state.dialog != null || state.busy.isNotEmpty() || splitters.captured) return false
        projectSwitcherOpen = false
        input.cancelExplorerDrag()
        input.pointerActivity()
        terminalOverlay.focusLost()
        controller.dispatch(IdeCommand.EditorFocusLost)
        quickOpen.open(workspace)
        host.clearFocus()
        return true
    }

    private fun openQuickFile(path: ProjectPath) {
        controller.dispatch(IdeCommand.OpenFile(path))
        focusArea = IdeFocusArea.Editor
        host.clearFocus()
    }

    private fun confirmPrompt(): Boolean {
        if (prompt.state?.kind is IdePromptKind.RenamePeripheral) return confirmPeripheralRename()
        if (prompt.state?.kind is IdePromptKind.GitToken && prompt.state?.tokenCheck?.canSave != true) {
            prompt.checkToken(controller::validateGitCredentials)
            return true
        }
        val command = prompt.confirm() ?: return true
        controller.dispatch(command)
        return true
    }

    private fun confirmPeripheralRename(): Boolean {
        if (peripheralRenameFuture != null) return true
        val entry = peripheralRenameEntry ?: return true
        val target = peripheralTarget ?: return true
        val reference = entry.reference ?: return true
        val name =
            prompt.state
                ?.value
                ?.trim()
                ?.lowercase(java.util.Locale.ROOT) ?: return true
        if (!name.matches(Regex("[a-z][a-z0-9_-]{0,31}"))) {
            prompt.error("Use 1–32 letters, digits, underscores or hyphens, starting with a letter")
            return true
        }
        val current =
            controller
                .viewState()
                .target
                .attachedTarget()
                ?.let { IdeTargetReference(it.id, it.profile) }
        if (current != target) {
            prompt.error("Computer attachment changed; refresh Devices")
            return true
        }
        peripheralRenameFuture = runCatching { peripheralRename(target, reference, entry.name, name) }.getOrNull()
        if (peripheralRenameFuture == null) {
            prompt.error("Device rename unavailable")
        } else {
            peripheralPicker = peripheralPicker?.copy(loading = true, message = "Renaming…")
        }
        return true
    }

    private fun pollPeripheralRename() {
        val future = peripheralRenameFuture ?: return
        if (!future.isDone) return
        peripheralRenameFuture = null
        val entry = peripheralRenameEntry
        val view = peripheralPicker ?: return
        when (val reply = runCatching { future.join() }.getOrNull()) {
            is IdeTargetReply.NamedPeripherals -> {
                val index = reply.entries.indexOfFirst { it.reference == entry?.reference }.takeIf { it >= 0 }
                peripheralPicker =
                    view.copy(entries = reply.entries, selectedIndex = index, loading = false, lookupIndex = 0, message = "Device renamed")
                if (prompt.state?.kind is IdePromptKind.RenamePeripheral) prompt.cancel()
                peripheralRenameEntry = null
            }

            else -> {
                val message = (reply as? IdeTargetReply.Failed)?.failure?.detail ?: "Device rename failed"
                peripheralPicker = view.copy(loading = false, message = message)
                if (prompt.state?.kind is IdePromptKind.RenamePeripheral) prompt.error(message)
            }
        }
    }

    private fun restoreParent() {
        returningToParent = true
        closeSession()
        host.restoreParent()
    }

    private fun closeSession() {
        closePeripherals()
        if (sessionClosed) return
        sessionClosed = true
        peripheralObserver.close()
        host.closeSession()
    }

    private fun activeFile(): ProjectPath? = ((controller.viewState().page as? IdePageState.Workspace)?.value?.activeFile)

    private fun admittedTreeFirstRow(
        geometry: IdeRenderGeometry,
        state: IdeViewState,
    ): Int {
        val entries = (state.page as? IdePageState.Workspace)?.value?.explorerRows()?.size ?: 0
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
        if (workspace.git.visible) return
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

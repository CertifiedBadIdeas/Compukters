/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukterkraft.core.computer.workbench

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.frontend.SourceTextSupport

/**
 * Client-side state container for the Workbench authoring GUI.
 *
 * All state is exposed via [stateFlow] ([StateFlow]) so consumers can
 * observe changes reactively. Synchronous reads use [state] (delegates to `.value`).
 *
 * Remote workspace updates arrive through a [WorkbenchUpdateSource]'s [StateFlow].
 * Call [bind] with a [CoroutineScope] to start reactively collecting remote state changes.
 */
class WorkbenchStore(
    private val workspaceGateway: WorkspaceGateway,
    private val controlGateway: ComputerControlGateway,
    private val ideFacade: WorkbenchIdeFacade,
) {
    private val _state = MutableStateFlow(WorkbenchState())

    /** Observable workbench state. */
    val stateFlow: StateFlow<WorkbenchState> = _state.asStateFlow()

    /** Current workbench state (synchronous read). */
    val state: WorkbenchState get() = _state.value

    private var collectJob: Job? = null

    /**
     * Bind a [WorkbenchUpdateSource] and start reactively collecting its [StateFlow].
     * Remote changes are merged into local state as soon as they arrive.
     */
    fun bind(
        scope: CoroutineScope,
        updateSource: WorkbenchUpdateSource,
    ) {
        collectJob?.cancel()
        // Immediately merge the current value
        mergeRemoteState(updateSource.stateFlow.value)
        collectJob =
            scope.launch {
                updateSource.stateFlow.collect { remote ->
                    mergeRemoteState(remote)
                }
            }
    }

    fun dispose() {
        collectJob?.cancel()
        collectJob = null
    }

    fun initialize() {
        requestListing("")
    }

    fun toggleMode() {
        val nextMode = if (state.mode == WorkbenchMode.TERMINAL) WorkbenchMode.EDITOR else WorkbenchMode.TERMINAL
        _state.value = state.copy(mode = nextMode)
        if (nextMode == WorkbenchMode.EDITOR) {
            requestListing(state.browserPath)
        }
    }

    fun toggleTerminalVisibility() {
        _state.value = state.copy(terminalVisible = !state.terminalVisible)
    }

    fun requestListing(path: String) {
        val normalizedPath = path.trim('/').trim()
        _state.value = state.copy(browserPath = normalizedPath)
        workspaceGateway.list(normalizedPath)
    }

    fun requestDocument(path: String) {
        workspaceGateway.read(path)
    }

    fun saveDocument() {
        val path = state.openDocument?.path ?: return
        workspaceGateway.write(path, state.editor.text)
        _state.value = state.copy(editor = state.editor.copy(dirty = false))
        refreshIde()
    }

    fun refreshWorkspace() {
        requestListing(state.browserPath)
        state.openDocument?.path?.let(::requestDocument)
    }

    fun navigateUp() {
        requestListing(state.browserPath.substringBeforeLast('/', ""))
    }

    fun rebootComputer() {
        controlGateway.reboot()
    }

    fun pullFromTarget() {
        if (!state.actions.canPull) return
        controlGateway.pullFromTarget()
    }

    fun pushToTarget() {
        if (!state.actions.canPush) return
        controlGateway.pushToTarget()
    }

    fun runTargetProgram() {
        if (!state.actions.canRun) return
        controlGateway.runTargetProgram()
    }

    fun attachTargetTerminal() {
        if (!state.actions.canAttachTerminal) return
        controlGateway.attachTargetTerminal()
    }

    fun updateHover(
        line: Int,
        column: Int,
    ) {
        val document = state.openDocument ?: return
        val hoverInfo = ideFacade.hover(document.path, state.editor.text, line, column)
        _state.value = state.copy(editor = state.editor.copy(hoverInfo = hoverInfo))
    }

    fun clearHover() {
        _state.value = state.copy(editor = state.editor.copy(hoverInfo = null))
    }

    fun openCompletion() {
        val document = state.openDocument ?: return
        val items =
            ideFacade.complete(
                document.path,
                state.editor.text,
                state.editor.cursorLine,
                state.editor.cursorColumn,
            )
        _state.value = state.copy(editor = state.editor.copy(completionItems = items, selectedCompletion = 0))
    }

    fun openImportPicker() {
        val document = state.openDocument ?: return
        val items = ideFacade.availableImports(document.path, state.editor.text)
        _state.value =
            state.copy(
                editor =
                    state.editor.copy(
                        importPickerVisible = items.isNotEmpty(),
                        importPickerItems = items,
                        selectedImportPickerIndex = 0,
                    ),
            )
    }

    fun closeImportPicker() {
        _state.value =
            state.copy(
                editor =
                    state.editor.copy(
                        importPickerVisible = false,
                        importPickerItems = emptyList(),
                        selectedImportPickerIndex = 0,
                    ),
            )
    }

    fun closeCompletion() {
        _state.value = state.copy(editor = state.editor.copy(completionItems = emptyList(), selectedCompletion = 0))
    }

    fun applyCompletion(index: Int = state.editor.selectedCompletion) {
        val item = state.editor.completionItems.getOrNull(index) ?: return
        _state.value = state.copy(editor = state.editor.applyCompletion(item))
        refreshIde()
    }

    fun applyImportPickerSelection(
        index: Int = state.editor.selectedImportPickerIndex,
        visibleEditorLines: Int,
    ) {
        val item = state.editor.importPickerItems.getOrNull(index) ?: return
        val importText = "import ${item.label};\n"
        _state.value = state.copy(editor = state.editor.insertText(importText, visibleEditorLines))
        refreshIde()
        closeImportPicker()
    }

    fun moveCursorTo(
        line: Int,
        column: Int,
        visibleEditorLines: Int,
    ) {
        _state.value = state.copy(editor = state.editor.withCursor(line, column, visibleEditorLines))
    }

    fun scrollEditor(deltaLines: Int) {
        _state.value = state.copy(editor = state.editor.scrollBy(deltaLines))
    }

    fun keyPressed(
        key: Int,
        modifiers: Int,
        visibleEditorLines: Int,
    ): Boolean {
        if (key == KeyCodes.KEY_F4) {
            toggleTerminalVisibility()
            return true
        }
        if (state.mode != WorkbenchMode.EDITOR) {
            return false
        }

        if ((modifiers and KeyCodes.MOD_CONTROL) != 0) {
            when (key) {
                KeyCodes.KEY_A -> {
                    openImportPicker()
                    return true
                }

                KeyCodes.KEY_S -> {
                    saveDocument()
                    return true
                }

                KeyCodes.KEY_SPACE -> {
                    openCompletion()
                    return true
                }
            }
        }

        if (state.editor.importPickerVisible) {
            when (key) {
                KeyCodes.KEY_UP -> {
                    selectImportPicker(state.editor.selectedImportPickerIndex - 1)
                    return true
                }

                KeyCodes.KEY_DOWN -> {
                    selectImportPicker(state.editor.selectedImportPickerIndex + 1)
                    return true
                }

                KeyCodes.KEY_ENTER,
                KeyCodes.KEY_KP_ENTER,
                -> {
                    applyImportPickerSelection(visibleEditorLines = visibleEditorLines)
                    return true
                }

                KeyCodes.KEY_ESCAPE -> {
                    closeImportPicker()
                    return true
                }
            }
        }

        if (state.editor.completionItems.isNotEmpty()) {
            when (key) {
                KeyCodes.KEY_UP -> {
                    selectCompletion(state.editor.selectedCompletion - 1)
                    return true
                }

                KeyCodes.KEY_DOWN -> {
                    selectCompletion(state.editor.selectedCompletion + 1)
                    return true
                }

                KeyCodes.KEY_ENTER,
                KeyCodes.KEY_KP_ENTER,
                -> {
                    applyCompletion()
                    return true
                }

                KeyCodes.KEY_ESCAPE -> {
                    closeCompletion()
                    return true
                }
            }
        }

        val nextEditor =
            when (key) {
                KeyCodes.KEY_LEFT -> state.editor.moveCursorHorizontal(-1, visibleEditorLines)

                KeyCodes.KEY_RIGHT -> state.editor.moveCursorHorizontal(1, visibleEditorLines)

                KeyCodes.KEY_UP -> state.editor.moveCursorVertical(-1, visibleEditorLines)

                KeyCodes.KEY_DOWN -> state.editor.moveCursorVertical(1, visibleEditorLines)

                KeyCodes.KEY_BACKSPACE -> state.editor.deleteBackward()

                KeyCodes.KEY_DELETE -> state.editor.deleteForward()

                KeyCodes.KEY_ENTER,
                KeyCodes.KEY_KP_ENTER,
                -> state.editor.insertText("\n", visibleEditorLines)

                KeyCodes.KEY_TAB -> state.editor.insertText("    ", visibleEditorLines)

                KeyCodes.KEY_PAGE_UP -> state.editor.scrollBy(-visibleEditorLines)

                KeyCodes.KEY_PAGE_DOWN -> state.editor.scrollBy(visibleEditorLines)

                KeyCodes.KEY_F12 -> navigateToDefinition(visibleEditorLines)

                else -> return false
            }

        _state.value = state.copy(editor = nextEditor)
        refreshIde()
        return true
    }

    fun charTyped(
        ch: Char,
        visibleEditorLines: Int,
    ): Boolean {
        if (state.mode != WorkbenchMode.EDITOR) {
            return false
        }
        if (state.editor.importPickerVisible) {
            return true
        }
        if (!Character.isISOControl(ch)) {
            _state.value = state.copy(editor = state.editor.insertText(ch.toString(), visibleEditorLines))
            refreshIde()
            if (shouldOpenCompletionAfterCharTyped(ch)) {
                openCompletionFromCurrentSnapshot()
            }
        }
        return true
    }

    private fun shouldOpenCompletionAfterCharTyped(ch: Char): Boolean {
        if (ch == '.') return true
        if (!(ch == '_' || ch.isLetterOrDigit())) return false
        return SourceTextSupport.shouldAutoTriggerIdentifierCompletion(
            state.editor.text,
            SourceTextSupport.offsetAt(state.editor.text, state.editor.cursorLine, state.editor.cursorColumn),
        )
    }

    private fun openCompletionFromCurrentSnapshot() {
        val document = state.openDocument ?: return
        val items =
            ideFacade.completeFromLastAnalysis(
                document.path,
                state.editor.text,
                state.editor.cursorLine,
                state.editor.cursorColumn,
            )
        if (items.isNotEmpty()) {
            _state.value =
                state.copy(
                    editor = state.editor.copy(completionItems = items, selectedCompletion = 0),
                )
        }
    }

    private fun mergeRemoteState(remoteState: WorkbenchRemoteState) {
        val documentChanged = remoteState.document != state.openDocument
        var nextState = state

        if (remoteState.entries != state.entries) {
            nextState = nextState.copy(entries = remoteState.entries)
        }

        if (remoteState.document != state.openDocument) {
            nextState =
                nextState.copy(
                    openDocument = remoteState.document,
                    editor =
                        remoteState.document
                            ?.let {
                                EditorState(text = it.text)
                            } ?: EditorState(),
                )
        }

        val actionState =
            WorkbenchActionState(
                canPull = remoteState.target.connected,
                canPush = remoteState.target.connected,
                canRun = remoteState.target.connected,
                canAttachTerminal = remoteState.target.connected,
            )

        _state.value =
            nextState.copy(
                target = remoteState.target,
                sync = remoteState.sync,
                actions = actionState,
            )
        if (documentChanged && remoteState.document != null) {
            refreshIde()
        }
    }

    private fun refreshIde() {
        val document = state.openDocument ?: return
        val snapshot = ideFacade.analyze(document.path, state.editor.text)
        _state.value =
            state.copy(
                editor =
                    state.editor.copy(
                        ideSnapshot = snapshot,
                        hoverInfo = null,
                        completionItems = emptyList(),
                        selectedCompletion = 0,
                    ),
            )
    }

    private fun navigateToDefinition(visibleEditorLines: Int): EditorState {
        val document = state.openDocument ?: return state.editor
        val target =
            ideFacade
                .definition(
                    document.path,
                    state.editor.text,
                    state.editor.cursorLine,
                    state.editor.cursorColumn,
                )?.takeIf { it.path == document.path } ?: return state.editor
        return state.editor.withCursor(target.range.start.line, target.range.start.column, visibleEditorLines)
    }

    private fun selectCompletion(index: Int) {
        val items = state.editor.completionItems
        if (items.isEmpty()) return
        val normalizedIndex = ((index % items.size) + items.size) % items.size
        _state.value = state.copy(editor = state.editor.copy(selectedCompletion = normalizedIndex))
    }

    private fun selectImportPicker(index: Int) {
        val items = state.editor.importPickerItems
        if (items.isEmpty()) return
        val normalizedIndex = ((index % items.size) + items.size) % items.size
        _state.value = state.copy(editor = state.editor.copy(selectedImportPickerIndex = normalizedIndex))
    }
}

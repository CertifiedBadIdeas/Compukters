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
package ck.mod.application.workbench

import org.lwjgl.glfw.GLFW

class WorkbenchStore(
    private val workspaceGateway: WorkspaceGateway,
    private val controlGateway: ComputerControlGateway,
    private val ideFacade: WorkbenchIdeFacade,
) {
    var state: WorkbenchState = WorkbenchState()
        private set

    private var updateSubscription: AutoCloseable? = null

    fun bind(updateSource: WorkbenchUpdateSource) {
        updateSubscription?.close()
        updateSubscription = updateSource.subscribe(::mergeRemoteState)
    }

    fun dispose() {
        updateSubscription?.close()
        updateSubscription = null
    }

    fun initialize() {
        requestListing("")
    }

    fun toggleMode() {
        val nextMode = if (state.mode == WorkbenchMode.TERMINAL) WorkbenchMode.EDITOR else WorkbenchMode.TERMINAL
        state = state.copy(mode = nextMode)
        if (nextMode == WorkbenchMode.EDITOR) {
            requestListing(state.browserPath)
        }
    }

    fun requestListing(path: String) {
        val normalizedPath = path.trim('/').trim()
        state = state.copy(browserPath = normalizedPath)
        workspaceGateway.list(normalizedPath)
    }

    fun requestDocument(path: String) {
        workspaceGateway.read(path)
    }

    fun saveDocument() {
        val path = state.openDocument?.path ?: return
        workspaceGateway.write(path, state.editor.text)
        state = state.copy(editor = state.editor.copy(dirty = false))
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

    fun updateHover(
        line: Int,
        column: Int,
    ) {
        val document = state.openDocument ?: return
        val hoverInfo = ideFacade.hover(document.path, state.editor.text, line, column)
        state = state.copy(editor = state.editor.copy(hoverInfo = hoverInfo))
    }

    fun clearHover() {
        state = state.copy(editor = state.editor.copy(hoverInfo = null))
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
        state = state.copy(editor = state.editor.copy(completionItems = items, selectedCompletion = 0))
    }

    fun closeCompletion() {
        state = state.copy(editor = state.editor.copy(completionItems = emptyList(), selectedCompletion = 0))
    }

    fun applyCompletion(index: Int = state.editor.selectedCompletion) {
        val item = state.editor.completionItems.getOrNull(index) ?: return
        state = state.copy(editor = state.editor.applyCompletion(item))
        refreshIde()
    }

    fun moveCursorTo(
        line: Int,
        column: Int,
        visibleEditorLines: Int,
    ) {
        state = state.copy(editor = state.editor.withCursor(line, column, visibleEditorLines))
    }

    fun scrollEditor(deltaLines: Int) {
        state = state.copy(editor = state.editor.scrollBy(deltaLines))
    }

    fun keyPressed(
        key: Int,
        modifiers: Int,
        visibleEditorLines: Int,
    ): Boolean {
        if (key == GLFW.GLFW_KEY_F4) {
            toggleMode()
            return true
        }
        if (state.mode != WorkbenchMode.EDITOR) {
            return false
        }

        if ((modifiers and GLFW.GLFW_MOD_CONTROL) != 0) {
            when (key) {
                GLFW.GLFW_KEY_S -> {
                    saveDocument()
                    return true
                }

                GLFW.GLFW_KEY_SPACE -> {
                    openCompletion()
                    return true
                }
            }
        }

        if (state.editor.completionItems.isNotEmpty()) {
            when (key) {
                GLFW.GLFW_KEY_UP -> {
                    selectCompletion(state.editor.selectedCompletion - 1)
                    return true
                }

                GLFW.GLFW_KEY_DOWN -> {
                    selectCompletion(state.editor.selectedCompletion + 1)
                    return true
                }

                GLFW.GLFW_KEY_ENTER,
                GLFW.GLFW_KEY_KP_ENTER,
                -> {
                    applyCompletion()
                    return true
                }

                GLFW.GLFW_KEY_ESCAPE -> {
                    closeCompletion()
                    return true
                }
            }
        }

        val nextEditor =
            when (key) {
                GLFW.GLFW_KEY_LEFT -> state.editor.moveCursorHorizontal(-1, visibleEditorLines)
                GLFW.GLFW_KEY_RIGHT -> state.editor.moveCursorHorizontal(1, visibleEditorLines)
                GLFW.GLFW_KEY_UP -> state.editor.moveCursorVertical(-1, visibleEditorLines)
                GLFW.GLFW_KEY_DOWN -> state.editor.moveCursorVertical(1, visibleEditorLines)
                GLFW.GLFW_KEY_BACKSPACE -> state.editor.deleteBackward()
                GLFW.GLFW_KEY_DELETE -> state.editor.deleteForward()
                GLFW.GLFW_KEY_ENTER,
                GLFW.GLFW_KEY_KP_ENTER,
                -> state.editor.insertText("\n", visibleEditorLines)

                GLFW.GLFW_KEY_TAB -> state.editor.insertText("    ", visibleEditorLines)
                GLFW.GLFW_KEY_PAGE_UP -> state.editor.scrollBy(-visibleEditorLines)
                GLFW.GLFW_KEY_PAGE_DOWN -> state.editor.scrollBy(visibleEditorLines)
                GLFW.GLFW_KEY_F12 -> navigateToDefinition(visibleEditorLines)
                else -> return true
            }

        state = state.copy(editor = nextEditor)
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
        if (!Character.isISOControl(ch)) {
            state = state.copy(editor = state.editor.insertText(ch.toString(), visibleEditorLines))
            refreshIde()
        }
        return true
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

        state = nextState
        if (documentChanged && remoteState.document != null) {
            refreshIde()
        }
    }

    private fun refreshIde() {
        val document = state.openDocument ?: return
        val snapshot = ideFacade.analyze(document.path, state.editor.text)
        state =
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
            ideFacade.definition(
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
        state = state.copy(editor = state.editor.copy(selectedCompletion = normalizedIndex))
    }
}

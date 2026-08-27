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
 */

package ru.lazyhat.compukters.impl.ide

import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW
import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeConflictAction
import ru.lazyhat.compukters.ide.client.state.IdeDialogState
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeEditorView
import ru.lazyhat.compukters.ide.client.state.IdeMoveDirection
import ru.lazyhat.compukters.ide.client.state.IdeProjectSummary
import ru.lazyhat.compukters.ide.project.tree.ProjectFileKind
import ru.lazyhat.compukters.ide.project.tree.ProjectTreeEntry

fun interface IdeCommandSink {
    fun dispatch(command: IdeCommand)
}

fun interface IdeClipboard {
    fun text(): String
}

fun interface IdeUiActionSink {
    fun activate(action: IdeHitAction): Boolean
}

enum class IdeFocusArea { Editor, Tree, Panel, None }

data class IdeFocusState(
    val area: IdeFocusArea,
    val completionVisible: Boolean = false,
    val dialog: IdeDialogState? = null,
) {
    companion object {
        val Editor = IdeFocusState(IdeFocusArea.Editor)
        val Tree = IdeFocusState(IdeFocusArea.Tree)
        val Panel = IdeFocusState(IdeFocusArea.Panel)
        val None = IdeFocusState(IdeFocusArea.None)
    }
}

data class IdePointerContext(
    val geometry: IdeRenderGeometry,
    val editor: IdeEditorView.Text? = null,
    val projects: List<IdeProjectSummary> = emptyList(),
    val tree: List<ProjectTreeEntry> = emptyList(),
    val treeFirstRow: Int = 0,
    val hitTargets: List<IdeHitTarget> = emptyList(),
    val dialog: IdeDialogState? = null,
)

class IdeInputAdapter(
    private val sink: IdeCommandSink,
    private val clipboard: IdeClipboard,
    private val limits: IdeClientLimits,
    private val uiActions: IdeUiActionSink = IdeUiActionSink { false },
) {
    var treeFirstRow: Int = 0
        private set

    fun clampTree(
        entryCount: Int,
        treeHeight: Int,
    ): Int {
        val visibleRows = ((treeHeight - TREE_ROWS_TOP).coerceAtLeast(0) / UI_LINE_HEIGHT).coerceAtLeast(1)
        treeFirstRow = treeFirstRow.coerceIn(0, (entryCount - visibleRows).coerceAtLeast(0))
        return treeFirstRow
    }

    fun keyPressed(
        event: KeyEvent,
        focus: IdeFocusState,
    ): Boolean {
        focus.dialog?.let { return dialogKey(event, it) }
        if (focus.completionVisible) completionKey(event)?.let { return dispatch(it) }
        if (focus.area != IdeFocusArea.Editor) return false
        if (event.isPaste) return dispatchType(boundedClipboard(clipboard.text()))
        val control = event.modifiers() and GLFW.GLFW_MOD_CONTROL != 0
        val shift = event.modifiers() and GLFW.GLFW_MOD_SHIFT != 0
        val command =
            if (control) {
                when (event.key()) {
                    GLFW.GLFW_KEY_S -> IdeCommand.Save
                    GLFW.GLFW_KEY_B -> IdeCommand.Build
                    GLFW.GLFW_KEY_SPACE -> IdeCommand.ManualCompletion
                    GLFW.GLFW_KEY_Z -> IdeCommand.Edit(IdeEditorInput.Undo)
                    GLFW.GLFW_KEY_Y -> IdeCommand.Edit(IdeEditorInput.Redo)
                    GLFW.GLFW_KEY_A -> IdeCommand.Edit(IdeEditorInput.SelectAll)
                    else -> null
                }
            } else {
                editorKey(event.key(), shift)
            }
        return command?.let(::dispatch) ?: false
    }

    fun charTyped(
        event: CharacterEvent,
        focus: IdeFocusState,
    ): Boolean {
        if (focus.dialog != null || focus.area != IdeFocusArea.Editor) return false
        return dispatchType(event.codepointAsString())
    }

    fun pointerActivity() {
        sink.dispatch(IdeCommand.PointerActivity)
    }

    fun pointerClicked(
        x: Double,
        y: Double,
        modifiers: Int,
        context: IdePointerContext,
    ): Boolean {
        val geometry = context.geometry
        context.hitTargets
            .asReversed()
            .firstOrNull { it.enabled && it.bounds.contains(x, y) }
            ?.let { target ->
                val handled = activate(target.action, context.dialog)
                if (handled) pointerActivity()
                return handled
            }
        if (geometry.editor.contains(x, y)) {
            val editor = context.editor
            if (editor != null) {
                val offset = editorOffset(x, y, editor, geometry) ?: return true
                sink.dispatch(IdeCommand.Edit(IdeEditorInput.SetCaret(offset, modifiers and GLFW.GLFW_MOD_SHIFT != 0)))
            } else if (context.projects.isNotEmpty()) {
                val row = ((y - geometry.editor.top - START_ROWS_TOP).toInt() / UI_LINE_HEIGHT)
                context.projects.getOrNull(row)?.let { sink.dispatch(IdeCommand.OpenProject(it.directoryName)) }
            }
            pointerActivity()
            return true
        }
        val tree = geometry.tree
        if (tree != null && tree.contains(x, y)) {
            val row = context.treeFirstRow + ((y - tree.top - TREE_ROWS_TOP).toInt() / UI_LINE_HEIGHT)
            val entry = context.tree.getOrNull(row)
            if (entry != null && entry.kind !is ProjectFileKind.Directory) sink.dispatch(IdeCommand.OpenFile(entry.path))
            pointerActivity()
            return true
        }
        return false
    }

    private fun activate(
        action: IdeHitAction,
        dialog: IdeDialogState?,
    ): Boolean =
        when (action) {
            IdeHitAction.Resolve -> {
                dispatch(IdeCommand.Resolve)
            }

            IdeHitAction.Build -> {
                dispatch(IdeCommand.Build)
            }

            IdeHitAction.Cancel -> {
                dispatch(IdeCommand.CancelBuild)
            }

            IdeHitAction.Delete -> {
                uiActions.activate(action)
            }

            IdeHitAction.CreateProject,
            IdeHitAction.OpenProject,
            IdeHitAction.CreateText,
            IdeHitAction.CreateDirectory,
            IdeHitAction.Rename,
            -> {
                uiActions.activate(action)
            }

            IdeHitAction.Confirm -> {
                when (dialog) {
                    is IdeDialogState.Confirmation -> dispatch(IdeCommand.ConfirmDialog(dialog.actionId))
                    is IdeDialogState.LockUpdate -> dispatch(IdeCommand.ConfirmLockUpdate)
                    is IdeDialogState.FileConflict -> dispatch(IdeCommand.ResolveConflict(dialog.confirmAction()))
                    is IdeDialogState.TargetOverwrite -> dispatch(IdeCommand.ConfirmTargetDeployment)
                    else -> uiActions.activate(action)
                }
            }

            IdeHitAction.Dismiss -> {
                when (dialog) {
                    null -> uiActions.activate(action)
                    is IdeDialogState.TargetOverwrite -> dispatch(IdeCommand.CancelTargetDeployment)
                    else -> dispatch(IdeCommand.CancelDialog)
                }
            }

            IdeHitAction.Verify -> dispatch(IdeCommand.Verify)
            IdeHitAction.Deploy -> dispatch(IdeCommand.Deploy)
            IdeHitAction.Run -> dispatch(IdeCommand.Run)
        }

    fun scroll(
        x: Double,
        y: Double,
        horizontal: Double,
        vertical: Double,
        context: IdePointerContext,
    ): Boolean {
        if (context.geometry.editor.contains(x, y) && context.editor != null) {
            val lines = (-vertical * SCROLL_ROWS).toInt()
            val columns = (horizontal * SCROLL_COLUMNS).toInt()
            if (lines != 0 || columns != 0) sink.dispatch(IdeCommand.ScrollEditor(lines, columns))
            pointerActivity()
            return true
        }
        val tree = context.geometry.tree
        if (tree != null && tree.contains(x, y)) {
            clampTree(context.tree.size, tree.height)
            treeFirstRow = (treeFirstRow + (-vertical * SCROLL_ROWS).toInt()).coerceIn(0, maximumTreeRow(context.tree.size, tree.height))
            pointerActivity()
            return true
        }
        return false
    }

    private fun maximumTreeRow(
        entryCount: Int,
        treeHeight: Int,
    ): Int {
        val visibleRows = ((treeHeight - TREE_ROWS_TOP).coerceAtLeast(0) / UI_LINE_HEIGHT).coerceAtLeast(1)
        return (entryCount - visibleRows).coerceAtLeast(0)
    }

    private fun dialogKey(
        event: KeyEvent,
        dialog: IdeDialogState,
    ): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            val command =
                when (dialog) {
                    is IdeDialogState.FileConflict -> IdeCommand.ResolveConflict(IdeConflictAction.Cancel)
                    is IdeDialogState.TargetOverwrite -> IdeCommand.CancelTargetDeployment
                    else -> IdeCommand.CancelDialog
                }
            return dispatch(command)
        }
        if (event.key() != GLFW.GLFW_KEY_ENTER) return true
        return when (dialog) {
            is IdeDialogState.Confirmation -> dispatch(IdeCommand.ConfirmDialog(dialog.actionId))
            is IdeDialogState.LockUpdate -> dispatch(IdeCommand.ConfirmLockUpdate)
            is IdeDialogState.FileConflict -> dispatch(IdeCommand.ResolveConflict(dialog.confirmAction()))
            is IdeDialogState.TargetOverwrite -> dispatch(IdeCommand.ConfirmTargetDeployment)
        }
    }

    private fun IdeDialogState.FileConflict.confirmAction(): IdeConflictAction =
        if (closing) IdeConflictAction.DiscardAndClose else IdeConflictAction.ReloadFromDisk

    private fun completionKey(event: KeyEvent): IdeCommand? =
        when (event.key()) {
            GLFW.GLFW_KEY_ESCAPE -> IdeCommand.DismissCompletion
            GLFW.GLFW_KEY_ENTER -> IdeCommand.Edit(IdeEditorInput.Enter)
            GLFW.GLFW_KEY_TAB -> IdeCommand.Edit(IdeEditorInput.Tab)
            GLFW.GLFW_KEY_UP -> IdeCommand.Edit(IdeEditorInput.Move(IdeMoveDirection.Up, false))
            GLFW.GLFW_KEY_DOWN -> IdeCommand.Edit(IdeEditorInput.Move(IdeMoveDirection.Down, false))
            GLFW.GLFW_KEY_PAGE_UP -> repeatMove(IdeMoveDirection.Up, COMPLETION_PAGE_ROWS)
            GLFW.GLFW_KEY_PAGE_DOWN -> repeatMove(IdeMoveDirection.Down, COMPLETION_PAGE_ROWS)
            else -> null
        }

    private fun editorKey(
        key: Int,
        shift: Boolean,
    ): IdeCommand? =
        when (key) {
            GLFW.GLFW_KEY_LEFT -> move(IdeMoveDirection.Left, shift)
            GLFW.GLFW_KEY_RIGHT -> move(IdeMoveDirection.Right, shift)
            GLFW.GLFW_KEY_UP -> move(IdeMoveDirection.Up, shift)
            GLFW.GLFW_KEY_DOWN -> move(IdeMoveDirection.Down, shift)
            GLFW.GLFW_KEY_HOME -> move(IdeMoveDirection.Home, shift)
            GLFW.GLFW_KEY_END -> move(IdeMoveDirection.End, shift)
            GLFW.GLFW_KEY_BACKSPACE -> IdeCommand.Edit(IdeEditorInput.Backspace)
            GLFW.GLFW_KEY_DELETE -> IdeCommand.Edit(IdeEditorInput.Delete)
            GLFW.GLFW_KEY_ENTER -> IdeCommand.Edit(IdeEditorInput.Enter)
            GLFW.GLFW_KEY_TAB -> IdeCommand.Edit(IdeEditorInput.Tab)
            GLFW.GLFW_KEY_ESCAPE -> IdeCommand.CloseRequested
            else -> null
        }

    private fun move(
        direction: IdeMoveDirection,
        selection: Boolean,
    ) = IdeCommand.Edit(IdeEditorInput.Move(direction, selection))

    private fun repeatMove(
        direction: IdeMoveDirection,
        count: Int,
    ): IdeCommand {
        repeat(count - 1) { sink.dispatch(move(direction, false)) }
        return move(direction, false)
    }

    private fun dispatchType(text: String): Boolean {
        if (text.isEmpty()) return true
        return dispatch(IdeCommand.Edit(IdeEditorInput.Type(text)))
    }

    private fun dispatch(command: IdeCommand): Boolean {
        sink.dispatch(command)
        return true
    }

    private fun boundedClipboard(value: String): String {
        val result = StringBuilder(minOf(value.length, limits.clipboardCodeUnits))
        var offset = 0
        var bytes = 0
        while (offset < value.length) {
            val first = value[offset]
            val validPair =
                Character.isHighSurrogate(first) && offset + 1 < value.length && Character.isLowSurrogate(value[offset + 1])
            val codePoint =
                when {
                    validPair -> Character.toCodePoint(first, value[offset + 1])
                    Character.isSurrogate(first) -> 0xfffd
                    else -> first.code
                }
            val inputUnits = if (validPair) 2 else 1
            val outputUnits = Character.charCount(codePoint)
            val outputBytes = utf8Bytes(codePoint)
            if (result.length + outputUnits > limits.clipboardCodeUnits || bytes + outputBytes > limits.clipboardUtf8Bytes) break
            result.appendCodePoint(codePoint)
            bytes += outputBytes
            offset += inputUnits
        }
        return result.toString()
    }

    private fun utf8Bytes(codePoint: Int): Int =
        when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }

    private fun editorOffset(
        x: Double,
        y: Double,
        editor: IdeEditorView.Text,
        geometry: IdeRenderGeometry,
    ): Int? {
        val font = geometry.font
        val gutterDigits =
            editor.totalLines
                .toString()
                .length
                .coerceAtLeast(2)
        val codeLeft = geometry.editor.left + (gutterDigits + 2) * font.cellWidth
        val row = ((y - geometry.editor.top).toInt() / font.cellHeight)
        val line = editor.visibleLines.getOrNull(row) ?: return null
        val lineStart = editor.visibleLineStartsUtf16[row]
        val requestedColumn = editor.firstVisibleColumn + ((x - codeLeft).toInt() / font.cellWidth).coerceAtLeast(0)
        var offset = 0
        var column = 0
        while (offset < line.length && column < requestedColumn) {
            val codePoint = line.codePointAt(offset)
            val next = if (codePoint == '\t'.code) column + TAB_WIDTH - column % TAB_WIDTH else column + 1
            if (next > requestedColumn) break
            column = next
            offset += Character.charCount(codePoint)
        }
        return lineStart + offset
    }

    private fun IdeRect.contains(
        x: Double,
        y: Double,
    ): Boolean = x >= left && x < right && y >= top && y < bottom

    private companion object {
        const val COMPLETION_PAGE_ROWS = 8
        const val UI_LINE_HEIGHT = 12
        const val START_ROWS_TOP = 6
        const val TREE_ROWS_TOP = 4
        const val SCROLL_ROWS = 3
        const val SCROLL_COLUMNS = 4
        const val TAB_WIDTH = 4
    }
}

class IdeSplitterInteraction(
    initial: IdeLayoutSettings,
    private val persist: (IdeLayoutSettings) -> Unit,
) {
    var layout: IdeLayoutSettings = initial
        private set
    private var capture: Capture? = null
    val captured: Boolean get() = capture != null

    fun press(
        x: Int,
        y: Int,
        geometry: IdeRenderGeometry,
    ): Boolean {
        capture =
            when {
                geometry.treeSplitter?.contains(x, y) == true -> Capture.Tree
                geometry.diagnosticsSplitter?.contains(x, y) == true -> Capture.Diagnostics
                else -> null
            }
        return captured
    }

    fun drag(
        x: Int,
        y: Int,
        geometry: IdeRenderGeometry,
    ): Boolean {
        layout =
            when (capture) {
                Capture.Tree -> layout.copy(treeWidth = geometry.treeWidthAt(x))
                Capture.Diagnostics -> layout.copy(diagnosticsHeight = geometry.diagnosticsHeightAt(y))
                null -> return false
            }
        return true
    }

    fun release(): Boolean {
        if (capture == null) return false
        capture = null
        persist(layout)
        return true
    }

    fun focusLost() {
        release()
    }

    private fun IdeRect.contains(
        x: Int,
        y: Int,
    ): Boolean = x in left until right && y in top until bottom

    private enum class Capture { Tree, Diagnostics }
}

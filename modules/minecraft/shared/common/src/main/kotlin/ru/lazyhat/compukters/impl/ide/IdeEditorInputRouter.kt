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

import ru.lazyhat.compukters.ide.client.IdeClientLimits
import ru.lazyhat.compukters.ide.client.analysis.IDE_COMPLETION_VISIBLE_ROWS
import ru.lazyhat.compukters.ide.client.state.IdeCommand
import ru.lazyhat.compukters.ide.client.state.IdeEditorInput
import ru.lazyhat.compukters.ide.client.state.IdeHorizontalDirection
import ru.lazyhat.compukters.ide.client.state.IdeMoveDirection
import ru.lazyhat.compukters.ide.client.state.IdeVerticalDirection

/** Editor-local ordering: search, semantic chooser, parameter info, completion, ordinary editing. */
internal class IdeEditorInputRouter(
    private val sink: IdeCommandSink,
    private val clipboard: IdeClipboard,
    private val limits: IdeClientLimits,
    private val uiActions: IdeUiActionSink,
    private val clipboardWriter: IdeClipboardWriter,
    private val selectionSource: IdeSelectionSource,
) {
    fun keyPressed(
        event: IdeKeyInput,
        focus: IdeFocusState,
    ): Boolean {
        if (focus.area == IdeFocusArea.Editor) {
            if (event.modifiers and IdeModifier.CONTROL != 0 && event.key == IdeKeyCode.F) return dispatch(IdeCommand.OpenFind)
            if (focus.findVisible && event.key == IdeKeyCode.ESCAPE) return dispatch(IdeCommand.CloseFind)
            if (focus.findFocused) return findKey(event, focus)
        }
        if (focus.importFixesVisible) {
            val command =
                when (event.key) {
                    IdeKeyCode.ESCAPE -> IdeCommand.DismissImportFixes
                    IdeKeyCode.ENTER -> if (event.modifiers == 0) IdeCommand.AcceptImportFix() else null
                    IdeKeyCode.UP -> if (event.modifiers == 0) IdeCommand.MoveImportFix(-1) else null
                    IdeKeyCode.DOWN -> if (event.modifiers == 0) IdeCommand.MoveImportFix(1) else null
                    IdeKeyCode.PAGE_UP -> IdeCommand.MoveImportFix(-8)
                    IdeKeyCode.PAGE_DOWN -> IdeCommand.MoveImportFix(8)
                    else -> null
                }
            if (command != null) return dispatch(command)
            dispatch(IdeCommand.DismissImportFixes)
        }
        if (focus.declarationChooserVisible) chooserKey(event)?.let { return dispatch(it) }
        if (focus.parameterInfoVisible && event.modifiers == IdeModifier.CONTROL &&
            event.key in listOf(IdeKeyCode.UP, IdeKeyCode.DOWN)
        ) {
            return dispatch(IdeCommand.SelectParameterInfo(if (event.key == IdeKeyCode.UP) -1 else 1))
        }
        if (focus.parameterInfoVisible && event.key == IdeKeyCode.ESCAPE) {
            return dispatch(IdeCommand.DismissParameterInfo)
        }
        if (focus.completionVisible) completionKey(event)?.let { return dispatch(it) }
        if (focus.area != IdeFocusArea.Editor) return false
        if (event.paste) return dispatchType(boundedClipboard(clipboard.text()))
        val control = event.modifiers and IdeModifier.CONTROL != 0
        val alt = event.modifiers and IdeModifier.ALT != 0
        val shift = event.modifiers and IdeModifier.SHIFT != 0
        if (shift && !control && !alt && event.key == IdeKeyCode.F6) return uiActions.activate(IdeHitAction.RenameSymbol)
        if (control && event.key == IdeKeyCode.C) return copySelection()
        if (control && event.key == IdeKeyCode.X) return cutSelection()
        val command =
            if (control) {
                when (event.key) {
                    IdeKeyCode.D -> if (!alt && !shift) IdeCommand.Edit(IdeEditorInput.Duplicate) else null
                    IdeKeyCode.SLASH -> if (!alt && !shift) IdeCommand.Edit(IdeEditorInput.ToggleLineComment) else null
                    IdeKeyCode.S -> IdeCommand.Save
                    IdeKeyCode.L -> if (alt && !shift) IdeCommand.Format else null
                    IdeKeyCode.B -> IdeCommand.GoToDeclaration()
                    IdeKeyCode.P -> IdeCommand.ShowParameterInfo
                    IdeKeyCode.F9 -> IdeCommand.Build
                    IdeKeyCode.SPACE -> IdeCommand.ManualCompletion
                    IdeKeyCode.Z -> IdeCommand.Edit(if (shift) IdeEditorInput.Redo else IdeEditorInput.Undo)
                    IdeKeyCode.Y -> IdeCommand.Edit(IdeEditorInput.Redo)
                    IdeKeyCode.A -> IdeCommand.Edit(IdeEditorInput.SelectAll)
                    IdeKeyCode.LEFT -> wordMove(IdeHorizontalDirection.Left, shift)
                    IdeKeyCode.RIGHT -> wordMove(IdeHorizontalDirection.Right, shift)
                    IdeKeyCode.BACKSPACE -> IdeCommand.Edit(IdeEditorInput.DeleteWordBackward)
                    IdeKeyCode.DELETE -> IdeCommand.Edit(IdeEditorInput.DeleteWordForward)
                    else -> null
                }
            } else if (alt) {
                when (event.key) {
                    IdeKeyCode.LEFT -> IdeCommand.NavigateBack
                    IdeKeyCode.RIGHT -> IdeCommand.NavigateForward
                    IdeKeyCode.ENTER -> if (!shift) IdeCommand.ShowImportFixes else null
                    IdeKeyCode.F7 -> IdeCommand.FindUsages
                    else -> null
                }
            } else {
                editingKey(event.key, shift, focus.editorPageRows)
            }
        return command?.let(::dispatch) ?: false
    }

    fun charTyped(
        event: IdeCharacterInput,
        focus: IdeFocusState,
    ): Boolean {
        if (focus.findFocused) return dispatch(IdeCommand.EditFind(IdeEditorInput.Type(event.text)))
        return dispatchType(event.text)
    }

    private fun findKey(
        event: IdeKeyInput,
        focus: IdeFocusState,
    ): Boolean {
        val control = event.modifiers and IdeModifier.CONTROL != 0
        val shift = event.modifiers and IdeModifier.SHIFT != 0
        if (event.paste) return dispatch(IdeCommand.EditFind(IdeEditorInput.Type(boundedClipboard(clipboard.text()))))
        if (event.key == IdeKeyCode.ENTER) return dispatch(IdeCommand.NavigateFind(shift))
        if (event.key == IdeKeyCode.TAB) return dispatch(IdeCommand.FocusFind(false))
        if (control && event.key == IdeKeyCode.C) {
            focus.findSelectedText?.let(clipboardWriter::setText)
            return true
        }
        val input =
            if (control) {
                when (event.key) {
                    IdeKeyCode.A -> IdeEditorInput.SelectAll
                    IdeKeyCode.Z -> if (shift) IdeEditorInput.Redo else IdeEditorInput.Undo
                    IdeKeyCode.Y -> IdeEditorInput.Redo
                    IdeKeyCode.LEFT -> IdeEditorInput.MoveWord(IdeHorizontalDirection.Left, shift)
                    IdeKeyCode.RIGHT -> IdeEditorInput.MoveWord(IdeHorizontalDirection.Right, shift)
                    IdeKeyCode.BACKSPACE -> IdeEditorInput.DeleteWordBackward
                    IdeKeyCode.DELETE -> IdeEditorInput.DeleteWordForward
                    else -> null
                }
            } else {
                (editingKey(event.key, shift, 1) as? IdeCommand.Edit)?.input
            }
        input?.let { dispatch(IdeCommand.EditFind(it)) }
        return true
    }

    private fun completionKey(event: IdeKeyInput): IdeCommand? =
        when (event.key) {
            IdeKeyCode.ESCAPE -> IdeCommand.DismissCompletion
            IdeKeyCode.ENTER -> IdeCommand.Edit(IdeEditorInput.Enter)
            IdeKeyCode.TAB -> IdeCommand.Edit(IdeEditorInput.Tab)
            IdeKeyCode.UP -> IdeCommand.Edit(IdeEditorInput.Move(IdeMoveDirection.Up, false))
            IdeKeyCode.DOWN -> IdeCommand.Edit(IdeEditorInput.Move(IdeMoveDirection.Down, false))
            IdeKeyCode.PAGE_UP -> repeatMove(IdeMoveDirection.Up, IDE_COMPLETION_VISIBLE_ROWS)
            IdeKeyCode.PAGE_DOWN -> repeatMove(IdeMoveDirection.Down, IDE_COMPLETION_VISIBLE_ROWS)
            else -> null
        }

    private fun chooserKey(event: IdeKeyInput): IdeCommand? =
        when (event.key) {
            IdeKeyCode.ESCAPE -> IdeCommand.DismissSemanticInteraction
            IdeKeyCode.ENTER -> IdeCommand.AcceptDeclarationChoice
            IdeKeyCode.UP -> IdeCommand.MoveDeclarationChoice(-1)
            IdeKeyCode.DOWN -> IdeCommand.MoveDeclarationChoice(1)
            else -> null
        }

    fun editingKey(
        key: Int,
        shift: Boolean,
        pageRows: Int,
    ): IdeCommand? =
        when (key) {
            IdeKeyCode.F2 -> {
                IdeCommand.NavigateDiagnostic(shift)
            }

            IdeKeyCode.LEFT -> {
                move(IdeMoveDirection.Left, shift)
            }

            IdeKeyCode.RIGHT -> {
                move(IdeMoveDirection.Right, shift)
            }

            IdeKeyCode.UP -> {
                move(IdeMoveDirection.Up, shift)
            }

            IdeKeyCode.DOWN -> {
                move(IdeMoveDirection.Down, shift)
            }

            IdeKeyCode.HOME -> {
                move(IdeMoveDirection.Home, shift)
            }

            IdeKeyCode.END -> {
                move(IdeMoveDirection.End, shift)
            }

            IdeKeyCode.BACKSPACE -> {
                IdeCommand.Edit(IdeEditorInput.Backspace)
            }

            IdeKeyCode.DELETE -> {
                IdeCommand.Edit(IdeEditorInput.Delete)
            }

            IdeKeyCode.ENTER -> {
                IdeCommand.Edit(IdeEditorInput.Enter)
            }

            IdeKeyCode.TAB -> {
                IdeCommand.Edit(if (shift) IdeEditorInput.Outdent else IdeEditorInput.Tab)
            }

            IdeKeyCode.PAGE_UP -> {
                IdeCommand.Edit(IdeEditorInput.Page(IdeVerticalDirection.Up, pageRows.coerceAtLeast(1), shift))
            }

            IdeKeyCode.PAGE_DOWN -> {
                IdeCommand.Edit(IdeEditorInput.Page(IdeVerticalDirection.Down, pageRows.coerceAtLeast(1), shift))
            }

            IdeKeyCode.ESCAPE -> {
                IdeCommand.CloseRequested
            }

            else -> {
                null
            }
        }

    private fun move(
        direction: IdeMoveDirection,
        selection: Boolean,
    ) = IdeCommand.Edit(IdeEditorInput.Move(direction, selection))

    private fun wordMove(
        direction: IdeHorizontalDirection,
        selection: Boolean,
    ) = IdeCommand.Edit(IdeEditorInput.MoveWord(direction, selection))

    private fun copySelection(): Boolean {
        selectionSource.selectedText()?.let { clipboardWriter.setText(boundedClipboard(it)) }
        return true
    }

    private fun cutSelection(): Boolean {
        val selected = selectionSource.selectedText() ?: return true
        clipboardWriter.setText(boundedClipboard(selected))
        return dispatch(IdeCommand.Edit(IdeEditorInput.Cut))
    }

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

    private fun boundedClipboard(value: String): String = IdeClipboardText.bounded(value, limits)

    private fun dispatch(command: IdeCommand): Boolean {
        sink.dispatch(command)
        return true
    }
}

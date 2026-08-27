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

package ru.lazyhat.compukters.ide.editor

class EditorDocument(
    initial: String,
    val limits: EditorLimits = EditorLimits(),
) {
    private val buffer = EditorBuffer(initial, limits)
    private val lines = EditorLineIndex(buffer, limits.tabWidth)
    private val history = EditorHistory(limits)
    private val listeners = linkedMapOf<Long, (EditorChange) -> Unit>()
    private var nextListenerId = 1L
    private var selection = EditorSelection(0, 0)
    private var preferredColumn: Int? = null
    private var closed = false

    var revision: Long = 0
        private set

    val length: Int
        get() = buffer.length

    val caretOffset: Int
        get() = selection.caretUtf16

    val selectionRange: EditorRange?
        get() = selection.range.takeUnless { it.length == 0 }

    val caretVisualColumn: Int
        get() = lines.visualColumn(caretOffset)

    val lineCount: Int
        get() = lines.lineCount

    val preferredLineSeparator: String
        get() = lines.preferredSeparator

    internal val undoEntryCount: Int
        get() = history.undoEntryCount

    fun materialize(): String = buffer.materialize()

    /** Copies one logical line without its line separator. */
    fun materializeLine(index: Int): String {
        val line = lines.line(index)
        return buffer.copyRange(line.startUtf16, line.contentEndUtf16).concatToString()
    }

    fun contentEquals(value: String): Boolean = buffer.contentEquals(value)

    fun charAt(offset: Int): Char = buffer.charAt(offset)

    fun copyRange(range: EditorRange): String = buffer.copyRange(range.startUtf16, range.endUtf16).concatToString()

    fun addChangeListener(listener: (EditorChange) -> Unit): EditorChangeSubscription {
        if (closed) return EditorChangeSubscription {}
        val id = nextListenerId++
        listeners[id] = listener
        return EditorChangeSubscription { listeners.remove(id) }
    }

    fun setCaret(
        offset: Int,
        extendSelection: Boolean = false,
    ): Boolean {
        if (closed || !isCaretBoundary(offset)) return false
        moveCaret(offset, extendSelection, keepPreferredColumn = false)
        return true
    }

    fun moveLeft(extendSelection: Boolean = false): Boolean {
        if (closed) return false
        val range = selectionRange
        val target =
            if (!extendSelection && range != null) {
                range.startUtf16
            } else {
                previousCaretBoundary(caretOffset)
            }
        if (target == caretOffset && range == null) return false
        moveCaret(target, extendSelection, keepPreferredColumn = false)
        return true
    }

    fun moveRight(extendSelection: Boolean = false): Boolean {
        if (closed) return false
        val range = selectionRange
        val target =
            if (!extendSelection && range != null) {
                range.endUtf16
            } else {
                nextCaretBoundary(caretOffset)
            }
        if (target == caretOffset && range == null) return false
        moveCaret(target, extendSelection, keepPreferredColumn = false)
        return true
    }

    fun moveUp(extendSelection: Boolean = false): Boolean = moveVertical(-1, extendSelection)

    fun moveDown(extendSelection: Boolean = false): Boolean = moveVertical(1, extendSelection)

    fun moveHome(extendSelection: Boolean = false): Boolean {
        if (closed) return false
        val target = lines.line(lines.lineOfOffset(caretOffset)).startUtf16
        if (target == caretOffset && (!extendSelection || selectionRange == null)) return false
        moveCaret(target, extendSelection, keepPreferredColumn = false)
        return true
    }

    fun moveEnd(extendSelection: Boolean = false): Boolean {
        if (closed) return false
        val target = lines.line(lines.lineOfOffset(caretOffset)).contentEndUtf16
        if (target == caretOffset && (!extendSelection || selectionRange == null)) return false
        moveCaret(target, extendSelection, keepPreferredColumn = false)
        return true
    }

    fun selectAll() {
        if (closed) return
        history.breakGroup()
        selection = EditorSelection(0, length)
        preferredColumn = null
    }

    fun copySelection(): String? = selectionRange?.let(::copyRange)

    fun type(text: String): EditorEditResult =
        replaceSelection(
            text,
            if ('\r' in text || '\n' in text) EditorHistoryKind.Atomic else EditorHistoryKind.Typing,
        )

    fun paste(text: String): EditorEditResult = replaceSelection(text, EditorHistoryKind.Atomic)

    fun replaceRange(
        range: EditorRange,
        text: String,
    ): EditorEditResult = replace(range, text, EditorHistoryKind.Atomic, EditorChangeOrigin.User)

    fun cut(): EditorEditResult {
        val range = selectionRange ?: return EditorEditResult.NoChange
        return replace(range, "", EditorHistoryKind.Atomic, EditorChangeOrigin.User)
    }

    fun backspace(): EditorEditResult {
        selectionRange?.let { return replace(it, "", EditorHistoryKind.Atomic, EditorChangeOrigin.User) }
        if (caretOffset == 0) return EditorEditResult.NoChange
        return replace(
            EditorRange(previousCaretBoundary(caretOffset), caretOffset),
            "",
            EditorHistoryKind.Backspace,
            EditorChangeOrigin.User,
        )
    }

    fun delete(): EditorEditResult {
        selectionRange?.let { return replace(it, "", EditorHistoryKind.Atomic, EditorChangeOrigin.User) }
        if (caretOffset == length) return EditorEditResult.NoChange
        return replace(
            EditorRange(caretOffset, nextCaretBoundary(caretOffset)),
            "",
            EditorHistoryKind.Atomic,
            EditorChangeOrigin.User,
        )
    }

    fun enter(): EditorEditResult {
        if (closed) return EditorEditResult.Rejected(EditorRejection.Closed)
        val line = lines.line(lines.lineOfOffset(caretOffset))
        var indentEnd = line.startUtf16
        while (indentEnd < line.contentEndUtf16) {
            val char = buffer.charAt(indentEnd)
            if (char != ' ' && char != '\t') break
            indentEnd++
        }
        val indent = buffer.copyRange(line.startUtf16, indentEnd).concatToString()
        return replaceSelection(preferredLineSeparator + indent, EditorHistoryKind.Atomic)
    }

    fun tab(): EditorEditResult {
        val spaces = limits.tabWidth - caretVisualColumn % limits.tabWidth
        return replaceSelection(" ".repeat(spaces), EditorHistoryKind.Atomic)
    }

    fun undo(): EditorEditResult {
        if (closed) return EditorEditResult.Rejected(EditorRejection.Closed)
        val entry = history.popUndo() ?: return EditorEditResult.NoChange
        return applyHistory(entry, undo = true)
    }

    fun redo(): EditorEditResult {
        if (closed) return EditorEditResult.Rejected(EditorRejection.Closed)
        val entry = history.popRedo() ?: return EditorEditResult.NoChange
        return applyHistory(entry, undo = false)
    }

    fun breakUndoGroup() = history.breakGroup()

    internal fun reset(text: String) {
        check(!closed) { "editor is closed" }
        val oldRange = EditorRange(0, length)
        val oldLines = 0..lines.lineCount - 1
        check(buffer.replace(0, length, text) == BufferReplaceResult.Applied)
        lines.rebuild()
        history.clear()
        selection = EditorSelection(0, 0)
        preferredColumn = null
        publish(oldRange, text.length, oldLines, 0..lines.lineCount - 1, EditorChangeOrigin.ExternalReset)
    }

    fun close() {
        if (closed) return
        closed = true
        history.clear()
        listeners.clear()
    }

    internal fun line(index: Int): EditorLine = lines.line(index)

    internal fun lineOfOffset(offset: Int): Int = lines.lineOfOffset(offset)

    internal fun offsetAtVisualColumn(
        line: Int,
        column: Int,
    ): Int = lines.offsetAtVisualColumn(line, column)

    internal fun lineVisualWidth(line: Int): Int = lines.visualWidth(line)

    private fun replaceSelection(
        text: String,
        kind: EditorHistoryKind,
    ): EditorEditResult = replace(selection.range, text, kind, EditorChangeOrigin.User)

    private fun replace(
        range: EditorRange,
        text: String,
        kind: EditorHistoryKind,
        origin: EditorChangeOrigin,
    ): EditorEditResult {
        if (closed) return EditorEditResult.Rejected(EditorRejection.Closed)
        if (!isCaretBoundary(range.startUtf16) || !isCaretBoundary(range.endUtf16)) {
            return EditorEditResult.Rejected(EditorRejection.InvalidRange)
        }
        if (range.length == 0 && text.isEmpty()) return EditorEditResult.NoChange
        val removed = buffer.copyRange(range.startUtf16, range.endUtf16).concatToString()
        val before = selection
        val after = EditorSelection(range.startUtf16 + text.length, range.startUtf16 + text.length)
        val entry = EditorHistoryEntry(range.startUtf16, removed, text, before, after, kind)
        if (!history.canRecord(entry)) return EditorEditResult.Rejected(EditorRejection.UndoLimit)
        val oldLines = lines.affectedLines(range)
        when (val result = buffer.replace(range.startUtf16, range.endUtf16, text)) {
            BufferReplaceResult.Applied -> Unit
            is BufferReplaceResult.Rejected -> return EditorEditResult.Rejected(result.reason)
        }
        lines.rebuildFrom(oldLines.first)
        selection = after
        preferredColumn = null
        history.record(entry)
        return publish(
            range,
            text.length,
            oldLines,
            lines.affectedLines(EditorRange(range.startUtf16, range.startUtf16 + text.length)),
            origin,
        )
    }

    private fun applyHistory(
        entry: EditorHistoryEntry,
        undo: Boolean,
    ): EditorEditResult {
        val removedNow = if (undo) entry.inserted else entry.removed
        val insertedNow = if (undo) entry.removed else entry.inserted
        val range = EditorRange(entry.startUtf16, entry.startUtf16 + removedNow.length)
        val oldLines = lines.affectedLines(range)
        check(buffer.replace(range.startUtf16, range.endUtf16, insertedNow) == BufferReplaceResult.Applied)
        lines.rebuildFrom(oldLines.first)
        selection = if (undo) entry.beforeSelection else entry.afterSelection
        preferredColumn = null
        return publish(
            range,
            insertedNow.length,
            oldLines,
            lines.affectedLines(EditorRange(range.startUtf16, range.startUtf16 + insertedNow.length)),
            EditorChangeOrigin.UndoRedo,
        )
    }

    private fun publish(
        oldRange: EditorRange,
        insertedCodeUnits: Int,
        oldLines: IntRange,
        newLines: IntRange,
        origin: EditorChangeOrigin,
    ): EditorEditResult.Applied {
        val oldRevision = revision
        revision = Math.incrementExact(revision)
        val change = EditorChange(oldRevision, revision, oldRange, insertedCodeUnits, oldLines, newLines, origin)
        listeners.values.toList().forEach { it(change) }
        return EditorEditResult.Applied(change)
    }

    private fun moveVertical(
        delta: Int,
        extendSelection: Boolean,
    ): Boolean {
        if (closed) return false
        val currentLine = lines.lineOfOffset(caretOffset)
        val targetLine = (currentLine + delta).coerceIn(0, lines.lineCount - 1)
        if (targetLine == currentLine) return false
        val desired = preferredColumn ?: caretVisualColumn
        val target = lines.offsetAtVisualColumn(targetLine, desired)
        preferredColumn = desired
        moveCaret(target, extendSelection, keepPreferredColumn = true)
        return true
    }

    private fun moveCaret(
        target: Int,
        extendSelection: Boolean,
        keepPreferredColumn: Boolean,
    ) {
        history.breakGroup()
        selection =
            if (extendSelection) {
                EditorSelection(selection.anchorUtf16, target)
            } else {
                EditorSelection(target, target)
            }
        if (!keepPreferredColumn) preferredColumn = null
    }

    private fun previousCaretBoundary(offset: Int): Int =
        if (offset >= 2 && buffer.charAt(offset - 2) == '\r' && buffer.charAt(offset - 1) == '\n') {
            offset - 2
        } else {
            buffer.previousScalarBoundary(offset)
        }

    private fun nextCaretBoundary(offset: Int): Int =
        if (offset + 1 < length && buffer.charAt(offset) == '\r' && buffer.charAt(offset + 1) == '\n') {
            offset + 2
        } else {
            buffer.nextScalarBoundary(offset)
        }

    private fun isCaretBoundary(offset: Int): Boolean {
        if (offset !in 0..length) return false
        if (offset == 0 || offset == length) return true
        if (buffer.charAt(offset - 1) == '\r' && buffer.charAt(offset) == '\n') return false
        return !(Character.isHighSurrogate(buffer.charAt(offset - 1)) && Character.isLowSurrogate(buffer.charAt(offset)))
    }
}

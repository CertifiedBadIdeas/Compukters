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

internal enum class EditorHistoryKind {
    Typing,
    Backspace,
    Atomic,
}

internal data class EditorHistoryEntry(
    val startUtf16: Int,
    val removed: String,
    val inserted: String,
    val beforeSelection: EditorSelection,
    val afterSelection: EditorSelection,
    val kind: EditorHistoryKind,
) {
    val charge: Int
        get() = removed.length + inserted.length
}

internal class EditorHistory(
    private val limits: EditorLimits,
) {
    private val undo = ArrayDeque<EditorHistoryEntry>()
    private val redo = ArrayDeque<EditorHistoryEntry>()
    private var undoUnits = 0
    private var groupOpen = false

    val undoEntryCount: Int
        get() = undo.size

    fun canRecord(entry: EditorHistoryEntry): Boolean {
        if (limits.maxUndoEntries == 0) return false
        val candidate = coalesced(entry) ?: entry
        return candidate.charge <= limits.maxUndoCodeUnits
    }

    fun record(entry: EditorHistoryEntry) {
        val candidate = coalesced(entry)
        if (candidate == null) {
            undo += entry
            undoUnits += entry.charge
        } else {
            val prior = undo.removeLast()
            undoUnits -= prior.charge
            undo += candidate
            undoUnits += candidate.charge
        }
        redo.clear()
        groupOpen = entry.kind != EditorHistoryKind.Atomic
        while (undo.size > limits.maxUndoEntries || undoUnits > limits.maxUndoCodeUnits) {
            undoUnits -= undo.removeFirst().charge
        }
    }

    fun popUndo(): EditorHistoryEntry? {
        breakGroup()
        val entry = undo.removeLastOrNull() ?: return null
        undoUnits -= entry.charge
        redo += entry
        return entry
    }

    fun popRedo(): EditorHistoryEntry? {
        breakGroup()
        val entry = redo.removeLastOrNull() ?: return null
        undo += entry
        undoUnits += entry.charge
        return entry
    }

    fun breakGroup() {
        groupOpen = false
    }

    fun clear() {
        undo.clear()
        redo.clear()
        undoUnits = 0
        groupOpen = false
    }

    private fun coalesced(next: EditorHistoryEntry): EditorHistoryEntry? {
        if (!groupOpen) return null
        val previous = undo.lastOrNull() ?: return null
        if (previous.kind != next.kind) return null
        return when (next.kind) {
            EditorHistoryKind.Typing -> {
                if (previous.removed.isEmpty() && next.removed.isEmpty() &&
                    previous.startUtf16 + previous.inserted.length == next.startUtf16
                ) {
                    previous.copy(inserted = previous.inserted + next.inserted, afterSelection = next.afterSelection)
                } else {
                    null
                }
            }

            EditorHistoryKind.Backspace -> {
                if (previous.inserted.isEmpty() && next.inserted.isEmpty() &&
                    next.startUtf16 + next.removed.length == previous.startUtf16
                ) {
                    previous.copy(
                        startUtf16 = next.startUtf16,
                        removed = next.removed + previous.removed,
                        afterSelection = next.afterSelection,
                    )
                } else {
                    null
                }
            }

            EditorHistoryKind.Atomic -> {
                null
            }
        }
    }
}

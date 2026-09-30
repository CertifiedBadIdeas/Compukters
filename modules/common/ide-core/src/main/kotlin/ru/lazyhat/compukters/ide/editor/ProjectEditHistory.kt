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

/** Owner-thread coordinator; documents retain their ordinary typing groups and selection history. */
class ProjectEditHistory(
    private val maxGroups: Int = 32,
) {
    private val groups = linkedMapOf<Long, List<EditorDocument>>()
    private var nextGroup = 1L

    init {
        require(maxGroups > 0)
    }

    data class Replacement(
        val document: EditorDocument,
        val expectedRevision: Long,
        val text: String,
        val caretUtf16: Int,
    )

    sealed interface Result {
        data class Applied(
            val changes: Map<EditorDocument, EditorChange>,
        ) : Result

        data object NoChange : Result

        data class Rejected(
            val detail: String,
        ) : Result
    }

    fun apply(replacements: List<Replacement>): Result {
        if (replacements.map { it.document }.distinct().size != replacements.size) return Result.Rejected("duplicate document")
        if (replacements.any { it.document.revision != it.expectedRevision }) return Result.Rejected("document changed")
        if (replacements.any { !it.document.canReplaceAll(it.text, it.caretUtf16) }) return Result.Rejected("editor or undo limit")
        val changed = replacements.filterNot { it.document.contentEquals(it.text) }
        if (changed.isEmpty()) return Result.NoChange
        val id = nextGroup
        nextGroup = Math.incrementExact(nextGroup)
        val changes = linkedMapOf<EditorDocument, EditorChange>()
        changed.forEach { replacement ->
            val edit = replacement.document.replaceAll(replacement.text, replacement.caretUtf16, id)
            check(edit is EditorEditResult.Applied) { "admitted document edit failed" }
            changes[replacement.document] = edit.change
        }
        groups[id] = changed.map { it.document }
        while (groups.size > maxGroups) groups.remove(groups.keys.first())
        return Result.Applied(changes)
    }

    fun undo(document: EditorDocument): Result = move(document, redo = false)

    fun redo(document: EditorDocument): Result = move(document, redo = true)

    fun retains(document: EditorDocument): Boolean = groups.values.any { document in it }

    private fun move(
        document: EditorDocument,
        redo: Boolean,
    ): Result {
        val id = if (redo) document.redoTransactionId else document.undoTransactionId
        val involved = if (id == null) listOf(document) else groups[id] ?: return Result.Rejected("project history expired")
        if (involved.any { it.isClosed }) return Result.Rejected("document is unavailable")
        if (id != null && involved.any { (if (redo) it.redoTransactionId else it.undoTransactionId) != id }) {
            return Result.Rejected("another affected document has later edits")
        }
        val changes = linkedMapOf<EditorDocument, EditorChange>()
        involved.forEach { source ->
            when (val edit = if (redo) source.redo() else source.undo()) {
                is EditorEditResult.Applied -> changes[source] = edit.change
                EditorEditResult.NoChange -> Unit
                is EditorEditResult.Rejected -> return Result.Rejected("document is unavailable")
            }
        }
        return if (changes.isEmpty()) Result.NoChange else Result.Applied(changes)
    }

    fun clear() {
        groups.clear()
    }
}

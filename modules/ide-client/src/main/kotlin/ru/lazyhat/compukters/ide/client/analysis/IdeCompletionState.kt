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

package ru.lazyhat.compukters.ide.client.analysis

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorEditResult
import ru.lazyhat.compukters.ide.editor.EditorRange
import java.util.Collections

const val IDE_COMPLETION_VISIBLE_ROWS = 8

@ConsistentCopyVisibility
data class IdeCompletionState private constructor(
    val identity: AnalysisSnapshotIdentity,
    val path: VirtualSourcePath,
    val replacement: EditorRange,
    val items: List<CompletionItem>,
    val selectedIndex: Int,
    val firstVisibleIndex: Int,
) {
    val selectedItem: CompletionItem
        get() = items[selectedIndex]

    val visibleItems: List<CompletionItem>
        get() = items.subList(firstVisibleIndex, minOf(items.size, firstVisibleIndex + IDE_COMPLETION_VISIBLE_ROWS))

    fun move(delta: Int): IdeCompletionState = withSelection(selectedIndex.toLong() + delta)

    fun movePage(
        pages: Int,
        pageSize: Int,
    ): IdeCompletionState {
        require(pageSize > 0) { "completion page size must be positive" }
        return withSelection(selectedIndex.toLong() + pages.toLong() * pageSize)
    }

    fun accept(
        document: EditorDocument,
        currentIdentity: AnalysisSnapshotIdentity,
        currentPath: VirtualSourcePath,
    ): IdeCompletionAcceptance {
        if (identity != currentIdentity || path != currentPath) return IdeCompletionAcceptance.Stale
        return when (val result = document.replaceRange(replacement, selectedItem.insertText)) {
            is EditorEditResult.Applied -> IdeCompletionAcceptance.Applied(result)

            EditorEditResult.NoChange,
            is EditorEditResult.Rejected,
            -> IdeCompletionAcceptance.Rejected(result)
        }
    }

    private fun withSelection(index: Long): IdeCompletionState {
        val selected = index.coerceIn(0, items.lastIndex.toLong()).toInt()
        val first =
            when {
                selected < firstVisibleIndex -> {
                    selected
                }

                selected >= firstVisibleIndex + IDE_COMPLETION_VISIBLE_ROWS -> {
                    selected - IDE_COMPLETION_VISIBLE_ROWS + 1
                }

                else -> {
                    firstVisibleIndex
                }
            }
        return copy(selectedIndex = selected, firstVisibleIndex = first)
    }

    companion object {
        fun create(
            identity: AnalysisSnapshotIdentity,
            path: VirtualSourcePath,
            replacement: EditorRange,
            items: List<CompletionItem>,
            selectedIndex: Int = 0,
        ): IdeCompletionState {
            require(items.isNotEmpty()) { "completion popup must contain at least one item" }
            require(selectedIndex in items.indices) { "completion selection exceeds item range" }
            return IdeCompletionState(
                identity,
                VirtualSourcePath.kotlin(path.value),
                replacement,
                Collections.unmodifiableList(items.toList()),
                selectedIndex,
                (selectedIndex - IDE_COMPLETION_VISIBLE_ROWS + 1).coerceAtLeast(0),
            )
        }
    }
}

sealed interface IdeCompletionAcceptance {
    data class Applied(
        val edit: EditorEditResult.Applied,
    ) : IdeCompletionAcceptance

    data class Rejected(
        val edit: EditorEditResult,
    ) : IdeCompletionAcceptance

    data object Stale : IdeCompletionAcceptance
}

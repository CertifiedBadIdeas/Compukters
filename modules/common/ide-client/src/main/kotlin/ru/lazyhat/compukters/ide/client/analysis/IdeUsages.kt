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

import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.util.Collections

data class IdeUsage(
    val path: ProjectPath,
    val range: EditorRange,
    val line: Int,
    val context: String,
)

class IdeUsages(
    val identity: AnalysisSnapshotIdentity,
    rows: List<IdeUsage>,
    val total: Int,
    val selectedIndex: Int = 0,
    val focused: Boolean = true,
) {
    val rows: List<IdeUsage> = Collections.unmodifiableList(rows.toList())

    fun move(delta: Int): IdeUsages =
        IdeUsages(
            identity,
            rows,
            total,
            (selectedIndex + delta.toLong()).coerceIn(0, (rows.size - 1).coerceAtLeast(0).toLong()).toInt(),
            true,
        )

    fun unfocus(): IdeUsages = IdeUsages(identity, rows, total, selectedIndex, false)
}

sealed interface IdeUsagesOutcome {
    data class Found(
        val value: IdeUsages,
    ) : IdeUsagesOutcome

    data class Failed(
        val detail: String,
    ) : IdeUsagesOutcome
}

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

package ru.lazyhat.compukters.ide.client.state

import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import java.util.Collections

/** Exact source evidence travels with a row, never an index into a later presentation. */
data class IdeDiagnosticRow(
    val diagnostic: EditorDiagnostic,
    val sourceText: String? = null,
) {
    val navigable: Boolean
        get() {
            val range = diagnostic.range ?: return false
            val text = sourceText ?: return false
            return diagnostic.path != null && range.endUtf16 <= text.length &&
                text.isBoundary(range.startUtf16) && text.isBoundary(range.endUtf16)
        }

    val line: Int? =
        if (navigable) {
            var value = 0
            for (index in 0 until diagnostic.range!!.startUtf16) if (sourceText!![index] == '\n') value++
            value
        } else {
            null
        }

    private fun String.isBoundary(offset: Int): Boolean =
        offset == 0 || offset == length || !(this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate())
}

class IdeDiagnostics(
    rows: List<IdeDiagnosticRow>,
) {
    val rows: List<IdeDiagnosticRow> =
        Collections.unmodifiableList(
            rows.distinctBy { it.diagnostic }.sortedWith(
                compareBy({ it.diagnostic.path?.value ?: "" }, { it.diagnostic.range?.startUtf16 ?: -1 }),
            ),
        )
    val errors: Int get() = rows.count { it.diagnostic.severity == EditorDiagnosticSeverity.Error }
    val warnings: Int get() = rows.count { it.diagnostic.severity == EditorDiagnosticSeverity.Warning }

    fun next(
        path: ProjectPath?,
        caretUtf16: Int,
        backwards: Boolean,
    ): IdeDiagnosticRow? {
        val locations = rows.filter { it.navigable && it.diagnostic.severity != EditorDiagnosticSeverity.Info }
        if (path == null) return if (backwards) locations.lastOrNull() else locations.firstOrNull()

        fun compare(row: IdeDiagnosticRow): Int {
            val byPath =
                row.diagnostic.path!!
                    .value
                    .compareTo(path.value)
            return if (byPath != 0) {
                byPath
            } else {
                row.diagnostic.range!!
                    .startUtf16
                    .compareTo(caretUtf16)
            }
        }
        return if (backwards) {
            locations.lastOrNull { compare(it) < 0 } ?: locations.lastOrNull()
        } else {
            locations.firstOrNull { compare(it) > 0 } ?: locations.firstOrNull()
        }
    }

    companion object {
        val Empty = IdeDiagnostics(emptyList())

        fun unlocated(
            editor: IdeEditorView,
            build: ru.lazyhat.compukters.ide.client.build.IdeBuildState,
        ): IdeDiagnostics {
            val analysis = (editor as? IdeEditorView.Text)?.analysis as? ru.lazyhat.compukters.ide.client.analysis.IdeAnalysisState.Active
            val values =
                analysis?.presentation?.diagnostics.orEmpty() +
                    (build as? ru.lazyhat.compukters.ide.client.build.IdeBuildState.Diagnostics)?.values.orEmpty()
            return IdeDiagnostics(values.map { IdeDiagnosticRow(it) })
        }
    }
}

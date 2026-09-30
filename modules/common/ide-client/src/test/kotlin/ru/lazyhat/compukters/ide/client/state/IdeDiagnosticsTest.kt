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

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.EditorDiagnostic
import ru.lazyhat.compukters.ide.analysis.EditorDiagnosticSeverity
import ru.lazyhat.compukters.ide.editor.EditorRange
import ru.lazyhat.compukters.ide.project.fs.ProjectPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class IdeDiagnosticsTest {
    @Test
    fun `navigation orders locations across files wraps and skips info and unlocated problems`() {
        val first = row("src/a.kt", 0)
        val second = row("src/a.kt", 2, EditorDiagnosticSeverity.Warning)
        val third = row("src/b.kt", 1)
        val info = row("src/a.kt", 1, EditorDiagnosticSeverity.Info)
        val unlocated = IdeDiagnosticRow(EditorDiagnostic(EditorDiagnosticSeverity.Error, "No position"))
        val values = IdeDiagnostics(listOf(third, second, first, first, info, unlocated))
        assertEquals(3, values.errors)
        assertEquals(1, values.warnings)
        assertEquals(second, values.next(ProjectPath.file("src/a.kt"), 0, false))
        assertEquals(third, values.next(ProjectPath.file("src/a.kt"), 2, false))
        assertEquals(first, values.next(ProjectPath.file("src/b.kt"), 1, false))
        assertEquals(third, values.next(ProjectPath.file("src/a.kt"), 0, true))
        assertEquals(second, values.next(ProjectPath.file("src/b.kt"), 1, true))
        assertEquals(first, values.next(null, 0, false))
        assertEquals(third, values.next(null, 0, true))
        assertNull(IdeDiagnostics(listOf(info, unlocated)).next(null, 0, false))
    }

    @Test
    fun `locations require current source evidence and whole UTF16 boundaries`() {
        val path = VirtualSourcePath.kotlin("src/main.kt")
        val split = IdeDiagnosticRow(EditorDiagnostic(EditorDiagnosticSeverity.Error, "Split", path, EditorRange(1, 2)), "😀\nx")
        assertFalse(split.navigable)
        val outside = IdeDiagnosticRow(EditorDiagnostic(EditorDiagnosticSeverity.Error, "Outside", path, EditorRange(3, 8)), "abc")
        assertFalse(outside.navigable)
        val valid = IdeDiagnosticRow(EditorDiagnostic(EditorDiagnosticSeverity.Warning, "Whole", path, EditorRange(3, 4)), "😀\nx")
        assertEquals(1, valid.line)
        assertFalse(valid.copy(sourceText = null).navigable)
    }

    private fun row(
        path: String,
        start: Int,
        severity: EditorDiagnosticSeverity = EditorDiagnosticSeverity.Error,
    ) = IdeDiagnosticRow(EditorDiagnostic(severity, "Problem $start", VirtualSourcePath.kotlin(path), EditorRange(start, start + 1)), "abc")
}

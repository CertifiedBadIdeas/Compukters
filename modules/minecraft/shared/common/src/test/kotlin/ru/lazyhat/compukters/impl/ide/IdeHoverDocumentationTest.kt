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

package ru.lazyhat.compukters.impl.ide

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.DeclarationOrigin
import ru.lazyhat.compukters.ide.analysis.EditorExpressionInfo
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdeHoverDocumentationTest {
    @Test
    fun `signature precedes compact sections and origin follows documentation`() {
        val info =
            EditorExpressionInfo(
                VirtualSourcePath.kotlin("main.kt"),
                EditorRange(0, 7),
                "Unit",
                "fun require(value: Boolean): Unit",
                DeclarationOrigin.Project,
                "Checks a condition.\n@param value condition that must be true.\n@throws IllegalArgumentException if false.",
            )
        val lines = IdeHoverDocumentation(info).lines(80).map { it.text }
        assertEquals("fun require(value: Boolean): Unit", lines.first())
        assertTrue(lines.none { it.startsWith("Type:") })
        assertTrue("  value — condition that must be true." in lines)
        assertTrue("  IllegalArgumentException — if false." in lines)
        assertEquals("Project source", lines.last())
        val narrow = IdeHoverDocumentation(info).lines(20)
        assertTrue(narrow.all { IdeSourceColumns.columnAt(it.text) <= 20 })
        assertTrue(narrow.joinToString("") { it.text }.contains("condition that must be true."))
    }

    @Test
    fun `expressions without a declaration signature retain their inferred type`() {
        val info = EditorExpressionInfo(VirtualSourcePath.kotlin("main.kt"), EditorRange(0, 1), "Int", null, null)
        assertEquals(listOf("Int"), IdeHoverDocumentation(info).lines(80).map { it.text })
    }
}

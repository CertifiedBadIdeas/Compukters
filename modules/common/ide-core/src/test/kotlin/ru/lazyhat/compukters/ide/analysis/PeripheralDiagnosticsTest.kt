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

package ru.lazyhat.compukters.ide.analysis

import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeripheralDiagnosticsTest {
    private val type = "compukter.display.TextDisplay"
    private val fact = PeripheralLookup(VirtualSourcePath.kotlin("main.kt"), EditorRange(10, 15), type, "screen", false)
    private val device = PeripheralObservation("screen", listOf(type))

    @Test
    fun `mandatory absence warns while optional absence is informational without underline`() {
        val warning = PeripheralDiagnostics.collect(listOf(fact), emptyList(), true).single()
        val info = PeripheralDiagnostics.collect(listOf(fact.copy(optional = true)), emptyList(), true).single()
        assertEquals(EditorDiagnosticSeverity.Warning, warning.severity)
        assertEquals(EditorDiagnosticSeverity.Info, info.severity)
        assertEquals(EditorDiagnosticHighlight.None, info.highlight)
        assertEquals(fact.range, warning.range)
    }

    @Test
    fun `available names normalize exactly like runtime and incompatible contracts are explained`() {
        assertTrue(PeripheralDiagnostics.collect(listOf(fact.copy(name = " SCREEN ")), listOf(device), true).isEmpty())
        val mismatch =
            PeripheralDiagnostics
                .collect(
                    listOf(fact),
                    listOf(device.copy(types = listOf("compukter.display.GraphicalDisplay"))),
                    true,
                ).single()
        assertTrue(mismatch.message.contains("does not support TextDisplay"))
        assertTrue(PeripheralDiagnostics.collect(listOf(fact), listOf(device.copy(types = emptyList())), true).isEmpty())
    }

    @Test
    fun `unknown and incomplete observations cannot prove absence`() {
        assertTrue(PeripheralDiagnostics.collect(listOf(fact), emptyList(), false).isEmpty())
        assertTrue(PeripheralDiagnostics.collect(listOf(fact), listOf(device.copy(types = listOf("other.Type"))), false).isEmpty())
    }

    @Test
    fun `unavailable reasons and reachable ambiguity are preserved`() {
        for (availability in PeripheralAvailability.entries.filter { it != PeripheralAvailability.Available }) {
            val result = PeripheralDiagnostics.collect(listOf(fact), listOf(device.copy(availability = availability)), true)
            assertEquals(1, result.size)
            assertEquals(EditorDiagnosticSeverity.Warning, result.single().severity)
        }
        assertTrue(
            PeripheralDiagnostics
                .collect(listOf(fact), listOf(device, device), true)
                .single()
                .message
                .contains("More than one"),
        )
        assertTrue(
            PeripheralDiagnostics
                .collect(
                    listOf(fact),
                    listOf(device, device.copy(availability = PeripheralAvailability.OutOfRange)),
                    true,
                ).isEmpty(),
        )
    }
}

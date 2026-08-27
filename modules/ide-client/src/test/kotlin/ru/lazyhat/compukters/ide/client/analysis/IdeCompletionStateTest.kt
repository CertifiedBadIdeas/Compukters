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

import ru.lazyhat.compukters.compiler.worker.protocol.Hash256
import ru.lazyhat.compukters.compiler.worker.protocol.VirtualSourcePath
import ru.lazyhat.compukters.ide.analysis.AnalysisProfileIdentity
import ru.lazyhat.compukters.ide.analysis.AnalysisSnapshotIdentity
import ru.lazyhat.compukters.ide.analysis.CompletionItem
import ru.lazyhat.compukters.ide.analysis.CompletionKind
import ru.lazyhat.compukters.ide.analysis.SourceSnapshotId
import ru.lazyhat.compukters.ide.editor.EditorDocument
import ru.lazyhat.compukters.ide.editor.EditorRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IdeCompletionStateTest {
    @Test
    fun `selection and page movement are clamped and immutable`() {
        val mutable = (0 until 8).mapTo(mutableListOf()) { item("item$it") }
        val initial = IdeCompletionState.create(identity(1, 2), path(), EditorRange(0, 2), mutable)
        mutable.clear()

        assertEquals(8, initial.items.size)
        assertEquals(1, initial.move(1).selectedIndex)
        assertEquals(0, initial.move(-1).selectedIndex)
        assertEquals(3, initial.movePage(1, 3).selectedIndex)
        assertEquals(7, initial.movePage(20, 3).selectedIndex)
        assertEquals(0, initial.movePage(-20, 3).selectedIndex)
    }

    @Test
    fun `viewport moves only when selection crosses a visible boundary`() {
        val items = (0 until 12).map { item("item$it") }
        var state = IdeCompletionState.create(identity(1, 2), path(), EditorRange(0, 2), items)

        repeat(7) { state = state.move(1) }
        assertEquals(7, state.selectedIndex)
        assertEquals(0, state.firstVisibleIndex)
        assertEquals((0..7).map { "item$it" }, state.visibleItems.map { it.label })

        state = state.move(1)
        assertEquals(8, state.selectedIndex)
        assertEquals(1, state.firstVisibleIndex)
        assertEquals((1..8).map { "item$it" }, state.visibleItems.map { it.label })

        state = state.move(-1)
        assertEquals(7, state.selectedIndex)
        assertEquals(1, state.firstVisibleIndex)

        repeat(7) { state = state.move(-1) }
        assertEquals(0, state.selectedIndex)
        assertEquals(0, state.firstVisibleIndex)
    }

    @Test
    fun `viewport remains valid when page movement clamps at list boundaries`() {
        val items = (0 until 12).map { item("item$it") }
        val initial = IdeCompletionState.create(identity(1, 2), path(), EditorRange(0, 2), items)

        val last = initial.movePage(20, 8)
        assertEquals(11, last.selectedIndex)
        assertEquals(4, last.firstVisibleIndex)
        assertEquals((4..11).map { "item$it" }, last.visibleItems.map { it.label })

        val first = last.movePage(-20, 8)
        assertEquals(0, first.selectedIndex)
        assertEquals(0, first.firstVisibleIndex)
        assertEquals((0..7).map { "item$it" }, first.visibleItems.map { it.label })
    }

    @Test
    fun `acceptance rejects stale identity and path then replaces atomically`() {
        val document = EditorDocument("pr value")
        val state = IdeCompletionState.create(identity(1, 2), path(), EditorRange(0, 2), listOf(item("println")))

        assertIs<IdeCompletionAcceptance.Stale>(state.accept(document, identity(9, 2), path()))
        assertIs<IdeCompletionAcceptance.Stale>(
            state.accept(document, identity(1, 2), VirtualSourcePath.kotlin("src/other.kt")),
        )
        assertEquals("pr value", document.materialize())

        assertIs<IdeCompletionAcceptance.Applied>(state.accept(document, identity(1, 2), path()))
        assertEquals("println value", document.materialize())
        document.undo()
        assertEquals("pr value", document.materialize())
        assertEquals(ru.lazyhat.compukters.ide.editor.EditorEditResult.NoChange, document.undo())
    }

    @Test
    fun `acceptance rejects a range outside the current editor`() {
        val document = EditorDocument("pr")
        val state = IdeCompletionState.create(identity(1, 2), path(), EditorRange(0, 3), listOf(item("println")))

        assertIs<IdeCompletionAcceptance.Rejected>(state.accept(document, identity(1, 2), path()))
        assertEquals("pr", document.materialize())
    }

    private fun item(label: String) = CompletionItem(label, label, CompletionKind.Function)

    private fun path() = VirtualSourcePath.kotlin("src/main.kt")

    private fun identity(
        source: Int,
        profile: Int,
    ) = AnalysisSnapshotIdentity(SourceSnapshotId(hash(source)), AnalysisProfileIdentity(hash(profile)))

    private fun hash(seed: Int) = Hash256.of(ByteArray(32) { seed.toByte() })
}

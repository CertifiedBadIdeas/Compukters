/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ru.lazyhat.compukterkraft.impl.workbench

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.lazyhat.compukterkraft.core.workbench.TargetControlGateway
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchIdeFacade
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchStore
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchUpdateSource
import ru.lazyhat.compukterkraft.core.workbench.WorkspaceGateway
import ru.lazyhat.compukterkraft.lang.api.SourceLocation
import ru.lazyhat.compukterkraft.lang.api.SourceRange
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItemKind
import ru.lazyhat.compukterkraft.lang.runtime.ComputerIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.DefinitionTarget
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchStoreTest {
    @Test
    fun mergesRemoteStateReactively() =
        runTest(UnconfinedTestDispatcher()) {
            val workspaceGateway = FakeWorkspaceGateway()
            val store = WorkbenchStore(workspaceGateway, FakeTargetControlGateway(), FakeWorkbenchIdeFacade())
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            store.initialize()

            assertEquals(listOf(""), workspaceGateway.listRequests)

            updates.push(
                WorkbenchRemoteState(
                    entries = listOf(ComputerWorkspaceEntry("rom", directory = true)),
                    document = ComputerWorkspaceDocument("startup.ck", "fun main() {}", 7),
                ),
            )

            assertEquals("startup.ck", store.state.openDocument?.path)
            assertEquals("fun main() {}", store.state.editor.text)
            assertTrue(store.state.entries.any { it.path == "rom" })
            assertTrue(store.state.editor.ideSnapshot != null)
        }

    @Test
    fun appliesCompletionFromStoreState() =
        runTest(UnconfinedTestDispatcher()) {
            val workspaceGateway = FakeWorkspaceGateway()
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(workspaceGateway, FakeTargetControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(
                WorkbenchRemoteState(
                    document = ComputerWorkspaceDocument("main.ck", "pri", 1),
                ),
            )

            store.moveCursorTo(0, 3, visibleEditorLines = 20)
            store.openCompletion()
            store.applyCompletion(0)

            assertEquals("printLine", store.state.editor.text)
        }

    private class FakeWorkbenchUpdateSource : WorkbenchUpdateSource {
        private val _stateFlow = MutableStateFlow(WorkbenchRemoteState())
        override val stateFlow: StateFlow<WorkbenchRemoteState> = _stateFlow

        fun push(state: WorkbenchRemoteState) {
            _stateFlow.value = state
        }
    }

    private class FakeWorkspaceGateway : WorkspaceGateway {
        val listRequests = mutableListOf<String>()
        val readRequests = mutableListOf<String>()

        override fun list(path: String) {
            listRequests += path
        }

        override fun read(path: String) {
            readRequests += path
        }
    }

    private class FakeTargetControlGateway : TargetControlGateway {
        override fun reboot() {
        }

        override fun runTargetProgram() {
        }

        override fun attachTargetTerminal() {
        }
    }

    private class FakeWorkbenchIdeFacade : WorkbenchIdeFacade {
        override fun analyze(
            path: String,
            source: String,
        ): ComputerIdeSnapshot =
            ComputerIdeSnapshot(
                document = ComputerWorkspaceDocument(path, source, 0),
                diagnostics = emptyList(),
                highlights = emptyList(),
            )

        override fun complete(
            path: String,
            source: String,
            line: Int,
            column: Int,
        ): List<CompletionItem> =
            listOf(
                CompletionItem(
                    label = "printLine",
                    detail = "function",
                    kind = CompletionItemKind.FUNCTION,
                ),
            )

        override fun completeFromLastAnalysis(
            path: String,
            source: String,
            line: Int,
            column: Int,
        ): List<CompletionItem> = complete(path, source, line, column)

        override fun hover(
            path: String,
            source: String,
            line: Int,
            column: Int,
        ): HoverInfo? = null

        override fun definition(
            path: String,
            source: String,
            line: Int,
            column: Int,
        ): DefinitionTarget? =
            DefinitionTarget(
                path = path,
                range = SourceRange(SourceLocation(0, 0, 0), SourceLocation(0, 0, 0)),
            )
    }
}

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
package ru.lazyhat.compukterkraft.core.workbench

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import ru.lazyhat.compukterkraft.lang.api.SourceLocation
import ru.lazyhat.compukterkraft.lang.api.SourceRange
import ru.lazyhat.compukterkraft.lang.frontend.FormatResult
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.DeviceIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.DefinitionTarget
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo

/**
 * Tiny test fixture that builds a fully-bound [WorkbenchStore] in EDITOR
 * mode with a single document loaded. Reused by tests that exercise the
 * store from the outside (e.g. the editor view-model adapter) and don't
 * want to re-derive the full Fake* gateway boilerplate.
 */
internal object WorkbenchEditorViewModelTestSupport {
    fun makeStoreWithDocument(
        scope: TestScope,
        text: String,
        path: String = "main.ck",
        target: WorkbenchTargetState = WorkbenchTargetState(),
    ): WorkbenchStore {
        val store = WorkbenchStore(StubWorkspaceGateway(), StubControlGateway(), StubIdeFacade())
        val updates = StubUpdateSource()
        store.bind(scope.backgroundScope, updates)
        updates.push(document = DeviceWorkspaceDocument(path, text, 0), target = target)
        return store
    }

    private class StubUpdateSource : WorkbenchUpdateSource {
        private val flow = MutableStateFlow(WorkbenchRemoteState())
        override val stateFlow: StateFlow<WorkbenchRemoteState> = flow

        fun push(
            entries: List<DeviceWorkspaceEntry> = emptyList(),
            document: DeviceWorkspaceDocument? = null,
            target: WorkbenchTargetState = WorkbenchTargetState(),
        ) {
            flow.value = WorkbenchRemoteState(entries = entries, document = document, target = target)
        }
    }

    private class StubWorkspaceGateway : WorkspaceGateway {
        override fun list(path: String) {}

        override fun read(path: String) {}
    }

    private class StubControlGateway : TargetControlGateway {
        override fun reboot() {}

        override fun runTargetProgram() {}

        override fun attachTargetTerminal() {}
    }

    private class StubIdeFacade : WorkbenchIdeFacade {
        override fun analyze(
            path: String,
            source: String,
        ): DeviceIdeSnapshot =
            DeviceIdeSnapshot(
                document = DeviceWorkspaceDocument(path, source, 0),
                diagnostics = emptyList(),
                highlights = emptyList(),
            )

        override fun complete(
            path: String,
            source: String,
            line: Int,
            column: Int,
        ): List<CompletionItem> = emptyList()

        override fun completeFromLastAnalysis(
            path: String,
            source: String,
            line: Int,
            column: Int,
        ): List<CompletionItem> = emptyList()

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
        ): DefinitionTarget =
            DefinitionTarget(
                path = path,
                range = SourceRange(SourceLocation(0, 0, 0), SourceLocation(0, 0, 0)),
            )

        override fun formatDocument(
            path: String,
            source: String,
        ): FormatResult = FormatResult(emptyList())

        override fun cleanupDocument(
            path: String,
            source: String,
        ): FormatResult = FormatResult(emptyList())
    }
}

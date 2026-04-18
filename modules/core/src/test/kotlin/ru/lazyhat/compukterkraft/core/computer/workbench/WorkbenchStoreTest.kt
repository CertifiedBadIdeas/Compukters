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
package ru.lazyhat.compukterkraft.core.computer.workbench

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.lazyhat.compukterkraft.core.input.KeyCodes
import ru.lazyhat.compukterkraft.lang.api.SourceLocation
import ru.lazyhat.compukterkraft.lang.api.SourceRange
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItemKind
import ru.lazyhat.compukterkraft.lang.runtime.ComputerIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.DefinitionTarget
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchStoreTest {
    @Test
    fun terminalDockStartsHiddenAndCanBeToggled() =
        runTest(UnconfinedTestDispatcher()) {
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), FakeWorkbenchIdeFacade())

            assertFalse(store.state.terminalVisible)

            store.toggleTerminalVisibility()
            assertTrue(store.state.terminalVisible)

            store.toggleTerminalVisibility()
            assertFalse(store.state.terminalVisible)
        }

    @Test
    fun rebootDelegatesToControlGateway() =
        runTest(UnconfinedTestDispatcher()) {
            val controlGateway = FakeComputerControlGateway()
            val store = WorkbenchStore(FakeWorkspaceGateway(), controlGateway, FakeWorkbenchIdeFacade())

            store.rebootComputer()

            assertEquals(listOf("reboot"), controlGateway.calls)
        }

    @Test
    fun opensCompletionWhenTypingIdentifierPrefix() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "", 0))
            store.toggleMode()

            store.charTyped('w', visibleEditorLines = 20)

            assertEquals(listOf("completeFromLastAnalysis:0:1"), ideFacade.calls)
            assertTrue(
                store.state.editor.completionItems
                    .isNotEmpty(),
            )
        }

    @Test
    fun doesNotOpenCompletionAfterImportSpace() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "import", 0))
            store.toggleMode()
            store.moveCursorTo(0, 6, visibleEditorLines = 20)

            store.charTyped(' ', visibleEditorLines = 20)

            assertTrue(ideFacade.calls.isEmpty())
            assertTrue(
                store.state.editor.completionItems
                    .isEmpty(),
            )
        }

    @Test
    fun keepsDotTriggerWorking() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "terminal", 0))
            store.toggleMode()
            store.moveCursorTo(0, 8, visibleEditorLines = 20)

            store.charTyped('.', visibleEditorLines = 20)

            assertEquals(listOf("completeFromLastAnalysis:0:9"), ideFacade.calls)
        }

    @Test
    fun importPickerRequestsAvailableImportsFromFacade() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "", 0))
            store.toggleMode()

            store.openImportPicker()

            assertTrue(ideFacade.calls.contains("availableImports"))
        }

    @Test
    fun opensImportPickerWithAvailableImports() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "", 0))
            store.toggleMode()

            store.openImportPicker()

            assertTrue(store.state.editor.importPickerVisible)
            assertEquals(
                listOf("terminal"),
                store.state.editor.importPickerItems
                    .map { it.label },
            )
        }

    @Test
    fun appliesSelectedImportFromPicker() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "fun main() {}", 0))
            store.toggleMode()

            store.openImportPicker()
            store.applyImportPickerSelection(0, visibleEditorLines = 20)

            assertTrue(
                store.state.editor.text
                    .startsWith("import terminal;\n"),
            )
            assertTrue(!store.state.editor.importPickerVisible)
        }

    @Test
    fun ctrlAOpensImportPicker() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "", 0))
            store.toggleMode()

            store.keyPressed(KeyCodes.KEY_A, KeyCodes.MOD_CONTROL, visibleEditorLines = 20)

            assertTrue(store.state.editor.importPickerVisible)
            assertTrue(ideFacade.calls.contains("availableImports"))
        }

    @Test
    fun enterAppliesSelectedImportWhilePickerIsOpen() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade =
                FakeWorkbenchIdeFacade(
                    availableImports =
                        listOf(
                            CompletionItem(label = "terminal", detail = "base", kind = CompletionItemKind.MODULE),
                            CompletionItem(label = "filesystem", detail = "base", kind = CompletionItemKind.MODULE),
                        ),
                )
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "fun main() {}", 0))
            store.toggleMode()
            store.openImportPicker()

            store.keyPressed(KeyCodes.KEY_DOWN, modifiers = 0, visibleEditorLines = 20)
            store.keyPressed(KeyCodes.KEY_ENTER, modifiers = 0, visibleEditorLines = 20)

            assertTrue(
                store.state.editor.text
                    .startsWith("import filesystem;\n"),
            )
            assertTrue(!store.state.editor.importPickerVisible)
        }

    @Test
    fun disablesTargetActionsWhenNoTargetIsConnected() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "fun main() {}", 0))
            store.toggleMode()

            assertTrue(!store.state.target.connected)
            assertTrue(!store.state.actions.canPull)
            assertTrue(!store.state.actions.canPush)
            assertTrue(!store.state.actions.canRun)
            assertTrue(!store.state.actions.canAttachTerminal)
        }

    @Test
    fun enablesTargetActionsWhenTargetDescriptorArrives() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(
                document = ComputerWorkspaceDocument("main.ck", "fun main() {}", 0),
                target = WorkbenchTargetState(connected = true, displayName = "Pocket Computer", familyId = "normal"),
            )

            assertTrue(store.state.target.connected)
            assertTrue(store.state.actions.canPull)
            assertTrue(store.state.actions.canPush)
            assertTrue(store.state.actions.canRun)
            assertTrue(store.state.actions.canAttachTerminal)
        }

    @Test
    fun runActionDelegatesToControlGateway() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val controlGateway = FakeComputerControlGateway()
            val store = WorkbenchStore(FakeWorkspaceGateway(), controlGateway, ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(target = WorkbenchTargetState(connected = true, displayName = "Pocket Computer", familyId = "normal"))

            store.runTargetProgram()

            assertEquals(listOf("run"), controlGateway.calls)
        }

    @Test
    fun pullAndPushActionsDelegateToControlGateway() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val controlGateway = FakeComputerControlGateway()
            val store = WorkbenchStore(FakeWorkspaceGateway(), controlGateway, ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(target = WorkbenchTargetState(connected = true, displayName = "Pocket Computer", familyId = "normal"))

            store.pullFromTarget()
            store.pushToTarget()

            assertEquals(listOf("pull", "push"), controlGateway.calls)
        }

    @Test
    fun escapeIsNotHandledByEditorStore() =
        runTest(UnconfinedTestDispatcher()) {
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), FakeWorkbenchIdeFacade())
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "fun main() {}", 0))
            store.toggleMode()

            assertFalse(store.keyPressed(KeyCodes.KEY_ESCAPE, modifiers = 0, visibleEditorLines = 20))
        }

    @Test
    fun capturesPrintableKeyDownBeforeCharTypedInEditorMode() =
        runTest(UnconfinedTestDispatcher()) {
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), FakeWorkbenchIdeFacade())
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "", 0))
            store.toggleMode()

            assertTrue(store.keyPressed(69, modifiers = 0, visibleEditorLines = 20))
        }

    private class FakeWorkbenchUpdateSource : WorkbenchUpdateSource {
        private val _stateFlow = MutableStateFlow(WorkbenchRemoteState())
        override val stateFlow: StateFlow<WorkbenchRemoteState> = _stateFlow

        fun push(
            entries: List<ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry> = emptyList(),
            document: ComputerWorkspaceDocument? = null,
            target: WorkbenchTargetState = WorkbenchTargetState(),
        ) {
            _stateFlow.value = WorkbenchRemoteState(entries = entries, document = document, target = target)
        }
    }

    private class FakeWorkspaceGateway : WorkspaceGateway {
        override fun list(path: String) {
        }

        override fun read(path: String) {
        }

        override fun write(
            path: String,
            text: String,
        ) {
        }
    }

    private class FakeComputerControlGateway : ComputerControlGateway {
        val calls = mutableListOf<String>()

        override fun reboot() {
            calls += "reboot"
        }

        override fun pullFromTarget() {
            calls += "pull"
        }

        override fun pushToTarget() {
            calls += "push"
        }

        override fun runTargetProgram() {
            calls += "run"
        }

        override fun attachTargetTerminal() {
            calls += "attach"
        }
    }

    private class FakeWorkbenchIdeFacade(
        private val availableImports: List<CompletionItem> =
            listOf(CompletionItem(label = "terminal", detail = "base", kind = CompletionItemKind.MODULE)),
    ) : WorkbenchIdeFacade {
        val calls = mutableListOf<String>()

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
        ): List<CompletionItem> = listOf(CompletionItem(label = "manual", detail = "", kind = CompletionItemKind.KEYWORD))

        override fun completeFromLastAnalysis(
            path: String,
            source: String,
            line: Int,
            column: Int,
        ): List<CompletionItem> {
            calls += "completeFromLastAnalysis:$line:$column"
            return listOf(CompletionItem(label = "while", detail = "keyword", kind = CompletionItemKind.KEYWORD))
        }

        override fun availableImports(
            path: String,
            source: String,
        ): List<CompletionItem> {
            calls += "availableImports"
            return availableImports
        }

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
    }
}

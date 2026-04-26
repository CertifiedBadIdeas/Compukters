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
import kotlinx.coroutines.launch
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
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(target = WorkbenchTargetState(connected = true, displayName = "Pocket Computer", familyId = "normal"))

            assertFalse(store.state.terminalVisible)

            store.toggleTerminalVisibility()
            assertTrue(store.state.terminalVisible)

            store.toggleTerminalVisibility()
            assertFalse(store.state.terminalVisible)
        }

    @Test
    fun terminalCannotBeOpenedWithoutAttachedComputer() =
        runTest(UnconfinedTestDispatcher()) {
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), FakeWorkbenchIdeFacade())

            store.toggleTerminalVisibility()

            assertFalse(store.state.terminalVisible)
        }

    @Test
    fun terminalAutoHidesWhenComputerIsRemoved() =
        runTest(UnconfinedTestDispatcher()) {
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), FakeWorkbenchIdeFacade())
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(target = WorkbenchTargetState(connected = true, displayName = "Pocket Computer", familyId = "normal"))

            store.toggleTerminalVisibility()
            assertTrue(store.state.terminalVisible)

            updates.push(target = WorkbenchTargetState())

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

            assertTrue(!store.state.target.connected)
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
    fun escapeIsNotHandledByEditorStore() =
        runTest(UnconfinedTestDispatcher()) {
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), FakeWorkbenchIdeFacade())
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "fun main() {}", 0))

            assertFalse(store.keyPressed(KeyCodes.KEY_ESCAPE, modifiers = 0, visibleEditorLines = 20))
        }

    @Test
    fun capturesPrintableKeyDownBeforeCharTypedInEditorMode() =
        runTest(UnconfinedTestDispatcher()) {
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeComputerControlGateway(), FakeWorkbenchIdeFacade())
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "", 0))

            assertTrue(store.keyPressed(69, modifiers = 0, visibleEditorLines = 20))
        }

    // --- Task 8: CRDT sync tests -----------------------------------------------------------

    @Test
    fun applyLocalEditAddsToOutbox() =
        runTest(UnconfinedTestDispatcher()) {
            val opsGateway = FakeWorkbenchOpsGateway()
            val store =
                WorkbenchStore(
                    FakeWorkspaceGateway(),
                    FakeComputerControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    opsGateway,
                ) {
                    ru.lazyhat.compukterkraft.core.computer.workbench.crdt
                        .SiteId("p:test01")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "", 0))

            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.computer.workbench.LocalEdit
                    .Insert(0, "ab"),
            )

            assertEquals("ab", store.state.editor.text)
            // Wait past the debounce window so the outbox flushes the batch.
            testScheduler.advanceTimeBy(80)
            testScheduler.runCurrent()
            assertEquals(1, opsGateway.batches.size)
            assertEquals("main.ck", opsGateway.batches[0].first)
            assertEquals(1, opsGateway.batches[0].second.size)
        }

    @Test
    fun applyAckClearsPendingCount() =
        runTest(UnconfinedTestDispatcher()) {
            val opsGateway = FakeWorkbenchOpsGateway()
            val store =
                WorkbenchStore(
                    FakeWorkspaceGateway(),
                    FakeComputerControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    opsGateway,
                ) {
                    ru.lazyhat.compukterkraft.core.computer.workbench.crdt
                        .SiteId("p:test02")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "", 0))

            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.computer.workbench.LocalEdit
                    .Insert(0, "x"),
            )
            testScheduler.advanceTimeBy(80)
            testScheduler.runCurrent()
            // Insert of 1 char at clock 0 → highest clock acked is 0.
            store.applyAck(0)
            testScheduler.runCurrent()

            assertEquals(0, store.state.editor.pendingOpCount)
            assertEquals(
                ru.lazyhat.compukterkraft.core.computer.workbench.sync.SyncStatus.Idle,
                store.state.editor.syncStatus,
            )
        }

    @Test
    fun applyRemoteOpsUpdatesEditorText() =
        runTest(UnconfinedTestDispatcher()) {
            val store =
                WorkbenchStore(
                    FakeWorkspaceGateway(),
                    FakeComputerControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    FakeWorkbenchOpsGateway(),
                ) {
                    ru.lazyhat.compukterkraft.core.computer.workbench.crdt
                        .SiteId("p:test03")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "hello", 0))

            // Bootstrap atomized "hello" with site=ServerInit at clock 0..4. Last visible
            // atom = (ServerInit, 4). Append "!" via remote op.
            val lastAtom =
                store.replica!!
                    .document
                    .atomAtOffset(4)!!
                    .first
            val remoteOp =
                ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op.Insert(
                    author =
                        ru.lazyhat.compukterkraft.core.computer.workbench.crdt
                            .SiteId("p:other1"),
                    clock = 0,
                    leftId = lastAtom,
                    text = "!",
                )

            store.applyRemoteOps(listOf(remoteOp))

            assertEquals("hello!", store.state.editor.text)
        }

    @Test
    fun flushAndRunWaitsForSync() =
        runTest(UnconfinedTestDispatcher()) {
            val opsGateway = FakeWorkbenchOpsGateway()
            val controlGateway = FakeComputerControlGateway()
            val store =
                WorkbenchStore(
                    FakeWorkspaceGateway(),
                    controlGateway,
                    FakeWorkbenchIdeFacade(),
                    opsGateway,
                ) {
                    ru.lazyhat.compukterkraft.core.computer.workbench.crdt
                        .SiteId("p:test04")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(
                document = ComputerWorkspaceDocument("main.ck", "", 0),
                target = WorkbenchTargetState(connected = true, displayName = "PC", familyId = "normal"),
            )

            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.computer.workbench.LocalEdit
                    .Insert(0, "x"),
            )
            // Schedule the ack to arrive while flushAndRun is suspended.
            val ackJob =
                backgroundScope.launch {
                    kotlinx.coroutines.delay(20)
                    store.applyAck(0)
                }
            store.flushAndRun(timeoutMs = 1_000)
            ackJob.join()

            assertEquals(listOf("run"), controlGateway.calls)
            assertTrue(opsGateway.batches.isNotEmpty())
        }

    @Test
    fun cursorMovesWhenRemoteInsertHappensLeft() =
        runTest(UnconfinedTestDispatcher()) {
            val store =
                WorkbenchStore(
                    FakeWorkspaceGateway(),
                    FakeComputerControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    FakeWorkbenchOpsGateway(),
                ) {
                    ru.lazyhat.compukterkraft.core.computer.workbench.crdt
                        .SiteId("p:test05")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "world", 0))
            store.moveCursorTo(0, 5, visibleEditorLines = 20) // at end

            // Remote insert at the start of the document (leftId = null).
            val remoteOp =
                ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op.Insert(
                    author =
                        ru.lazyhat.compukterkraft.core.computer.workbench.crdt
                            .SiteId("p:other2"),
                    clock = 0,
                    leftId = null,
                    text = "hi-",
                )

            store.applyRemoteOps(listOf(remoteOp))

            assertEquals("hi-world", store.state.editor.text)
            // Cursor was at offset 5 (after "world"); remote inserted "hi-" (3 chars) to its
            // left, so the new visible offset should be 5 + 3 = 8.
            assertEquals(0, store.state.editor.cursorLine)
            assertEquals(8, store.state.editor.cursorColumn)
        }

    @Test
    fun staleWorkspacePushDoesNotResetActiveCrdtSession() =
        runTest(UnconfinedTestDispatcher()) {
            // Regression: when a CRDT session is open and the user has typed locally, the
            // server can still push a WorkbenchWorkspaceClientMessage whose `document.text`
            // is whatever the server last read from disk — i.e. STALE, because in-flight ops
            // haven't been materialized yet. This happens on every action reply (LIST, READ,
            // ATTACH_TERMINAL, target stack swap, ...). Previously mergeRemoteState would
            // see `document.text != openDocument.text`, call bootstrapReplica, blow away the
            // live replica + editor text, and the server-side replica (which already had the
            // user's ops applied) would then start rejecting fresh ops because they collided
            // on (siteId, clock). User-visible symptom: typing freezes, indicator goes
            // [<>] -> [!!] Stale, going back to the freeze position briefly shows [OK]
            // before re-typing repeats the rejection.
            //
            // Fix: while a CRDT session is bound to the same path, the local replica owns
            // the text. Stale workspace pushes update entries/target only.
            val opsGateway = FakeWorkbenchOpsGateway()
            val store =
                WorkbenchStore(
                    FakeWorkspaceGateway(),
                    FakeComputerControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    opsGateway,
                ) {
                    ru.lazyhat.compukterkraft.core.computer.workbench.crdt
                        .SiteId("p:test-merge")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("main.ck", "hello", 0))

            // User types " world" locally — replica is now ahead of the disk text.
            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.computer.workbench.LocalEdit
                    .Insert(5, " world"),
            )
            assertEquals("hello world", store.state.editor.text)
            testScheduler.advanceTimeBy(80)
            testScheduler.runCurrent()
            val sentBatchesBefore = opsGateway.batches.size

            // Server pushes the stale snapshot (e.g. ATTACH_TERMINAL reply: server read
            // `main.ck` from disk, in-flight ops not yet materialized).
            updates.push(document = ComputerWorkspaceDocument("main.ck", "hello", 0))

            // Editor must keep the locally-edited text.
            assertEquals("hello world", store.state.editor.text)
            // The replica must still be live: a new local edit must produce a new batch.
            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.computer.workbench.LocalEdit
                    .Insert(11, "!"),
            )
            testScheduler.advanceTimeBy(80)
            testScheduler.runCurrent()
            assertTrue(
                opsGateway.batches.size > sentBatchesBefore,
                "follow-up edit after stale workspace push must still flow through outbox",
            )
        }

    @Test
    fun openingDifferentDocumentResetsReplica() =
        runTest(UnconfinedTestDispatcher()) {
            val opsGateway = FakeWorkbenchOpsGateway()
            val store =
                WorkbenchStore(
                    FakeWorkspaceGateway(),
                    FakeComputerControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    opsGateway,
                ) {
                    ru.lazyhat.compukterkraft.core.computer.workbench.crdt
                        .SiteId("p:test-switch")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = ComputerWorkspaceDocument("a.ck", "alpha", 0))
            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.computer.workbench.LocalEdit
                    .Insert(5, "X"),
            )

            // Switch to a different file — must replace text and reset replica.
            updates.push(document = ComputerWorkspaceDocument("b.ck", "beta", 0))
            assertEquals("beta", store.state.editor.text)
            assertEquals("b.ck", store.state.openDocument?.path)
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
    }

    private class FakeComputerControlGateway : ComputerControlGateway {
        val calls = mutableListOf<String>()

        override fun reboot() {
            calls += "reboot"
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

    private class FakeWorkbenchOpsGateway : WorkbenchOpsGateway {
        val batches: MutableList<Pair<String, List<ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op>>> =
            mutableListOf()
        val sessionsOpened: MutableList<String> = mutableListOf()

        override fun sendOps(
            path: String,
            ops: List<ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op>,
        ) {
            batches += path to ops
        }

        override fun sessionOpen(path: String) {
            sessionsOpened += path
        }
    }
}

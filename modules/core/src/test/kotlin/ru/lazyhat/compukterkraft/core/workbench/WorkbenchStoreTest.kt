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
import ru.lazyhat.compukterkraft.lang.runtime.DefinitionTarget
import ru.lazyhat.compukterkraft.lang.runtime.DeviceIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.HoverInfo
import ru.lazyhat.compukterkraft.lang.runtime.TextEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchStoreTest {
    @Test
    fun terminalDockStartsHiddenAndCanBeToggled() =
        runTest(UnconfinedTestDispatcher()) {
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), FakeWorkbenchIdeFacade())
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
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), FakeWorkbenchIdeFacade())

            store.toggleTerminalVisibility()

            assertFalse(store.state.terminalVisible)
        }

    @Test
    fun terminalAutoHidesWhenComputerIsRemoved() =
        runTest(UnconfinedTestDispatcher()) {
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), FakeWorkbenchIdeFacade())
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
            val controlGateway = FakeTargetControlGateway()
            val store = WorkbenchStore(FakeWorkspaceGateway(), controlGateway, FakeWorkbenchIdeFacade())

            store.rebootComputer()

            assertEquals(listOf("reboot"), controlGateway.calls)
        }

    @Test
    fun opensCompletionWhenTypingIdentifierPrefix() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "", 0))

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
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "import", 0))
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
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "terminal", 0))
            store.moveCursorTo(0, 8, visibleEditorLines = 20)

            store.charTyped('.', visibleEditorLines = 20)

            assertEquals(listOf("completeFromLastAnalysis:0:9"), ideFacade.calls)
        }

    @Test
    fun completionAppliesAdditionalTextEditsBeforeMainInsert() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            ideFacade.nextManualCompletions =
                listOf(
                    CompletionItem(
                        label = "println",
                        detail = "terminal::println(text: String): Unit",
                        kind = CompletionItemKind.FUNCTION,
                        insertText = "println()",
                        cursorOffset = "println(".length,
                        sourceNamespace = "terminal",
                        additionalTextEdits = listOf(TextEdit(0, 0, "import terminal { println };\n")),
                    ),
                )
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "fun main() { pri }", 0))
            store.moveCursorTo(0, "fun main() { pri".length, visibleEditorLines = 20)

            store.openCompletion()
            store.applyCompletion()

            assertEquals("import terminal { println };\nfun main() { println() }", store.state.editor.text)
            assertEquals(1, store.state.editor.cursorLine)
            assertEquals("fun main() { println(".length, store.state.editor.cursorColumn)
        }

    @Test
    fun disablesTargetActionsWhenNoTargetIsConnected() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "fun main() {}", 0))

            assertTrue(!store.state.target.connected)
            assertTrue(!store.state.actions.canRun)
            assertTrue(!store.state.actions.canAttachTerminal)
        }

    @Test
    fun enablesTargetActionsWhenTargetDescriptorArrives() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(
                document = DeviceWorkspaceDocument("main.ck", "fun main() {}", 0),
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
            val controlGateway = FakeTargetControlGateway()
            val store = WorkbenchStore(FakeWorkspaceGateway(), controlGateway, ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(target = WorkbenchTargetState(connected = true, displayName = "Pocket Computer", familyId = "normal"))

            store.runTargetProgram()

            assertEquals(listOf("run"), controlGateway.calls)
        }

    @Test
    fun formatOpenDocumentAppliesFacadeEdits() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            ideFacade.nextFormatResult =
                ru.lazyhat.compukterkraft.lang.frontend.FormatResult(
                    listOf(TextEdit(0, "fun main(){println();}".length, "fun main() {\n    println();\n}\n")),
                )
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "fun main(){println();}", 0))

            store.formatOpenDocument(visibleEditorLines = 20)

            assertEquals("fun main() {\n    println();\n}\n", store.state.editor.text)
            assertEquals(listOf("formatDocument:main.ck"), ideFacade.calls.filter { it.startsWith("formatDocument") })
        }

    @Test
    fun cleanupOpenDocumentAppliesFacadeEdits() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            ideFacade.nextCleanupResult =
                ru.lazyhat.compukterkraft.lang.frontend.FormatResult(
                    listOf(
                        TextEdit(
                            0,
                            "import terminal { clear, println };\nfun main(){println();}".length,
                            "import terminal { println };\n\nfun main() {\n    println();\n}\n",
                        ),
                    ),
                )
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "import terminal { clear, println };\nfun main(){println();}", 0))

            store.cleanupOpenDocument(visibleEditorLines = 20)

            assertEquals("import terminal { println };\n\nfun main() {\n    println();\n}\n", store.state.editor.text)
            assertEquals(listOf("cleanupDocument:main.ck"), ideFacade.calls.filter { it.startsWith("cleanupDocument") })
        }

    @Test
    fun ctrlAltFTriggersFormatDocument() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            ideFacade.nextFormatResult =
                ru.lazyhat.compukterkraft.lang.frontend
                    .FormatResult(listOf(TextEdit(0, 1, "formatted")))
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "x", 0))

            assertTrue(store.keyPressed(KeyCodes.KEY_F, KeyCodes.MOD_CONTROL or KeyCodes.MOD_ALT, visibleEditorLines = 20))

            assertEquals("formatted", store.state.editor.text)
            assertTrue(ideFacade.calls.contains("formatDocument:main.ck"))
        }

    @Test
    fun ctrlAltLTriggersCleanupDocument() =
        runTest(UnconfinedTestDispatcher()) {
            val ideFacade = FakeWorkbenchIdeFacade()
            ideFacade.nextCleanupResult =
                ru.lazyhat.compukterkraft.lang.frontend
                    .FormatResult(listOf(TextEdit(0, 1, "cleaned")))
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), ideFacade)
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "x", 0))

            assertTrue(store.keyPressed(KeyCodes.KEY_L, KeyCodes.MOD_CONTROL or KeyCodes.MOD_ALT, visibleEditorLines = 20))

            assertEquals("cleaned", store.state.editor.text)
            assertTrue(ideFacade.calls.contains("cleanupDocument:main.ck"))
        }

    @Test
    fun escapeIsNotHandledByEditorStore() =
        runTest(UnconfinedTestDispatcher()) {
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), FakeWorkbenchIdeFacade())
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "fun main() {}", 0))

            assertFalse(store.keyPressed(KeyCodes.KEY_ESCAPE, modifiers = 0, visibleEditorLines = 20))
        }

    @Test
    fun capturesPrintableKeyDownBeforeCharTypedInEditorMode() =
        runTest(UnconfinedTestDispatcher()) {
            val store = WorkbenchStore(FakeWorkspaceGateway(), FakeTargetControlGateway(), FakeWorkbenchIdeFacade())
            val updates = FakeWorkbenchUpdateSource()

            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "", 0))

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
                    FakeTargetControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    opsGateway,
                ) {
                    ru.lazyhat.compukterkraft.core.workbench.crdt
                        .SiteId("p:test01")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "", 0))

            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.workbench.LocalEdit
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
                    FakeTargetControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    opsGateway,
                ) {
                    ru.lazyhat.compukterkraft.core.workbench.crdt
                        .SiteId("p:test02")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "", 0))

            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.workbench.LocalEdit
                    .Insert(0, "x"),
            )
            testScheduler.advanceTimeBy(80)
            testScheduler.runCurrent()
            // Insert of 1 char at clock 0 → highest clock acked is 0.
            store.applyAck(0)
            testScheduler.runCurrent()

            assertEquals(0, store.state.editor.pendingOpCount)
            assertEquals(
                ru.lazyhat.compukterkraft.core.workbench.sync.SyncStatus.Idle,
                store.state.editor.syncStatus,
            )
        }

    @Test
    fun applyRemoteOpsUpdatesEditorText() =
        runTest(UnconfinedTestDispatcher()) {
            val store =
                WorkbenchStore(
                    FakeWorkspaceGateway(),
                    FakeTargetControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    FakeWorkbenchOpsGateway(),
                ) {
                    ru.lazyhat.compukterkraft.core.workbench.crdt
                        .SiteId("p:test03")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "hello", 0))

            // Bootstrap atomized "hello" with site=ServerInit at clock 0..4. Last visible
            // atom = (ServerInit, 4). Append "!" via remote op.
            val lastAtom =
                store.replica!!
                    .document
                    .atomAtOffset(4)!!
                    .first
            val remoteOp =
                ru.lazyhat.compukterkraft.core.workbench.crdt.Op.Insert(
                    author =
                        ru.lazyhat.compukterkraft.core.workbench.crdt
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
            val controlGateway = FakeTargetControlGateway()
            val store =
                WorkbenchStore(
                    FakeWorkspaceGateway(),
                    controlGateway,
                    FakeWorkbenchIdeFacade(),
                    opsGateway,
                ) {
                    ru.lazyhat.compukterkraft.core.workbench.crdt
                        .SiteId("p:test04")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(
                document = DeviceWorkspaceDocument("main.ck", "", 0),
                target = WorkbenchTargetState(connected = true, displayName = "PC", familyId = "normal"),
            )

            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.workbench.LocalEdit
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
                    FakeTargetControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    FakeWorkbenchOpsGateway(),
                ) {
                    ru.lazyhat.compukterkraft.core.workbench.crdt
                        .SiteId("p:test05")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "world", 0))
            store.moveCursorTo(0, 5, visibleEditorLines = 20) // at end

            // Remote insert at the start of the document (leftId = null).
            val remoteOp =
                ru.lazyhat.compukterkraft.core.workbench.crdt.Op.Insert(
                    author =
                        ru.lazyhat.compukterkraft.core.workbench.crdt
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
    fun cursorStaysWhenRemoteInsertHappensExactlyAtCursor() =
        runTest(UnconfinedTestDispatcher()) {
            // Regression: when a peer inserts at exactly the local cursor offset (e.g. the
            // peer pressed Enter right where we were about to type), our caret must stay on
            // the original character — typing should land BEFORE the peer's newline, not get
            // teleported to the new line after it.
            val store =
                WorkbenchStore(
                    FakeWorkspaceGateway(),
                    FakeTargetControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    FakeWorkbenchOpsGateway(),
                ) {
                    ru.lazyhat.compukterkraft.core.workbench.crdt
                        .SiteId("p:test06")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "abc", 0))
            // Caret at offset 3 — end of "abc".
            store.moveCursorTo(0, 3, visibleEditorLines = 20)

            // Peer presses Enter at the same offset (their leftId = atom of last 'c').
            val lastAtom =
                store.replica!!
                    .document
                    .atomAtOffset(2)!!
                    .first
            val remoteOp =
                ru.lazyhat.compukterkraft.core.workbench.crdt.Op.Insert(
                    author =
                        ru.lazyhat.compukterkraft.core.workbench.crdt
                            .SiteId("p:other3"),
                    clock = 0,
                    leftId = lastAtom,
                    text = "\n",
                )

            store.applyRemoteOps(listOf(remoteOp))

            assertEquals("abc\n", store.state.editor.text)
            // Caret must remain at end of original line, NOT jump to start of the new one.
            assertEquals(0, store.state.editor.cursorLine)
            assertEquals(3, store.state.editor.cursorColumn)
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
                    FakeTargetControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    opsGateway,
                ) {
                    ru.lazyhat.compukterkraft.core.workbench.crdt
                        .SiteId("p:test-merge")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("main.ck", "hello", 0))

            // User types " world" locally — replica is now ahead of the disk text.
            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.workbench.LocalEdit
                    .Insert(5, " world"),
            )
            assertEquals("hello world", store.state.editor.text)
            testScheduler.advanceTimeBy(80)
            testScheduler.runCurrent()
            val sentBatchesBefore = opsGateway.batches.size

            // Server pushes the stale snapshot (e.g. ATTACH_TERMINAL reply: server read
            // `main.ck` from disk, in-flight ops not yet materialized).
            updates.push(document = DeviceWorkspaceDocument("main.ck", "hello", 0))

            // Editor must keep the locally-edited text.
            assertEquals("hello world", store.state.editor.text)
            // The replica must still be live: a new local edit must produce a new batch.
            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.workbench.LocalEdit
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
                    FakeTargetControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    opsGateway,
                ) {
                    ru.lazyhat.compukterkraft.core.workbench.crdt
                        .SiteId("p:test-switch")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("a.ck", "alpha", 0))
            store.applyLocalEdit(
                ru.lazyhat.compukterkraft.core.workbench.LocalEdit
                    .Insert(5, "X"),
            )

            // Switch to a different file — must replace text and reset replica.
            updates.push(document = DeviceWorkspaceDocument("b.ck", "beta", 0))
            assertEquals("beta", store.state.editor.text)
            assertEquals("b.ck", store.state.openDocument?.path)
        }

    @Test
    fun cursorMovementsAreReportedThroughOpsGateway() =
        runTest(UnconfinedTestDispatcher()) {
            val opsGateway = FakeWorkbenchOpsGateway()
            val store =
                WorkbenchStore(
                    FakeWorkspaceGateway(),
                    FakeTargetControlGateway(),
                    FakeWorkbenchIdeFacade(),
                    opsGateway,
                ) {
                    ru.lazyhat.compukterkraft.core.workbench.crdt
                        .SiteId("p:cursor-test")
                }
            val updates = FakeWorkbenchUpdateSource()
            store.bind(backgroundScope, updates)
            updates.push(document = DeviceWorkspaceDocument("foo.ck", "hello\nworld", 0))

            // Opening the document already reported the initial caret at (0,0).
            val initial = opsGateway.cursors.size
            assertTrue(initial >= 1, "opening a file must report the initial caret to the server")
            assertEquals("foo.ck", opsGateway.cursors.first().first)

            store.moveCursorTo(line = 1, column = 3, visibleEditorLines = 20)
            assertTrue(
                opsGateway.cursors.size > initial,
                "moveCursorTo must publish the new caret via the gateway",
            )
            val (path, anchor) = opsGateway.cursors.last()
            assertEquals("foo.ck", path)
            // The anchor must be a real CRDT pointer (not the leftmost fallback) — we have
            // a live replica at this point.
            assertTrue(
                anchor != null && anchor.atomId != null,
                "post-snapshot cursor reports must carry a CRDT atom anchor",
            )

            // Re-emitting the same line/col must NOT cause a duplicate report.
            val before = opsGateway.cursors.size
            store.moveCursorTo(line = 1, column = 3, visibleEditorLines = 20)
            assertEquals(before, opsGateway.cursors.size, "same caret must be deduplicated")
        }

    private class FakeWorkbenchUpdateSource : WorkbenchUpdateSource {
        private val _stateFlow = MutableStateFlow(WorkbenchRemoteState())
        override val stateFlow: StateFlow<WorkbenchRemoteState> = _stateFlow

        fun push(
            entries: List<ru.lazyhat.compukterkraft.lang.runtime.DeviceWorkspaceEntry> = emptyList(),
            document: DeviceWorkspaceDocument? = null,
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

    private class FakeTargetControlGateway : TargetControlGateway {
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

    private class FakeWorkbenchIdeFacade : WorkbenchIdeFacade {
        val calls = mutableListOf<String>()
        var nextManualCompletions: List<CompletionItem> =
            listOf(CompletionItem(label = "manual", detail = "", kind = CompletionItemKind.KEYWORD))
        var nextFormatResult: ru.lazyhat.compukterkraft.lang.frontend.FormatResult =
            ru.lazyhat.compukterkraft.lang.frontend
                .FormatResult(emptyList())
        var nextCleanupResult: ru.lazyhat.compukterkraft.lang.frontend.FormatResult =
            ru.lazyhat.compukterkraft.lang.frontend
                .FormatResult(emptyList())

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
        ): List<CompletionItem> = nextManualCompletions

        override fun completeFromLastAnalysis(
            path: String,
            source: String,
            line: Int,
            column: Int,
        ): List<CompletionItem> {
            calls += "completeFromLastAnalysis:$line:$column"
            return listOf(CompletionItem(label = "while", detail = "keyword", kind = CompletionItemKind.KEYWORD))
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

        override fun formatDocument(
            path: String,
            source: String,
        ): ru.lazyhat.compukterkraft.lang.frontend.FormatResult {
            calls += "formatDocument:$path"
            return nextFormatResult
        }

        override fun cleanupDocument(
            path: String,
            source: String,
        ): ru.lazyhat.compukterkraft.lang.frontend.FormatResult {
            calls += "cleanupDocument:$path"
            return nextCleanupResult
        }
    }

    private class FakeWorkbenchOpsGateway : WorkbenchOpsGateway {
        val batches: MutableList<Pair<String, List<ru.lazyhat.compukterkraft.core.workbench.crdt.Op>>> =
            mutableListOf()
        val sessionsOpened: MutableList<String> = mutableListOf()
        val cursors:
            MutableList<Pair<String, ru.lazyhat.compukterkraft.core.workbench.crdt.CursorAnchor?>> =
            mutableListOf()

        override fun sendOps(
            path: String,
            ops: List<ru.lazyhat.compukterkraft.core.workbench.crdt.Op>,
        ) {
            batches += path to ops
        }

        override fun sessionOpen(path: String) {
            sessionsOpened += path
        }

        override fun sendCursor(
            path: String,
            cursor: ru.lazyhat.compukterkraft.core.workbench.crdt.CursorAnchor?,
        ) {
            cursors += path to cursor
        }
    }
}

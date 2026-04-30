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
package ru.lazyhat.compukterkraft.common.workbench

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import ru.lazyhat.compukterkraft.common.workbench.context.ServerWorkbench
import ru.lazyhat.compukterkraft.core.computer.vm.ComputerWorkspaceHost
import ru.lazyhat.compukterkraft.core.workbench.TargetControlGateway
import ru.lazyhat.compukterkraft.core.workbench.LocalEdit
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchIdeFacade
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchOpsGateway
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchStore
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchTargetState
import ru.lazyhat.compukterkraft.core.workbench.WorkbenchUpdateSource
import ru.lazyhat.compukterkraft.core.workbench.WorkspaceGateway
import ru.lazyhat.compukterkraft.core.workbench.crdt.Op
import ru.lazyhat.compukterkraft.core.workbench.crdt.SiteId
import ru.lazyhat.compukterkraft.lang.runtime.CompletionItem
import ru.lazyhat.compukterkraft.lang.runtime.ComputerIdeSnapshot
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end smoke for Phase 1 of the workbench async-sync pipeline. We spin up an in-memory
 * [ServerWorkbench] and a client [WorkbenchStore] connected by a direct callback gateway that
 * mimics the network: ops produced locally are forwarded synchronously to the server via
 * `applyOps`, and the resulting ack is fed back via `applyAck`.
 *
 * Verifies that after 100 random local edits + `flushAndRun`, the server's flatten matches the
 * client's editor text and that RUN was issued only after the last ack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchSyncIntegrationTest {
    @Test
    fun clientAndServerConvergeAfterRandomEditsAndFlushAndRun() =
        runTest(StandardTestDispatcher()) {
            val workspaceRoot = createTempDirectory("workbench-sync-integration")
            val workspace = ComputerWorkspaceHost(workspaceRoot)
            val server = ServerWorkbench(workspaceId = 7, workspace = workspace)
            val path = "main.ck"
            server.write(path, "fun main() {}")

            val controlGateway = RecordingControlGateway()
            val opsGateway = DirectOpsGateway(server)
            val store =
                WorkbenchStore(
                    workspaceGateway = NoopWorkspaceGateway,
                    controlGateway = controlGateway,
                    ideFacade = NoopIdeFacade,
                    opsGateway = opsGateway,
                    siteIdProvider = { SiteId("p:integration") },
                )
            opsGateway.bindStore(store)
            val updates = MutableUpdateSource()
            updates.push(
                WorkbenchRemoteState(
                    document = ComputerWorkspaceDocument(path, "fun main() {}", version = 1),
                    target = WorkbenchTargetState(connected = true, displayName = "Pocket Dev", familyId = "advanced"),
                ),
            )

            store.bind(backgroundScope, updates)
            // Open the same document on the server so it has a replica seeded by ServerInit.
            assertTrue(server.openSession(path) != null)

            // Apply 100 random local edits.
            val random = Random(0xC0FFEE)
            repeat(100) {
                val text = store.state.editor.text
                if (text.isEmpty() || random.nextBoolean()) {
                    val offset = random.nextInt(text.length + 1)
                    val ch = ('a'..'z').random(random)
                    store.applyLocalEdit(LocalEdit.Insert(offset, ch.toString()))
                } else {
                    val offset = random.nextInt(text.length)
                    store.applyLocalEdit(LocalEdit.Delete(offset, length = 1))
                }
            }

            val flushed = store.flushAndRun(timeoutMs = 5_000L)

            assertTrue(flushed, "flushAndRun must succeed within timeout")
            val clientText = store.state.editor.text
            val serverText =
                server
                    .openSession(path)!!
                    .runs
                    .filterNot { it.deleted }
                    .joinToString("") { it.text }
            assertEquals(clientText, serverText, "client and server must converge")
            assertEquals(1, controlGateway.runCount, "RUN must fire exactly once")
            assertEquals(0, store.state.editor.pendingOpCount, "outbox must drain before RUN")
        }

    // -----------------------------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------------------------

    /**
     * Synchronous ops gateway: forwards every batch directly to the [server] and feeds the ack
     * back into the bound store, mimicking a zero-latency network.
     */
    private class DirectOpsGateway(
        private val server: ServerWorkbench,
    ) : WorkbenchOpsGateway {
        private var store: WorkbenchStore? = null

        fun bindStore(store: WorkbenchStore) {
            this.store = store
        }

        override fun sendOps(
            path: String,
            ops: List<Op>,
        ) {
            val sender = ops.firstOrNull()?.author ?: return
            val result = server.applyOps(path, ops, sender) ?: return
            store?.applyAck(result.ackedClock)
        }

        override fun sessionOpen(path: String) {
            server.openSession(path)
        }

        override fun sendCursor(
            path: String,
            cursor: ru.lazyhat.compukterkraft.core.workbench.crdt.CursorAnchor?,
        ) {
            // The integration test is concerned with op apply/ack only; cursor relay is
            // exercised separately. Drop on the floor.
        }
    }

    private object NoopWorkspaceGateway : WorkspaceGateway {
        override fun list(path: String) = Unit

        override fun read(path: String) = Unit
    }

    private class RecordingControlGateway : TargetControlGateway {
        var runCount = 0
            private set

        override fun reboot() = Unit

        override fun runTargetProgram() {
            runCount += 1
        }

        override fun attachTargetTerminal() = Unit
    }

    private object NoopIdeFacade : WorkbenchIdeFacade {
        override fun analyze(
            path: String,
            source: String,
        ): ComputerIdeSnapshot =
            ComputerIdeSnapshot(
                document = ComputerWorkspaceDocument(path, source, version = 0),
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
        ) = null

        override fun definition(
            path: String,
            source: String,
            line: Int,
            column: Int,
        ) = null
    }

    private class MutableUpdateSource : WorkbenchUpdateSource {
        private val flow = MutableStateFlow(WorkbenchRemoteState())
        override val stateFlow: StateFlow<WorkbenchRemoteState> = flow

        fun push(state: WorkbenchRemoteState) {
            flow.value = state
        }
    }
}

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

package ru.lazyhat.compukterkraft.common.workbench.context

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import ru.lazyhat.compukterkraft.common.utils.computerFamilyId
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.updateComputerDataTag
import ru.lazyhat.compukterkraft.common.workbench.test.TestMinecraftBootstrap
import ru.lazyhat.compukterkraft.core.computer.vm.ComputerWorkspaceHost
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBuffer
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerWorkbenchTest {
    @Test
    fun extractsTargetDescriptorFromComputerItem() {
        TestMinecraftBootstrap.ensureInitialized()

        val root = createTempDirectory("server-workbench-target")
        val workspace = ComputerWorkspaceHost(root)
        val stack =
            ItemStack(Items.STONE).apply {
                updateComputerDataTag {
                    computerID = 73
                    computerFamilyId = "advanced"
                }
                set(DataComponents.CUSTOM_NAME, Component.literal("Pocket Dev"))
            }

        val workbench = ServerWorkbench(workspaceId = 11, workspace = workspace)
        workbench.setTarget(stack)

        assertEquals(73, workbench.targetDescriptor().computerId)
        assertEquals("Pocket Dev", workbench.targetState().displayName)
        assertEquals("advanced", workbench.targetState().familyId)
        assertTrue(workbench.targetState().connected)
    }

    @Test
    fun targetWithoutComputerIdStaysDisconnected() {
        TestMinecraftBootstrap.ensureInitialized()

        val stack =
            ItemStack(Items.STONE).apply {
                updateComputerDataTag {
                    computerFamilyId = "advanced"
                }
                set(DataComponents.CUSTOM_NAME, Component.literal("Unbound Pocket"))
            }

        val workbench =
            ServerWorkbench(workspaceId = 11, workspace = ComputerWorkspaceHost(createTempDirectory("server-workbench-unbound")))
        workbench.setTarget(stack)

        assertEquals("Unbound Pocket", workbench.targetState().displayName)
        assertEquals("advanced", workbench.targetState().familyId)
        assertFalse(workbench.targetState().connected)
    }

    @Test
    fun keepsAuthoringWorkspaceSeparateByWorkbenchId() {
        val root = createTempDirectory("server-workbench-workspace")
        val workspace = ComputerWorkspaceHost(root)
        val workbench = ServerWorkbench(workspaceId = 41, workspace = workspace)

        workbench.write("main.ck", "fun main() {}")

        val localDocument = workbench.read("main.ck")
        val unrelatedDocument = workspace.readDocument(7, "main.ck")

        assertNotNull(localDocument)
        assertEquals("fun main() {}", localDocument.text)
        assertEquals(emptyList(), workspace.list(7, ""))
        assertEquals(null, unrelatedDocument)
    }

    @Test
    fun runAndAttachDelegateToRuntimeBridge() {
        val workbench =
            ServerWorkbench(
                workspaceId = 17,
                workspace = ComputerWorkspaceHost(createTempDirectory("server-workbench-runtime")),
                initialTarget = ServerWorkbench.TargetDescriptor(computerId = 5, displayName = "Pocket Dev", familyId = "advanced"),
            )
        val bridge = FakeRuntimeBridge()
        workbench.bindRuntimeBridge(bridge)

        workbench.runTargetProgram()
        workbench.attachTerminal()
        workbench.handleInput(
            ru.lazyhat.compukterkraft.core.computer.input.KeyInputEvent
                .Down(28, repeat = false),
        )

        assertEquals(listOf("run:5", "attach:5", "event:key"), bridge.calls)
        assertNotNull(workbench.currentScreenSnapshot())
    }

    @Test
    fun rebootDelegatesToRuntimeBridge() {
        val workbench =
            ServerWorkbench(
                workspaceId = 17,
                workspace = ComputerWorkspaceHost(createTempDirectory("server-workbench-reboot")),
                initialTarget = ServerWorkbench.TargetDescriptor(computerId = 5, displayName = "Pocket Dev", familyId = "advanced"),
            )
        val bridge = FakeRuntimeBridge()
        workbench.bindRuntimeBridge(bridge)

        workbench.rebootTarget()

        assertEquals(listOf("reboot:5"), bridge.calls)
    }

    @Test
    fun applyOpsAcksHighestAppliedClockRegardlessOfSenderMatch() {
        // Regression: previously applyOps looked up `result.ackedClockBySite[sender]` keyed
        // by the request sender. If the client's siteId disagreed with the server-derived
        // sender (e.g. siteIdProvider used random UUID while server used player.uuid), the
        // lookup returned -1 and the client outbox stalled in Syncing -> Stale forever.
        //
        // The fix: the server returns max(applied clocks) — every batch carries ops from one
        // player, so max-applied-clock is the right ack for that client regardless of sender
        // identity.
        val workspace = ComputerWorkspaceHost(createTempDirectory("server-workbench-ack"))
        val workbench = ServerWorkbench(workspaceId = 100, workspace = workspace)
        workbench.write("main.ck", "")
        workbench.openSession("main.ck")

        val author = ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId("p:client01")
        val sender = ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId("p:server02") // intentionally DIFFERENT
        val ops = listOf(
            ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op.Insert(
                author = author,
                clock = 0,
                leftId = null,
                text = "hello",
            ),
        )

        val result = workbench.applyOps("main.ck", ops, sender)

        assertNotNull(result)
        // "hello" -> highest = clock(0) + length(5) - 1 = 4
        assertEquals(4, result.ackedClock, "ack must reflect the highest applied clock, not the sender match")
    }

    @Test
    fun snapshotEagerlyOpensCrdtSessionForResolvedDocument() {
        // Regression: when the menu is first opened, refreshFromServerWorkbench() calls
        // ServerWorkbench.snapshot(null), which auto-picks the first non-directory file as
        // the displayed document. The client unconditionally bootstraps a local CRDT replica
        // from `document.text`. If the server doesn't have a matching CRDT session, the
        // first ops batch the user types is dropped (handleOpsRequest returns null because
        // replicas[path] is empty) and the outbox stalls in Syncing -> Stale forever.
        //
        // The fix: snapshot() opens a CRDT session for the resolved document path so the
        // server-side invariant "if a document is surfaced to the client, a session is open
        // for it" always holds.
        val workspace = ComputerWorkspaceHost(createTempDirectory("server-workbench-snapshot-opens"))
        val workbench = ServerWorkbench(workspaceId = 200, workspace = workspace)
        workbench.write("bios.ck", "print(\"hello\")")

        // Auto-resolve path (mimics initial menu open).
        val state = workbench.snapshot(null)
        val document = state.document
        assertNotNull(document)
        assertEquals("bios.ck", document.path)

        // The session must already be open — applyOps for that path must succeed.
        val author = ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId("p:client01")
        val ops = listOf(
            ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op.Insert(
                author = author,
                clock = 0,
                leftId = null,
                text = "X",
            ),
        )
        val result = workbench.applyOps("bios.ck", ops, author)
        assertNotNull(result, "snapshot() must eagerly open CRDT session so first ops are not dropped")
        assertEquals(0, result.ackedClock)
    }

    private class FakeRuntimeBridge : WorkbenchTargetRuntimeBridge {
        val calls = mutableListOf<String>()
        private val snapshot = ScreenBuffer(16, 8, true).forceSnapshot()

        override fun rebootTarget(target: ServerWorkbench.TargetDescriptor) {
            calls += "reboot:${target.computerId}"
        }

        override fun runTargetProgram(target: ServerWorkbench.TargetDescriptor) {
            calls += "run:${target.computerId}"
        }

        override fun attachTerminal(target: ServerWorkbench.TargetDescriptor) {
            calls += "attach:${target.computerId}"
        }

        override fun queueEvent(
            target: ServerWorkbench.TargetDescriptor,
            event: String,
            arguments: Array<Any>,
        ): Boolean {
            calls += "event:$event"
            return true
        }

        override fun currentScreenSnapshot(target: ServerWorkbench.TargetDescriptor) = snapshot
    }
}

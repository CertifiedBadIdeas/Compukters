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
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.deviceFamilyId
import ru.lazyhat.compukterkraft.common.utils.updateComputerDataTag
import ru.lazyhat.compukterkraft.common.workbench.test.TestMinecraftBootstrap
import ru.lazyhat.compukterkraft.core.device.vm.DeviceWorkspaceHost
import ru.lazyhat.compukterkraft.core.workbench.EditorPresence
import ru.lazyhat.compukterkraft.core.workbench.crdt.AtomId
import ru.lazyhat.compukterkraft.core.workbench.crdt.CursorAnchor
import ru.lazyhat.compukterkraft.core.workbench.crdt.SiteId
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
        val workspace = DeviceWorkspaceHost(root)
        val stack =
            ItemStack(Items.STONE).apply {
                updateComputerDataTag {
                    computerID = 73
                    deviceFamilyId = "advanced"
                }
                set(DataComponents.CUSTOM_NAME, Component.literal("Pocket Dev"))
            }

        val workbench = ServerWorkbench(workspaceId = 11, workspace = workspace)
        workbench.setTarget(stack)

        assertEquals(73, workbench.targetDescriptor().deviceId)
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
                    deviceFamilyId = "advanced"
                }
                set(DataComponents.CUSTOM_NAME, Component.literal("Unbound Pocket"))
            }

        val workbench =
            ServerWorkbench(workspaceId = 11, workspace = DeviceWorkspaceHost(createTempDirectory("server-workbench-unbound")))
        workbench.setTarget(stack)

        assertEquals("Unbound Pocket", workbench.targetState().displayName)
        assertEquals("advanced", workbench.targetState().familyId)
        assertFalse(workbench.targetState().connected)
    }

    @Test
    fun keepsAuthoringWorkspaceSeparateByWorkbenchId() {
        val root = createTempDirectory("server-workbench-workspace")
        val workspace = DeviceWorkspaceHost(root)
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
                workspace = DeviceWorkspaceHost(createTempDirectory("server-workbench-runtime")),
                initialTarget = ServerWorkbench.TargetDescriptor(deviceId = 5, displayName = "Pocket Dev", familyId = "advanced"),
            )
        val bridge = FakeRuntimeBridge()
        workbench.bindRuntimeBridge(bridge)

        workbench.runTargetProgram()
        workbench.handleInput(
            ru.lazyhat.compukterkraft.core.device.input.KeyInputEvent
                .Down(28, repeat = false),
        )

        workbench.attachTerminal()

        assertEquals(listOf("run:5", "event:key"), bridge.calls)
    }

    @Test
    fun rebootDelegatesToRuntimeBridge() {
        val workbench =
            ServerWorkbench(
                workspaceId = 17,
                workspace = DeviceWorkspaceHost(createTempDirectory("server-workbench-reboot")),
                initialTarget = ServerWorkbench.TargetDescriptor(deviceId = 5, displayName = "Pocket Dev", familyId = "advanced"),
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
        val workspace = DeviceWorkspaceHost(createTempDirectory("server-workbench-ack"))
        val workbench = ServerWorkbench(workspaceId = 100, workspace = workspace)
        workbench.write("main.ck", "")
        workbench.openSession("main.ck")

        val author =
            ru.lazyhat.compukterkraft.core.workbench.crdt
                .SiteId("p:client01")
        val sender =
            ru.lazyhat.compukterkraft.core.workbench.crdt
                .SiteId("p:server02") // intentionally DIFFERENT
        val ops =
            listOf(
                ru.lazyhat.compukterkraft.core.workbench.crdt.Op.Insert(
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
        TestMinecraftBootstrap.ensureInitialized()
        val workspace = DeviceWorkspaceHost(createTempDirectory("server-workbench-snapshot-opens"))
        val workbench = ServerWorkbench(workspaceId = 200, workspace = workspace)
        // Bind a target — without one snapshot() returns empty (files are computer-bound).
        workbench.setTarget(
            ItemStack(Items.STONE).apply {
                updateComputerDataTag {
                    computerID = 42
                    deviceFamilyId = "advanced"
                }
            },
        )
        workbench.write("bios.ck", "print(\"hello\")")

        // Auto-resolve path (mimics initial menu open).
        val state = workbench.snapshot(null)
        val document = state.document
        assertNotNull(document)
        assertEquals("bios.ck", document.path)

        // The session must already be open — applyOps for that path must succeed.
        val author =
            ru.lazyhat.compukterkraft.core.workbench.crdt
                .SiteId("p:client01")
        val ops =
            listOf(
                ru.lazyhat.compukterkraft.core.workbench.crdt.Op.Insert(
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

    @Test
    fun snapshotAfterTargetSwitchExposesNewComputerFiles() {
        // Regression: switching the bound computer in the workbench used to keep showing
        // the previous computer's file in the IDE editor until the user manually toggled
        // file selection. Root cause: the menu refreshed snapshot() with the cached old
        // document path. With the fix, the menu calls snapshot(null) on a target change so
        // ServerWorkbench picks the first file of the NEW computer's workspace.
        TestMinecraftBootstrap.ensureInitialized()
        val workspace = DeviceWorkspaceHost(createTempDirectory("server-workbench-target-switch"))
        // Two computers, each with its own file at the same path.
        workspace.writeDocument(11, "shell.ck", "old-content")
        workspace.writeDocument(22, "boot.ck", "new-content")

        val workbench = ServerWorkbench(workspaceId = 999, workspace = workspace)
        val first =
            ItemStack(Items.STONE).apply {
                updateComputerDataTag {
                    computerID = 11
                    deviceFamilyId = "advanced"
                }
            }
        val second =
            ItemStack(Items.STONE).apply {
                updateComputerDataTag {
                    computerID = 22
                    deviceFamilyId = "advanced"
                }
            }

        workbench.setTarget(first)
        val before = workbench.snapshot(null)
        assertEquals("shell.ck", before.document?.path)
        assertEquals("old-content", before.document?.text)

        workbench.setTarget(second)
        // Caller (menu) passes null on computer change — must surface NEW computer's file.
        val after = workbench.snapshot(null)
        assertEquals("boot.ck", after.document?.path)
        assertEquals("new-content", after.document?.text)
    }

    @Test
    fun snapshotWithoutBoundComputerReturnsEmptyWorkspace() {
        // Files are bound to the computer's workspace. When no computer is in the workbench
        // slot the IDE must show an empty file list — even if the workbench's own scratch
        // sandbox (workspaceId) happens to contain leftover files. Otherwise the user sees
        // unrelated files appear out of nowhere when they remove a computer.
        TestMinecraftBootstrap.ensureInitialized()
        val workspace = DeviceWorkspaceHost(createTempDirectory("server-workbench-empty-target"))
        // Pre-seed the workbench's own scratch workspace AND a computer's workspace.
        workspace.writeDocument(999, "scratch.ck", "leftover")
        workspace.writeDocument(33, "shell.ck", "computer-only-file")

        val workbench = ServerWorkbench(workspaceId = 999, workspace = workspace)
        val computerStack =
            ItemStack(Items.STONE).apply {
                updateComputerDataTag {
                    computerID = 33
                    deviceFamilyId = "advanced"
                }
            }

        // With computer: shows computer's files.
        workbench.setTarget(computerStack)
        assertEquals("shell.ck", workbench.snapshot(null).document?.path)

        // Without computer: empty regardless of scratch contents.
        workbench.clearTarget()
        val cleared = workbench.snapshot(null)
        assertEquals(emptyList(), cleared.entries)
        assertEquals(null, cleared.document)
        assertFalse(cleared.target.connected)
    }

    @Test
    fun applyOpsExposesAppliedOpsForFanOut() {
        // Phase 2: subscribers (peer menus) need the list of applied ops to relay to other
        // collaborators. Rejected ops must NOT appear there — relaying a rejected op would
        // diverge peers from the server replica.
        val workspace = DeviceWorkspaceHost(createTempDirectory("server-workbench-applied"))
        val workbench = ServerWorkbench(workspaceId = 100, workspace = workspace)
        workbench.write("shell.ck", "")
        workbench.openSession("shell.ck")

        val author =
            ru.lazyhat.compukterkraft.core.workbench.crdt
                .SiteId("p:client01")
        val good =
            ru.lazyhat.compukterkraft.core.workbench.crdt.Op.Insert(
                author = author,
                clock = 0,
                leftId = null,
                text = "hi",
            )
        // Causally invalid: targets an atom the replica has never seen.
        val bad =
            ru.lazyhat.compukterkraft.core.workbench.crdt.Op.Delete(
                author = author,
                clock = 1,
                targetId =
                    ru.lazyhat.compukterkraft.core.workbench.crdt.AtomId(
                        ru.lazyhat.compukterkraft.core.workbench.crdt
                            .SiteId("p:ghost"),
                        99,
                    ),
                length = 1,
            )

        val result = workbench.applyOps("shell.ck", listOf(good, bad), author)
        assertNotNull(result)
        assertEquals(listOf(good), result.applied)
        assertTrue(result.rejectedAny)
    }

    @Test
    fun subscribeAndUnsubscribeAreScopedPerPath() {
        val workspace = DeviceWorkspaceHost(createTempDirectory("server-workbench-subscribe"))
        val workbench = ServerWorkbench(workspaceId = 100, workspace = workspace)

        val received = mutableListOf<Pair<String, Int>>()
        val subA = ServerWorkbench.SessionSubscriber { p, ops -> received += "A" + p to ops.size }
        val subB = ServerWorkbench.SessionSubscriber { p, ops -> received += "B" + p to ops.size }

        workbench.subscribe("shell.ck", subA)
        workbench.subscribe("shell.ck", subB)
        workbench.subscribe("util.ck", subA)

        // Idempotent — duplicate subscribe must not double the subscriber.
        workbench.subscribe("shell.ck", subA)

        assertEquals(setOf(subA, subB), workbench.subscribersOf("shell.ck").toSet())
        assertEquals(listOf(subA), workbench.subscribersOf("util.ck"))

        workbench.unsubscribe("shell.ck", subA)
        assertEquals(listOf(subB), workbench.subscribersOf("shell.ck"))
        assertEquals(listOf(subA), workbench.subscribersOf("util.ck"), "unsubscribe must be path-scoped")

        workbench.unsubscribeAll(subA)
        assertEquals(listOf(subB), workbench.subscribersOf("shell.ck"))
        assertEquals(emptyList(), workbench.subscribersOf("util.ck"))
    }

    @Test
    fun setPresenceBroadcastsSnapshotToAttachedMenus() {
        val workspace = DeviceWorkspaceHost(createTempDirectory("server-workbench-presence"))
        val workbench = ServerWorkbench(workspaceId = 101, workspace = workspace)

        val received = mutableListOf<List<EditorPresence>>()
        val observer = ServerWorkbench.MenuObserver { snapshot -> received += snapshot }
        workbench.attachMenu(observer)
        // Attach must replay the (empty) current snapshot so a freshly opened menu starts in
        // a consistent state.
        assertEquals(listOf(emptyList()), received)
        received.clear()

        val alice = EditorPresence(SiteId("p:alice"), "alice", "shell.ck", cursor = null)
        val bob = EditorPresence(SiteId("p:bob"), "bob", "util.ck", cursor = null)

        assertTrue(workbench.setPresence(alice))
        assertTrue(workbench.setPresence(bob))
        assertEquals(2, received.size)
        assertEquals(setOf(alice), received[0].toSet())
        assertEquals(setOf(alice, bob), received[1].toSet())

        // Re-setting an identical presence must be a no-op (broadcast skipped).
        assertFalse(workbench.setPresence(alice))
        assertEquals(2, received.size)

        // Cursor change for an existing site counts as a real change.
        val aliceWithCursor = alice.copy(cursor = CursorAnchor(AtomId(SiteId("p:alice"), 5), 0))
        assertTrue(workbench.setPresence(aliceWithCursor))
        assertEquals(setOf(aliceWithCursor, bob), received.last().toSet())

        workbench.removePresence(SiteId("p:bob"))
        assertEquals(setOf(aliceWithCursor), received.last().toSet())

        workbench.detachMenu(observer)
        val sizeBeforeQuiet = received.size
        workbench.setPresence(EditorPresence(SiteId("p:carol"), "carol", "x.ck"))
        assertEquals(sizeBeforeQuiet, received.size, "detached observer must not receive broadcasts")
    }

    private class FakeRuntimeBridge : WorkbenchTargetRuntimeBridge {
        val calls = mutableListOf<String>()

        override fun rebootTarget(target: ServerWorkbench.TargetDescriptor) {
            calls += "reboot:${target.deviceId}"
        }

        override fun runTargetProgram(target: ServerWorkbench.TargetDescriptor) {
            calls += "run:${target.deviceId}"
        }

        override fun attachTerminal(target: ServerWorkbench.TargetDescriptor) {
            calls += "attach:${target.deviceId}"
        }

        override fun queueEvent(
            target: ServerWorkbench.TargetDescriptor,
            event: String,
            arguments: Array<Any>,
        ): Boolean {
            calls += "event:$event"
            return true
        }

    }
}

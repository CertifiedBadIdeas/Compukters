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

package ru.lazyhat.compukterkraft.common.workbench.menu

import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import ru.lazyhat.compukterkraft.common.network.ServerNetworking
import ru.lazyhat.compukterkraft.common.workbench.context.ServerWorkbench
import ru.lazyhat.compukterkraft.common.workbench.data.WorkbenchContainerData
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchDocumentSnapshotClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.client.WorkbenchOpsClientMessage
import ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchWorkspaceServerMessage
import ru.lazyhat.compukterkraft.core.computer.input.InputEvent
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchStore
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.CrdtDocument
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.TextRun
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import java.util.concurrent.ConcurrentHashMap

abstract class AbstractWorkbenchMenu(
    menuType: MenuType<*>,
    containerId: Int,
    protected val containerData: WorkbenchContainerData,
    protected val serverWorkbench: ServerWorkbench? = null,
    /**
     * Server-side: the player who opened this menu. Used as the destination when fanning out
     * relayed ops/cursor updates from other collaborators. `null` on client-side menus and on
     * server-side menus that have no owning player (synthetic / test scenarios).
     */
    private val ownerPlayer: ServerPlayer? = null,
) : AbstractContainerMenu(menuType, containerId),
    ServerWorkbench.SessionSubscriber {
    private val _workspaceStateFlow = MutableStateFlow(containerData.toRemoteState())
    private val _screenSnapshot = MutableStateFlow<ScreenBufferSnapshot?>(null)

    /**
     * Paths this menu has opened a CRDT session for. Tracked so [removed] can unsubscribe from
     * exactly the sessions we registered on — [ServerWorkbench.unsubscribeAll] would also work
     * but iterating a per-menu set avoids touching unrelated path entries.
     */
    private val subscribedPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val workspaceStateFlow: StateFlow<WorkbenchRemoteState> = _workspaceStateFlow.asStateFlow()

    val screenSnapshot: ScreenBufferSnapshot? get() = _screenSnapshot.value

    /**
     * The client-side editor store registers itself here so server→client CRDT messages
     * (ops/acks/snapshots) can be routed to the right replica. Server-side menus leave this
     * `null`. Cleared on screen close to avoid leaks.
     */
    var workbenchStore: WorkbenchStore? = null

    init {
        refreshFromServerWorkbench()
        updateScreenSnapshot(serverWorkbench?.currentScreenSnapshot())
    }

    fun refreshFromServerWorkbench(openDocumentPath: String? = _workspaceStateFlow.value.document?.path) {
        val workbench = serverWorkbench ?: return
        _workspaceStateFlow.value = workbench.snapshot(openDocumentPath)
    }

    fun updateRemoteState(remoteState: WorkbenchRemoteState) {
        _workspaceStateFlow.value = remoteState
    }

    fun updateScreenSnapshot(snapshot: ScreenBufferSnapshot?) {
        _screenSnapshot.value = snapshot
    }

    fun serverWorkbenchIdentity(): ServerWorkbench? = serverWorkbench

    fun handleInputEvent(event: InputEvent) {
        val workbench = serverWorkbench ?: return
        workbench.handleInput(event)
        updateScreenSnapshot(workbench.currentScreenSnapshot())
    }

    fun handleWorkspaceAction(
        action: WorkbenchWorkspaceServerMessage.Action,
        path: String,
    ): WorkbenchRemoteState? {
        val workbench = serverWorkbench ?: return null
        return when (action) {
            WorkbenchWorkspaceServerMessage.Action.LIST -> {
                workbench.snapshot(_workspaceStateFlow.value.document?.path)
            }

            WorkbenchWorkspaceServerMessage.Action.READ -> {
                workbench.snapshot(path)
            }

            WorkbenchWorkspaceServerMessage.Action.RUN -> {
                workbench.runTargetProgram()
                workbench.snapshot(_workspaceStateFlow.value.document?.path)
            }

            WorkbenchWorkspaceServerMessage.Action.REBOOT -> {
                workbench.rebootTarget()
                workbench.snapshot(_workspaceStateFlow.value.document?.path)
            }

            WorkbenchWorkspaceServerMessage.Action.ATTACH_TERMINAL -> {
                workbench.attachTerminal()
                workbench.snapshot(_workspaceStateFlow.value.document?.path)
            }
        }.also(::updateRemoteState)
    }

    override fun removed(player: Player) {
        // Materialize any open CRDT sessions back to disk so unflushed edits survive when the
        // player closes the workbench. The replicas die with the menu; this is the last
        // chance to persist them.
        serverWorkbench?.materializeOpenSessions()
        // Drop our subscriber registration on every path we joined, otherwise ServerWorkbench
        // would keep relaying ops to a defunct menu (and eventually leak references).
        val workbench = serverWorkbench
        if (workbench != null && subscribedPaths.isNotEmpty()) {
            workbench.unsubscribeAll(this)
            subscribedPaths.clear()
        }
        super.removed(player)
    }

    override fun stillValid(player: Player): Boolean = true

    /**
     * Open a CRDT session for [path] and produce the wire snapshot the client needs to
     * rebuild its replica. Returns `null` when the document does not exist or no
     * [serverWorkbench] is bound (client-side menu).
     */
    fun openWorkbenchSession(path: String): WorkbenchDocumentSnapshotClientMessage? {
        val workbench = serverWorkbench ?: return null
        val snapshot = workbench.openSession(path) ?: return null
        // Register for op fan-out from other collaborators on this same path. Idempotent on the
        // server side, so re-opening (e.g. after target rebind) is safe.
        if (subscribedPaths.add(path)) {
            workbench.subscribe(path, this)
        }
        return WorkbenchDocumentSnapshotClientMessage(
            containerId = containerId,
            path = snapshot.path,
            runs = snapshot.runs,
            versionVector = snapshot.versionVector,
        )
    }

    /**
     * Apply [ops] from [sender] to the open session at [path]; produce the per-sender ack the
     * server replies with. If no session is open at [path] (the client never sent READ /
     * the menu was reopened), returns `null` so the caller can re-open and re-snapshot.
     */
    fun handleOpsRequest(
        path: String,
        ops: List<Op>,
        sender: SiteId,
    ): WorkbenchOpsClientMessage? {
        val workbench = serverWorkbench ?: return null
        val result = workbench.applyOps(path, ops, sender) ?: return null
        // Fan out applied ops to every OTHER subscriber on this path so collaborators see
        // edits immediately. The sender's own client doesn't need its ops back — it already
        // applied them locally and only needs the ack.
        if (result.applied.isNotEmpty()) {
            for (peer in workbench.subscribersOf(path)) {
                if (peer === this) continue
                peer.onRemoteOps(path, result.applied)
            }
        }
        return WorkbenchOpsClientMessage(
            containerId = containerId,
            path = path,
            ops = emptyList(),
            ackedClock = result.ackedClock,
        )
    }

    /**
     * Forward [ops] applied by another collaborator to the owning player. Phase 2: implemented
     * via [ServerNetworking] so the client store can apply the relayed ops. The relay message
     * carries `ackedClock = NO_ACK_SENTINEL`, which the client's [applyOpsAck] interprets as
     * "ops only, no ack to consume".
     */
    override fun onRemoteOps(
        path: String,
        ops: List<Op>,
    ) {
        val player = ownerPlayer ?: return
        if (ops.isEmpty()) return
        ServerNetworking.sendToPlayer(
            WorkbenchOpsClientMessage(
                containerId = containerId,
                path = path,
                ops = ops,
                ackedClock = NO_ACK_SENTINEL,
            ),
            player,
        )
    }

    /**
     * Route a server-bound ops/ack reply to the registered client [workbenchStore]. Silently
     * drops when no store is bound (e.g. snapshot arrived before screen finished initializing).
     */
    fun applyOpsAck(
        path: String,
        ops: List<Op>,
        ackedClock: Int,
    ) {
        val store = workbenchStore ?: return
        if (ops.isNotEmpty()) store.applyRemoteOps(ops)
        // NO_ACK_SENTINEL marks a relay-only message (peer ops, no ack to consume). The store's
        // own outbox will get its real ack via the sender-targeted reply.
        if (ackedClock != NO_ACK_SENTINEL) store.applyAck(ackedClock)
    }

    /**
     * Rebuild the client replica from a server snapshot. Called when a session opens or after
     * a desync recovery.
     */
    fun applyDocumentSnapshot(
        path: String,
        runs: List<TextRun>,
        versionVector: Map<SiteId, Int>,
    ) {
        val store = workbenchStore ?: return
        val document = CrdtDocument(runs.toPersistentList(), versionVector.toPersistentMap())
        store.onSnapshot(path, document)
    }

    companion object {
        /**
         * Wire-level sentinel for [WorkbenchOpsClientMessage.ackedClock] meaning "this message
         * is a peer-broadcast relay, the recipient has no ack to consume from it". Picked as
         * `Int.MIN_VALUE` because real CRDT clocks start at 0 and only grow, so the sentinel
         * can never collide with a legitimate ack value.
         */
        const val NO_ACK_SENTINEL: Int = Int.MIN_VALUE
    }
}

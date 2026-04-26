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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
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
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

abstract class AbstractWorkbenchMenu(
    menuType: MenuType<*>,
    containerId: Int,
    protected val containerData: WorkbenchContainerData,
    protected val serverWorkbench: ServerWorkbench? = null,
) : AbstractContainerMenu(menuType, containerId) {
    private val _workspaceStateFlow = MutableStateFlow(containerData.toRemoteState())
    private val _screenSnapshot = MutableStateFlow<ScreenBufferSnapshot?>(null)

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
        return WorkbenchOpsClientMessage(
            containerId = containerId,
            path = path,
            ops = emptyList(), // Phase 1: no peer-broadcast yet; Phase 2 fans out to other clients
            ackedClock = result.ackedClock,
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
        store.applyAck(ackedClock)
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
}

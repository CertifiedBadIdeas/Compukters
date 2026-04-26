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

import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.common.computer.item.AbstractComputerItem
import ru.lazyhat.compukterkraft.common.computer.item.ComputerItem
import ru.lazyhat.compukterkraft.common.utils.computerDataTagCopy
import ru.lazyhat.compukterkraft.common.utils.computerFamilyId
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import ru.lazyhat.compukterkraft.core.computer.ComputerEvents
import ru.lazyhat.compukterkraft.core.computer.input.InputEvent
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchTargetState
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.CrdtApplyResult
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.CrdtDocument
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.Op
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.ServerCrdtReplica
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.SiteId
import ru.lazyhat.compukterkraft.core.computer.workbench.crdt.TextRun
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot
import java.util.concurrent.ConcurrentHashMap

class ServerWorkbench(
    val workspaceId: Int,
    private val workspace: ComputerWorkspace,
    initialTarget: TargetDescriptor = TargetDescriptor(),
) : ComputerEvents.Receiver {
    private var targetDescriptor: TargetDescriptor = initialTarget
    private var runtimeBridge: WorkbenchTargetRuntimeBridge = WorkbenchTargetRuntimeBridge.None

    /**
     * Workspace the workbench reads from and writes to. When a target computer is paired we
     * route IO directly into the target's workspace — that way edits land where the target
     * VM actually loads them, with no separate mirror step. If no computer is paired we fall
     * back to the workbench's own allocated [workspaceId] (a scratch sandbox).
     */
    private val effectiveWorkspaceId: Int
        get() = targetDescriptor.computerId ?: workspaceId

    /**
     * Active CRDT replicas, keyed by document path within this authoring workspace. Each
     * replica is lazy-bootstrapped from disk on the first [openSession] for that path. The
     * map is mutation-safe because Minecraft network handlers can run on the server thread
     * while the menu is being closed concurrently.
     */
    private val replicas: ConcurrentHashMap<String, ServerCrdtReplica> = ConcurrentHashMap()

    fun setTarget(stack: ItemStack) {
        updateTarget(extractTargetDescriptor(stack))
    }

    fun setTarget(descriptor: TargetDescriptor) {
        updateTarget(descriptor)
    }

    fun clearTarget() {
        updateTarget(TargetDescriptor())
    }

    private fun updateTarget(next: TargetDescriptor) {
        if (next.computerId != targetDescriptor.computerId) {
            // Target's workspace changed (or detached) — flush pending replicas back to the
            // *previous* workspace so unflushed edits survive, then drop them so the next
            // open re-bootstraps from the new workspace's disk.
            materializeOpenSessions()
            replicas.clear()
        }
        targetDescriptor = next
    }

    fun bindRuntimeBridge(runtimeBridge: WorkbenchTargetRuntimeBridge) {
        this.runtimeBridge = runtimeBridge
    }

    fun targetDescriptor(): TargetDescriptor = targetDescriptor

    fun targetState(): WorkbenchTargetState = targetDescriptor.toTargetState()

    fun listEntries(path: String = ""): List<ComputerWorkspaceEntry> = workspace.list(effectiveWorkspaceId, path)

    fun read(path: String): ComputerWorkspaceDocument? = workspace.readDocument(effectiveWorkspaceId, path)

    fun write(
        path: String,
        text: String,
    ): ComputerWorkspaceDocument = workspace.writeDocument(effectiveWorkspaceId, path, text)

    fun runTargetProgram() {
        if (targetDescriptor.computerId == null) return
        materializeOpenSessions()
        runtimeBridge.runTargetProgram(targetDescriptor)
    }

    fun rebootTarget() {
        if (targetDescriptor.computerId == null) return
        // Flush in-flight CRDT replicas to disk so the rebooted target sees the latest source.
        // Without this a fresh shell.ck reboot would re-read the pre-edit text.
        materializeOpenSessions()
        runtimeBridge.rebootTarget(targetDescriptor)
    }

    fun attachTerminal() {
        if (targetDescriptor.computerId == null) return
        runtimeBridge.attachTerminal(targetDescriptor)
    }

    fun handleInput(event: InputEvent) {
        if (targetDescriptor.computerId == null) return
        ComputerEvents.dispatch(this, event)
    }

    fun currentScreenSnapshot(): ScreenBufferSnapshot? = runtimeBridge.currentScreenSnapshot(targetDescriptor)

    override fun queueEvent(
        event: String,
        arguments: Array<Any>,
    ) {
        runtimeBridge.queueEvent(targetDescriptor, event, arguments)
    }

    fun snapshot(openDocumentPath: String? = null): WorkbenchRemoteState {
        val entries = listEntries()
        val documentPath = openDocumentPath ?: entries.firstOrNull { !it.directory }?.path
        return WorkbenchRemoteState(
            entries = entries,
            document = documentPath?.let(::read),
            target = targetState(),
        )
    }

    /**
     * Open a CRDT collaboration session for [path] and return the snapshot the client needs to
     * rebuild its local replica. If a replica already exists (another client opened it), this
     * just re-emits the current snapshot — both replicas converge anyway.
     *
     * Returns `null` only when the file does not exist and we cannot create one (e.g. invalid
     * path); the caller should treat that as a no-op and skip sending the snapshot message.
     */
    fun openSession(path: String): SessionSnapshot? {
        val initialText = workspace.readDocument(effectiveWorkspaceId, path)?.text ?: return null
        val replica = replicas.computeIfAbsent(path) {
            ServerCrdtReplica(CrdtDocument.fromText(initialText, SiteId.ServerInit))
        }
        return SessionSnapshot(
            path = path,
            runs = replica.document.runs.toList(),
            versionVector = replica.versionVector(),
        )
    }

    /**
     * Apply a batch of [ops] coming from [sender] to the replica at [path]. If no session is
     * open at [path] the call returns `null` so the caller can re-snapshot.
     *
     * The acked clock returned is the highest clock among all ops the server actually applied
     * in this batch — regardless of [sender]. Since one network request always carries ops from
     * one player only, max-applied-clock is exactly what that client's outbox needs to advance
     * `lastAckedClock`. Keying by [sender] used to be brittle: a mismatch between the client's
     * `siteIdProvider` and the server's `SiteId.player(player.uuid)` would silently leave the
     * outbox in `Syncing -> Stale`. Sender is still passed through for future per-author audit
     * (rejection diagnostics, multi-author batches in Phase 2).
     */
    fun applyOps(path: String, ops: List<Op>, sender: SiteId): OpsApplyResult? {
        val replica = replicas[path] ?: return null
        val result: CrdtApplyResult = replica.apply(ops)
        val ackedClock = result.ackedClockBySite.values.maxOrNull() ?: -1
        return OpsApplyResult(
            ackedClock = ackedClock,
            rejectedAny = result.rejected.isNotEmpty(),
        )
    }

    /**
     * Flatten every open replica back to the workspace on disk. Called before [runTargetProgram]
     * (so the runtime sees up-to-date source) and on menu close (so unflushed edits survive).
     */
    fun materializeOpenSessions() {
        replicas.forEach { (path, replica) ->
            workspace.writeDocument(effectiveWorkspaceId, path, replica.flatten())
        }
    }

    /** Snapshot of an opened CRDT session, used to seed the wire snapshot message. */
    data class SessionSnapshot(
        val path: String,
        val runs: List<TextRun>,
        val versionVector: Map<SiteId, Int>,
    )

    /** Result of applying a batch of remote ops to a server-side replica. */
    data class OpsApplyResult(
        val ackedClock: Int,
        val rejectedAny: Boolean,
    )

    data class TargetDescriptor(
        val computerId: Int? = null,
        val displayName: String? = null,
        val familyId: String? = null,
    ) {
        fun toTargetState(): WorkbenchTargetState =
            if (familyId == null && displayName == null && computerId == null) {
                WorkbenchTargetState()
            } else {
                WorkbenchTargetState(
                    connected = computerId != null,
                    displayName = displayName,
                    familyId = familyId,
                )
            }
    }

    companion object {
        fun extractTargetDescriptor(stack: ItemStack): TargetDescriptor {
            if (stack.isEmpty) return TargetDescriptor()

            val customData = stack.computerDataTagCopy()
            val computerId = customData?.computerID
            val storedFamilyId = customData?.computerFamilyId
            if (computerId == null && storedFamilyId == null && stack.item !is AbstractComputerItem) return TargetDescriptor()

            val familyId =
                storedFamilyId ?: when (stack.item) {
                    is ComputerItem -> ComputerFamily.ADVANCED.name.lowercase()
                    else -> null
                }

            return TargetDescriptor(
                computerId = computerId,
                displayName = stack.hoverName.string.takeIf(String::isNotBlank),
                familyId = familyId,
            )
        }
    }
}

interface WorkbenchTargetRuntimeBridge {
    fun rebootTarget(target: ServerWorkbench.TargetDescriptor)

    fun runTargetProgram(target: ServerWorkbench.TargetDescriptor)

    fun attachTerminal(target: ServerWorkbench.TargetDescriptor)

    fun queueEvent(
        target: ServerWorkbench.TargetDescriptor,
        event: String,
        arguments: Array<Any>,
    ): Boolean

    fun currentScreenSnapshot(target: ServerWorkbench.TargetDescriptor): ScreenBufferSnapshot?

    data object None : WorkbenchTargetRuntimeBridge {
        override fun rebootTarget(target: ServerWorkbench.TargetDescriptor) = Unit

        override fun runTargetProgram(target: ServerWorkbench.TargetDescriptor) = Unit

        override fun attachTerminal(target: ServerWorkbench.TargetDescriptor) = Unit

        override fun queueEvent(
            target: ServerWorkbench.TargetDescriptor,
            event: String,
            arguments: Array<Any>,
        ): Boolean = false

        override fun currentScreenSnapshot(target: ServerWorkbench.TargetDescriptor): ScreenBufferSnapshot? = null
    }
}

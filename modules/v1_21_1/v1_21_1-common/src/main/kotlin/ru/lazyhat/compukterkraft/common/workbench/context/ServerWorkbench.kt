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
     * Active CRDT replicas, keyed by document path within this authoring workspace. Each
     * replica is lazy-bootstrapped from disk on the first [openSession] for that path. The
     * map is mutation-safe because Minecraft network handlers can run on the server thread
     * while the menu is being closed concurrently.
     */
    private val replicas: ConcurrentHashMap<String, ServerCrdtReplica> = ConcurrentHashMap()

    fun setTarget(stack: ItemStack) {
        targetDescriptor = extractTargetDescriptor(stack)
    }

    fun setTarget(descriptor: TargetDescriptor) {
        targetDescriptor = descriptor
    }

    fun clearTarget() {
        targetDescriptor = TargetDescriptor()
    }

    fun bindRuntimeBridge(runtimeBridge: WorkbenchTargetRuntimeBridge) {
        this.runtimeBridge = runtimeBridge
    }

    fun targetDescriptor(): TargetDescriptor = targetDescriptor

    fun targetState(): WorkbenchTargetState = targetDescriptor.toTargetState()

    fun listEntries(path: String = ""): List<ComputerWorkspaceEntry> = workspace.list(workspaceId, path)

    fun read(path: String): ComputerWorkspaceDocument? = workspace.readDocument(workspaceId, path)

    fun write(
        path: String,
        text: String,
    ): ComputerWorkspaceDocument = workspace.writeDocument(workspaceId, path, text)

    fun runTargetProgram() {
        if (targetDescriptor.computerId == null) return
        materializeOpenSessions()
        runtimeBridge.runTargetProgram(targetDescriptor)
    }

    fun rebootTarget() {
        if (targetDescriptor.computerId == null) return
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
        val initialText = workspace.readDocument(workspaceId, path)?.text ?: return null
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
     */
    fun applyOps(path: String, ops: List<Op>, sender: SiteId): OpsApplyResult? {
        val replica = replicas[path] ?: return null
        val result: CrdtApplyResult = replica.apply(ops)
        return OpsApplyResult(
            ackedClock = result.ackedClockBySite[sender] ?: -1,
            rejectedAny = result.rejected.isNotEmpty(),
        )
    }

    /**
     * Flatten every open replica back to the workspace on disk. Called before [runTargetProgram]
     * (so the runtime sees up-to-date source) and on menu close (so unflushed edits survive).
     */
    fun materializeOpenSessions() {
        replicas.forEach { (path, replica) ->
            workspace.writeDocument(workspaceId, path, replica.flatten())
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

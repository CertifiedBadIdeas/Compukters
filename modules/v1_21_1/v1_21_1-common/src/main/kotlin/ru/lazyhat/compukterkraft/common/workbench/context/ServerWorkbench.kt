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
import ru.lazyhat.compukterkraft.common.utils.computerDataTag
import ru.lazyhat.compukterkraft.common.utils.computerFamilyId
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.core.computer.ComputerEvents
import ru.lazyhat.compukterkraft.core.computer.input.InputEvent
import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchSyncState
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchTargetState
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspace
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceDocument
import ru.lazyhat.compukterkraft.lang.runtime.ComputerWorkspaceEntry
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

class ServerWorkbench(
    val workspaceId: Int,
    private val workspace: ComputerWorkspace,
    initialTarget: TargetDescriptor = TargetDescriptor(),
) : ComputerEvents.Receiver {
    private var targetDescriptor: TargetDescriptor = initialTarget
    private var syncState: WorkbenchSyncState = WorkbenchSyncState()
    private var runtimeBridge: WorkbenchTargetRuntimeBridge = WorkbenchTargetRuntimeBridge.None

    fun setTarget(stack: ItemStack) {
        targetDescriptor = extractTargetDescriptor(stack)
    }

    fun setTarget(descriptor: TargetDescriptor) {
        targetDescriptor = descriptor
    }

    fun clearTarget() {
        targetDescriptor = TargetDescriptor()
        syncState = WorkbenchSyncState()
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
    ): ComputerWorkspaceDocument {
        val document = workspace.writeDocument(workspaceId, path, text)
        syncState = syncState.copy(dirtyLocal = true)
        return document
    }

    fun pullFromTarget() {
        val targetComputerId = targetDescriptor.computerId ?: return
        mirrorWorkspace(sourceWorkspaceId = targetComputerId, destinationWorkspaceId = workspaceId)
        syncState = WorkbenchSyncState()
    }

    fun pushToTarget() {
        val targetComputerId = targetDescriptor.computerId ?: return
        mirrorWorkspace(sourceWorkspaceId = workspaceId, destinationWorkspaceId = targetComputerId)
        syncState = WorkbenchSyncState()
    }

    fun runTargetProgram() {
        if (targetDescriptor.computerId == null) return
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
            sync = syncState,
        )
    }

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

    private fun mirrorWorkspace(
        sourceWorkspaceId: Int,
        destinationWorkspaceId: Int,
    ) {
        val sourceEntries = collectEntries(sourceWorkspaceId)
        val destinationEntries = collectEntries(destinationWorkspaceId)

        val sourcePaths = sourceEntries.mapTo(linkedSetOf(), ComputerWorkspaceEntry::path)
        destinationEntries
            .filter { it.path !in sourcePaths }
            .sortedByDescending { it.path.count { ch -> ch == '/' } }
            .forEach { entry ->
                workspace.deleteDocument(destinationWorkspaceId, entry.path)
            }

        sourceEntries
            .filter(ComputerWorkspaceEntry::directory)
            .sortedBy { it.path.count { ch -> ch == '/' } }
            .forEach { entry ->
                workspace.makeDirectory(destinationWorkspaceId, entry.path)
            }

        sourceEntries
            .filterNot(ComputerWorkspaceEntry::directory)
            .forEach { entry ->
                val document = workspace.readDocument(sourceWorkspaceId, entry.path) ?: return@forEach
                workspace.writeDocument(destinationWorkspaceId, entry.path, document.text)
            }
    }

    private fun collectEntries(
        computerId: Int,
        path: String = "",
    ): List<ComputerWorkspaceEntry> {
        val entries = workspace.list(computerId, path)
        return buildList {
            for (entry in entries) {
                add(entry)
                if (entry.directory) {
                    addAll(collectEntries(computerId, entry.path))
                }
            }
        }
    }

    companion object {
        fun extractTargetDescriptor(stack: ItemStack): TargetDescriptor {
            if (stack.isEmpty) return TargetDescriptor()

            val customData = stack.computerDataTag
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
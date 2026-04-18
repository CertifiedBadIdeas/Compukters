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
import ru.lazyhat.compukterkraft.common.workbench.network.server.WorkbenchWorkspaceServerMessage
import ru.lazyhat.compukterkraft.core.computer.input.InputEvent
import ru.lazyhat.compukterkraft.core.computer.workbench.WorkbenchRemoteState
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

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
        text: String,
    ): WorkbenchRemoteState? {
        val workbench = serverWorkbench ?: return null
        return when (action) {
            WorkbenchWorkspaceServerMessage.Action.LIST -> {
                workbench.snapshot(_workspaceStateFlow.value.document?.path)
            }

            WorkbenchWorkspaceServerMessage.Action.READ -> {
                workbench.snapshot(path)
            }

            WorkbenchWorkspaceServerMessage.Action.WRITE -> {
                workbench.write(path, text)
                workbench.snapshot(path)
            }

            WorkbenchWorkspaceServerMessage.Action.PULL -> {
                workbench.pullFromTarget()
                workbench.snapshot(_workspaceStateFlow.value.document?.path)
            }

            WorkbenchWorkspaceServerMessage.Action.PUSH -> {
                workbench.pushToTarget()
                workbench.snapshot(_workspaceStateFlow.value.document?.path)
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
        super.removed(player)
    }

    override fun stillValid(player: Player): Boolean = true
}

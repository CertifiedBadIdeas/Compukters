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
package ck.mod.menu

import ck.lang.runtime.ComputerWorkspaceDocument
import ck.lang.runtime.ComputerWorkspaceEntry
import ck.lang.runtime.ScreenBufferSnapshot
import ck.mod.application.workbench.WorkbenchRemoteState
import ck.mod.block.ComputerFamily
import ck.mod.computer.ServerComputer
import ck.mod.data.ComputerContainerData
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

/**
 * Type-safe representation of which side of the network this menu lives on.
 */
sealed interface MenuSide {
    /**
     * Server-side state: owns [ServerComputer] and [ServerInputState].
     */
    class Server(
        val computer: ServerComputer,
        val input: ServerInputState<out AbstractComputerMenu>,
    ) : MenuSide

    /**
     * Client-side state: owns the latest [ScreenBufferSnapshot].
     */
    class Client(
        initialSnapshot: ScreenBufferSnapshot,
    ) : MenuSide {
        @Volatile
        var screenSnapshot: ScreenBufferSnapshot = initialSnapshot
    }
}

abstract class AbstractComputerMenu(
    type: MenuType<out AbstractComputerMenu>,
    id: Int,
    private val canUse: (Player) -> Boolean,
    override val family: ComputerFamily,
    computer: ServerComputer?,
    containerData: ComputerContainerData?,
) : AbstractContainerMenu(type, id),
    ComputerMenu {
    val uploadMaxSize: Int

    private val data: ContainerData =
        if (computer == null) {
            SimpleContainerData(1)
        } else {
            SingleContainerData { if (computer.isOn) 1 else 0 }
        }

    /**
     * Type-safe side discriminator.
     * On the server: [MenuSide.Server] — holds the [ServerComputer] + input.
     * On the client: [MenuSide.Client] — holds the latest [ScreenBufferSnapshot].
     */
    override val side: MenuSide = if (computer != null) {
        MenuSide.Server(computer, ServerInputState(this))
    } else {
        val snapshot = containerData?.terminalSnapshot
            ?: ScreenBufferSnapshot.empty(51, 19, false)
        MenuSide.Client(snapshot)
    }

    private var workspaceState: WorkbenchRemoteState = WorkbenchRemoteState()
    private val workspaceListeners = mutableMapOf<Int, (WorkbenchRemoteState) -> Unit>()
    private var nextWorkspaceListenerId: Int = 0
    val displayStack: ItemStack = containerData?.displayStack ?: ItemStack.EMPTY

    init {
        addDataSlots(data)

        uploadMaxSize = containerData?.uploadMaxSize ?: ck.mod.Config.uploadMaxSize
    }

    override fun stillValid(player: Player): Boolean {
        val server = side as? MenuSide.Server
        return (server == null || server.computer.checkUsable(player)) && canUse(player)
    }

    override fun updateTerminal(snapshot: ScreenBufferSnapshot) {
        val client = side as? MenuSide.Client
            ?: throw UnsupportedOperationException("Cannot update terminal on the server")
        client.screenSnapshot = snapshot
    }

    override fun updateWorkspaceEntries(entries: List<ComputerWorkspaceEntry>) {
        workspaceState = workspaceState.copy(entries = entries)
        notifyWorkspaceListeners()
    }

    override fun updateWorkspaceDocument(document: ComputerWorkspaceDocument?) {
        workspaceState = workspaceState.copy(document = document)
        notifyWorkspaceListeners()
    }


    fun getWorkspaceEntries(): List<ComputerWorkspaceEntry> = workspaceState.entries

    fun getWorkspaceDocument(): ComputerWorkspaceDocument? = workspaceState.document

    fun addWorkspaceListener(listener: (WorkbenchRemoteState) -> Unit): AutoCloseable {
        val listenerId = nextWorkspaceListenerId++
        workspaceListeners[listenerId] = listener
        listener(workspaceState)
        return AutoCloseable { workspaceListeners.remove(listenerId) }
    }

    override fun removed(player: Player) {
        super.removed(player)
        (side as? MenuSide.Server)?.input?.close()
    }

    companion object {
        const val SIDEBAR_WIDTH: Int = 17
    }

    private fun notifyWorkspaceListeners() {
        workspaceListeners.values.forEach { it(workspaceState) }
    }
}

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

import ck.mod.Config
import ck.mod.block.ComputerFamily
import ck.mod.computer.ServerComputer
import ck.mod.data.ComputerContainerData
import ck.mod.gui.NetworkedTerminal
import ck.mod.gui.Terminal
import ck.mod.gui.TerminalState
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

abstract class AbstractComputerMenu(
    type: MenuType<out AbstractComputerMenu>,
    id: Int,
    private val canUse: (Player) -> Boolean,
    val family: ComputerFamily,
    computer: ServerComputer?,
    containerData: ComputerContainerData?,
) : AbstractContainerMenu(type, id),
    ComputerMenu {
    val uploadMaxSize: Int

    private val data: ContainerData =
        if (computer ==
            null
        ) {
            SimpleContainerData(1)
        } else {
            SingleContainerData { if (computer.isOn) 1 else 0 }
        }

    protected val computer: ServerComputer?

    val input: ServerInputState<AbstractComputerMenu>?
    private val terminal: NetworkedTerminal?
    val displayStack: ItemStack

    init {
        addDataSlots(data)

        this.computer = computer
        input = if (computer == null) null else ServerInputState(this)
        terminal = containerData?.terminalState?.create()
        displayStack = containerData?.displayStack ?: ItemStack.EMPTY
        uploadMaxSize = containerData?.uploadMaxSize ?: Config.uploadMaxSize
    }

    override fun stillValid(player: Player): Boolean = (computer == null || computer.checkUsable(player)) && canUse(player)

    val isOn: Boolean
        get() = data.get(0) != 0

    public override fun getComputerPublic(): ServerComputer {
        if (computer == null) throw UnsupportedOperationException("Cannot access server computer on the client")
        return computer
    }

    public override fun getInputPublic(): ServerInputHandler {
        if (input == null) throw UnsupportedOperationException("Cannot access server computer on the client")
        return input
    }

    public override fun updateTerminal(state: TerminalState) {
        if (terminal == null) throw UnsupportedOperationException("Cannot update terminal on the server")
        state.apply(terminal)
    }

    /**
     * Get the current terminal state.
     *
     * @return The current terminal state.
     * @throws IllegalStateException When accessed on the server.
     */
    fun getTerminal(): Terminal {
        checkNotNull(terminal) { "Cannot update terminal on the server" }
        return terminal
    }

    override fun removed(player: Player) {
        super.removed(player)
        input?.close()
    }

    companion object {
        const val SIDEBAR_WIDTH: Int = 17
    }
}

// SPDX-FileCopyrightText: 2017 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.menu

import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compuktercraft.Config
import ru.lazyhat.compuktercraft.block.ComputerFamily
import ru.lazyhat.compuktercraft.computer.ServerComputer
import ru.lazyhat.compuktercraft.data.ComputerContainerData
import ru.lazyhat.compuktercraft.gui.NetworkedTerminal
import ru.lazyhat.compuktercraft.gui.Terminal
import ru.lazyhat.compuktercraft.gui.TerminalState

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
        computer?.close()
    }

    companion object {
        const val SIDEBAR_WIDTH: Int = 17
    }
}

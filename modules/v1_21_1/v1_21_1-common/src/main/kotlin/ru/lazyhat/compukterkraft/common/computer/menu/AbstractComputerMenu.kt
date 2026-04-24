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
package ru.lazyhat.compukterkraft.common.computer.menu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.common.computer.client.ClientTerminalBuffer
import ru.lazyhat.compukterkraft.common.computer.context.ServerComputer
import ru.lazyhat.compukterkraft.common.computer.data.ComputerContainerData
import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

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
     * Client-side state: owns the latest [ScreenBufferSnapshot] as a [StateFlow].
     */
    class Client(
        initialSnapshot: ScreenBufferSnapshot?,
    ) : MenuSide {
        private val _screenSnapshot = MutableStateFlow(initialSnapshot)

        /** Observable screen snapshot — emits whenever the server syncs a new frame. */
        val screenSnapshotFlow: StateFlow<ScreenBufferSnapshot?> = _screenSnapshot.asStateFlow()

        /** Current screen snapshot (synchronous read). */
        val screenSnapshot: ScreenBufferSnapshot? get() = _screenSnapshot.value

        /** Update from a network message. */
        fun updateScreenSnapshot(snapshot: ScreenBufferSnapshot) {
            _screenSnapshot.value = snapshot
        }

        /**
         * Stream-I/O terminal buffer. `null` until [attachTerminalBuffer] is
         * called by the open [ComputerTerminalScreen][ru.lazyhat.compukterkraft.common.computer.screen.ComputerTerminalScreen].
         *
         * Since Epic 2, terminal rendering prefers this buffer over
         * [screenSnapshot] when present; the snapshot path is kept for the
         * Workbench (and for unit tests) until Epic 4.
         */
        var terminalBuffer: ClientTerminalBuffer? = null
            private set

        fun attachTerminalBuffer(buffer: ClientTerminalBuffer) {
            terminalBuffer = buffer
        }

        fun detachTerminalBuffer() {
            terminalBuffer = null
        }

        fun applyStdoutBytes(bytes: ByteArray) {
            terminalBuffer?.applyStdoutBytes(bytes)
            // Side-effect: update snapshot so Workbench-style consumers see the new frame.
            terminalBuffer?.snapshot()?.let { updateScreenSnapshot(it) }
        }
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

    override val side: MenuSide =
        if (computer != null) {
            MenuSide.Server(computer, ServerInputState(this))
        } else {
            MenuSide.Client(containerData?.terminalSnapshot)
        }

    /** Whether the computer is currently on (synced from server via [ContainerData]). */
    val isComputerOn: Boolean get() = data.get(0) == 1

    val displayStack: ItemStack = containerData?.displayStack ?: ItemStack.EMPTY

    init {
        addDataSlots(data)

        uploadMaxSize = containerData?.uploadMaxSize ?: Config.uploadMaxSize
    }

    override fun stillValid(player: Player): Boolean {
        val server = side as? MenuSide.Server
        return (server == null || server.computer.checkUsable(player)) && canUse(player)
    }

    override fun updateTerminal(snapshot: ScreenBufferSnapshot) {
        val client =
            side as? MenuSide.Client
                ?: throw UnsupportedOperationException("Cannot update terminal on the server")
        client.updateScreenSnapshot(snapshot)
    }

    override fun handleStdoutBytes(bytes: ByteArray) {
        val client =
            side as? MenuSide.Client
                ?: throw UnsupportedOperationException("Cannot apply stdout bytes on the server")
        client.applyStdoutBytes(bytes)
    }

    override fun removed(player: Player) {
        super.removed(player)
        (side as? MenuSide.Server)?.input?.close()
    }
}

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

package ru.lazyhat.compukterkraft.common.data

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.common.computer.ServerComputer
import ru.lazyhat.compukterkraft.common.gui.TerminalState
import ru.lazyhat.compukterkraft.core.Config
import ru.lazyhat.compukterkraft.core.block.ComputerFamily
import ru.lazyhat.compukterkraft.lang.runtime.ScreenBufferSnapshot

class ComputerContainerData private constructor(
    val family: ComputerFamily,
    val terminalSnapshot: ScreenBufferSnapshot,
    val displayStack: ItemStack,
    val uploadMaxSize: Int,
) : IContainerData {
    constructor(buffer: RegistryFriendlyByteBuf) : this(
        buffer.readEnum(ComputerFamily::class.java),
        TerminalState(buffer).toSnapshot(),
        ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
        buffer.readInt(),
    )

    override fun toBytes(buffer: RegistryFriendlyByteBuf) {
        buffer.writeEnum(family)
        TerminalState(terminalSnapshot).write(buffer)
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, displayStack)
        buffer.writeInt(uploadMaxSize)
    }

    constructor(computer: ServerComputer, displayStack: ItemStack) : this(
        computer.family,
        computer.lastScreenSnapshot
            ?: ScreenBufferSnapshot.empty(51, 19, computer.family != ComputerFamily.NORMAL),
        displayStack,
        Config.uploadMaxSize,
    )
}

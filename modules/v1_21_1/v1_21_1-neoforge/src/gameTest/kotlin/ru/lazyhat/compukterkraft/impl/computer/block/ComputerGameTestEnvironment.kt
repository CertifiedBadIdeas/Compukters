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

package ru.lazyhat.compukterkraft.impl.computer.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import ru.lazyhat.compukterkraft.common.computer.block.ComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.context.ServerContext

object ComputerGameTestEnvironment {
    fun computerAt(
        level: ServerLevel,
        pos: BlockPos,
    ): ComputerBlockEntity =
        requireNotNull(level.getBlockEntity(pos) as? ComputerBlockEntity) {
            "Expected computer block entity at $pos"
        }

    fun serverComputerId(
        level: ServerLevel,
        pos: BlockPos,
    ): Int =
        requireNotNull(computerAt(level, pos).computerID) {
            "Expected computer id at $pos"
        }

    fun hasRegisteredServerComputer(
        level: ServerLevel,
        pos: BlockPos,
    ): Boolean {
        val computer = level.getBlockEntity(pos) as? ComputerBlockEntity ?: return false
        val id = computer.computerID ?: return false
        return ServerContext.computerManager.get(id) != null
    }
}

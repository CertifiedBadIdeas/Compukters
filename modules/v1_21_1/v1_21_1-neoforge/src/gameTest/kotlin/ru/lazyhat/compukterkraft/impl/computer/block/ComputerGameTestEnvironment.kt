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
import net.minecraft.world.level.storage.LevelResource
import ru.lazyhat.compukterkraft.common.computer.block.AbstractComputerBlockEntity
import ru.lazyhat.compukterkraft.common.computer.context.ServerContext
import ru.lazyhat.compukterkraft.common.notebook.block.NotebookBlockEntity
import java.nio.file.Path

object ComputerGameTestEnvironment {
    fun computerAt(
        level: ServerLevel,
        pos: BlockPos,
    ): AbstractComputerBlockEntity =
        requireNotNull(level.getBlockEntity(pos) as? AbstractComputerBlockEntity) {
            "Expected computer block entity at $pos"
        }

    fun notebookAt(
        level: ServerLevel,
        pos: BlockPos,
    ): NotebookBlockEntity =
        requireNotNull(level.getBlockEntity(pos) as? NotebookBlockEntity) {
            "Expected notebook block entity at $pos"
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
        val computer = level.getBlockEntity(pos) as? AbstractComputerBlockEntity ?: return false
        val id = computer.computerID ?: return false
        return ServerContext.get(id) != null
    }

    fun storage0Path(
        level: ServerLevel,
        computerId: Int,
    ): Path = computerDirectory(level, computerId).resolve("volumes/storage0.kv")

    fun runtimeSnapshotPath(
        level: ServerLevel,
        computerId: Int,
    ): Path = computerDirectory(level, computerId).resolve("runtime.ksnap")

    private fun computerDirectory(
        level: ServerLevel,
        computerId: Int,
    ): Path =
        level.server
            .getWorldPath(LevelResource.ROOT)
            .resolve("compukterkraft/computers/$computerId")
}

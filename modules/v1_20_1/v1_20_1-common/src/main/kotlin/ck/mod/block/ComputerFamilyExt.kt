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
package ck.mod.block

import ck.mod.Config
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.player.Player

/**
 * Check whether computers with this family can be used by the provided player.
 *
 * This method is not pure. On failure, the method may send a message to the player telling them why they cannot
 * interact with the computer.
 *
 * @param player The player trying to use a computer.
 * @return Whether this computer family can be used.
 */
fun ComputerFamily.checkUsable(player: Player): Boolean =
    when (this) {
        ComputerFamily.NORMAL, ComputerFamily.ADVANCED -> true
        ComputerFamily.COMMAND -> TODO("Not yet implemented") // checkCommandUsable(player)
    }

private fun checkCommandUsable(player: Player): Boolean {
    val server: MinecraftServer? = player.server
    if (server == null || !server.isCommandBlockEnabled) {
        player.displayClientMessage(Component.translatable("advMode.notEnabled"), true)
        return false
    } else if (!canUseCommandBlock(player)) {
        player.displayClientMessage(Component.translatable("advMode.notAllowed"), true)
        return false
    }

    return true
}

private fun canUseCommandBlock(player: Player): Boolean =
    if (Config.commandRequireCreative) player.canUseGameMasterBlocks() else player.hasPermissions(2)

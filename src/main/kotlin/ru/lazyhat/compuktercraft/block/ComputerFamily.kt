// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL
package ru.lazyhat.compuktercraft.block

import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.player.Player
import ru.lazyhat.compuktercraft.Config

enum class ComputerFamily {
    NORMAL,
    ADVANCED,
    COMMAND,
    ;

    /**
     * Check whether computers with this family can be used by the provided player.
     *
     *
     * This method is not pure. On failure, the method may send a message to the player telling them why they cannot
     * interact with the computer.
     *
     * @param player The player trying to use a computer.
     * @return Whether this computer family can be used.
     */
    fun checkUsable(player: Player): Boolean =
        when (this) {
            NORMAL, ADVANCED -> true
            COMMAND -> TODO("Not yet implemented") // checkCommandUsable(player)
        }

    companion object {
        private fun checkCommandUsable(player: Player): Boolean {
            val server: MinecraftServer? = player.getServer()
            if (server == null || !server.isCommandBlockEnabled()) {
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
    }
}

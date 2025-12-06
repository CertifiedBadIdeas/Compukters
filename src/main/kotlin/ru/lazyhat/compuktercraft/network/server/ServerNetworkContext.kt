// SPDX-FileCopyrightText: 2022 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.network.server

import net.minecraft.server.level.ServerPlayer

/**
 * The context under which serverbound packets are evaluated.
 */
fun interface ServerNetworkContext {
    /**
     * Get the player who sent this packet.
     *
     * @return The sending player.
     */
    fun sender(): ServerPlayer
}

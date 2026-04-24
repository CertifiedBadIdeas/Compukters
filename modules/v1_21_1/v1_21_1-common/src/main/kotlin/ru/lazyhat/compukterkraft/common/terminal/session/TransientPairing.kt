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
package ru.lazyhat.compukterkraft.common.terminal.session

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-only registry of the "last computer a player had a terminal bound to".
 * Purely ephemeral: cleared on logout and on server stop. Never persisted to NBT.
 *
 * Contract (Epic 3):
 *  - [set] is called by [TerminalItem.useOn] after a successful shift+RMB on a computer.
 *  - [get] is called by [TerminalItem.use] on air-RMB to decide whether to reopen the UI.
 *  - [clear] is called from a NeoForge `PlayerLoggedOutEvent` handler and from
 *    `ServerStoppingEvent` (see `CompukterKraftMod`).
 */
object TransientPairing {
    /**
     * A binding captures the target computer's instance id and its world location.
     * We re-resolve the `BlockEntity` through the server level at attach time so
     * that a computer being broken / moved invalidates the binding naturally.
     */
    data class Binding(
        val instanceId: Int,
        val blockPos: BlockPos,
        val dimensionId: ResourceKey<Level>,
    )

    private val bindings: ConcurrentHashMap<UUID, Binding> = ConcurrentHashMap()

    fun set(
        playerUuid: UUID,
        binding: Binding,
    ) {
        bindings[playerUuid] = binding
    }

    fun get(playerUuid: UUID): Binding? = bindings[playerUuid]

    fun clear(playerUuid: UUID) {
        bindings.remove(playerUuid)
    }

    fun clearAll() {
        bindings.clear()
    }

    internal fun size(): Int = bindings.size
}

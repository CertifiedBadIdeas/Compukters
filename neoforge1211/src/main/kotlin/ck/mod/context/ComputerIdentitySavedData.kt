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

package ck.mod.context

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData

class ComputerIdentitySavedData(
    private var nextComputerId: Int = FIRST_COMPUTER_ID,
) : SavedData() {
    fun allocateComputerId(): Int {
        val allocatedId = nextComputerId
        nextComputerId += 1
        setDirty()
        return allocatedId
    }

    override fun save(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ): CompoundTag =
        tag.apply {
            putInt(NEXT_COMPUTER_ID_TAG, nextComputerId)
        }

    companion object {
        private const val DATA_NAME = "compukterkraft_computer_identity"
        private const val NEXT_COMPUTER_ID_TAG = "NextComputerId"
        private const val FIRST_COMPUTER_ID = 1

        fun get(server: MinecraftServer): ComputerIdentitySavedData =
            server
                .overworld()
                .dataStorage
                .computeIfAbsent(
                    Factory(::ComputerIdentitySavedData, ::load),
                    DATA_NAME,
                )

        private fun load(
            tag: CompoundTag,
            registries: HolderLookup.Provider,
        ): ComputerIdentitySavedData =
            ComputerIdentitySavedData(
                nextComputerId = tag.getInt(NEXT_COMPUTER_ID_TAG).coerceAtLeast(FIRST_COMPUTER_ID),
            )
    }
}

/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.minecraft.peripheral

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData

internal class PeripheralNetworkStorage(
    val directory: PeripheralNetworkDirectory = PeripheralNetworkDirectory(),
) : SavedData() {
    override fun save(
        output: CompoundTag,
        registries: HolderLookup.Provider,
    ): CompoundTag = PeripheralNetworkCodecs.directory.encodeStart(NbtOps.INSTANCE, directory).getOrThrow() as CompoundTag

    companion object {
        private val factory = SavedData.Factory(::PeripheralNetworkStorage, ::load, null)

        fun get(level: ServerLevel): PeripheralNetworkStorage =
            level.server
                .overworld()
                .dataStorage
                .computeIfAbsent(factory, "compukters_peripheral_networks")

        fun load(
            input: CompoundTag,
            registries: HolderLookup.Provider,
        ): PeripheralNetworkStorage = PeripheralNetworkStorage(PeripheralNetworkCodecs.directory.parse(NbtOps.INSTANCE, input).getOrThrow())
    }
}

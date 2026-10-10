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

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import ru.lazyhat.compukters.core.display.DisplayDirectory

internal class DisplayStorage : SavedData() {
    var directory: DisplayDirectory = DisplayDirectory { setDirty() }
        private set

    override fun save(
        output: CompoundTag,
        registries: HolderLookup.Provider,
    ): CompoundTag = DisplayStorageCodec.codec.encodeStart(NbtOps.INSTANCE, directory.encode()).getOrThrow() as CompoundTag

    companion object {
        internal fun load(
            tag: CompoundTag,
            registries: HolderLookup.Provider,
        ): DisplayStorage =
            DisplayStorage().apply {
                directory = DisplayDirectory.decode(DisplayStorageCodec.codec.parse(NbtOps.INSTANCE, tag).getOrThrow()) { setDirty() }
            }

        private val factory = SavedData.Factory(::DisplayStorage, ::load, null)

        fun get(level: ServerLevel): DisplayStorage = level.dataStorage.computeIfAbsent(factory, "compukters_displays")
    }
}

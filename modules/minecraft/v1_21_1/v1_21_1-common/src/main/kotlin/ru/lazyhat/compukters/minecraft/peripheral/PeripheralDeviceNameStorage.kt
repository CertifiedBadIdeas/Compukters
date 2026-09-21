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

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData

internal class PeripheralDeviceNameStorage(
    val directory: PeripheralDeviceDirectory = PeripheralDeviceDirectory(),
) : SavedData() {
    override fun save(
        output: CompoundTag,
        registries: HolderLookup.Provider,
    ): CompoundTag {
        output.putInt(VERSION_KEY, FORMAT_VERSION)
        val entries = ListTag()
        directory.snapshot().forEach { entry ->
            entries.add(
                CompoundTag().apply {
                    putString(PROVIDER_KEY, entry.identity.providerId)
                    putString(DIMENSION_KEY, entry.identity.dimension)
                    putLong(ANCHOR_KEY, entry.identity.anchor.asLong())
                    putString(DEVICE_KEY, entry.identity.deviceKey)
                    putString(NAME_KEY, entry.name)
                },
            )
        }
        output.put(ENTRIES_KEY, entries)
        return output
    }

    fun setName(
        identity: PeripheralDeviceIdentity,
        name: String,
    ): String = directory.setName(identity, name).also { setDirty() }

    fun clearName(identity: PeripheralDeviceIdentity): String? = directory.clearName(identity)?.also { setDirty() }

    companion object {
        private const val STORAGE_NAME = "compukters_peripheral_names"
        private const val FORMAT_VERSION = 1
        private const val VERSION_KEY = "version"
        private const val ENTRIES_KEY = "entries"
        private const val PROVIDER_KEY = "provider"
        private const val DIMENSION_KEY = "dimension"
        private const val ANCHOR_KEY = "anchor"
        private const val DEVICE_KEY = "device_key"
        private const val NAME_KEY = "name"

        private val FACTORY = SavedData.Factory(::PeripheralDeviceNameStorage, ::load, null)

        fun get(level: ServerLevel): PeripheralDeviceNameStorage =
            level.server
                .overworld()
                .dataStorage
                .computeIfAbsent(FACTORY, STORAGE_NAME)

        fun load(
            input: CompoundTag,
            registries: HolderLookup.Provider,
        ): PeripheralDeviceNameStorage {
            if (input.getInt(VERSION_KEY) != FORMAT_VERSION) return PeripheralDeviceNameStorage()
            val entries =
                input.getList(ENTRIES_KEY, Tag.TAG_COMPOUND.toInt()).mapNotNull { raw ->
                    val entry = raw as? CompoundTag ?: return@mapNotNull null
                    runCatching {
                        PeripheralDeviceName(
                            PeripheralDeviceIdentity(
                                entry.getString(PROVIDER_KEY),
                                entry.getString(DIMENSION_KEY),
                                BlockPos.of(entry.getLong(ANCHOR_KEY)),
                                entry.getString(DEVICE_KEY),
                            ),
                            entry.getString(NAME_KEY),
                        )
                    }.getOrNull()
                }
            return runCatching { PeripheralDeviceNameStorage(PeripheralDeviceDirectory(entries)) }
                .getOrElse { PeripheralDeviceNameStorage() }
        }
    }
}

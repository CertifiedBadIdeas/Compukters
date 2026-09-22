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

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import ru.lazyhat.compukters.core.MOD_ID
import java.util.function.Supplier

internal class PeripheralDeviceNameStorage(
    val directory: PeripheralDeviceDirectory = PeripheralDeviceDirectory(),
) : SavedData() {
    fun setName(
        identity: PeripheralDeviceIdentity,
        name: String,
    ): String = directory.setName(identity, name).also { setDirty() }

    fun assignName(
        reachable: Set<PeripheralDeviceIdentity>,
        target: PeripheralDeviceIdentity,
        requestedName: String,
    ): PeripheralCandidateStatus =
        assignPeripheralName(directory, reachable, target, requestedName).also { result ->
            if (result == PeripheralCandidateStatus.TARGET) setDirty()
        }

    fun clearName(identity: PeripheralDeviceIdentity): String? = directory.clearName(identity)?.also { setDirty() }

    private fun persistentEntries(): List<PersistentEntry> = directory.snapshot().map(PersistentEntry::from)

    companion object {
        internal val CODEC: Codec<PeripheralDeviceNameStorage> =
            PersistentEntry.CODEC.listOf().fieldOf("entries").codec().xmap(
                { entries -> PeripheralDeviceNameStorage(PeripheralDeviceDirectory(entries.map(PersistentEntry::toDeviceName))) },
                PeripheralDeviceNameStorage::persistentEntries,
            )
        private val TYPE =
            SavedDataType(
                Identifier.fromNamespaceAndPath(MOD_ID, "peripheral_names"),
                Supplier(::PeripheralDeviceNameStorage),
                CODEC,
                DataFixTypes.LEVEL,
            )

        fun get(level: ServerLevel): PeripheralDeviceNameStorage =
            level.server
                .overworld()
                .dataStorage
                .computeIfAbsent(TYPE)
    }

    private data class PersistentEntry(
        val providerId: String,
        val dimension: String,
        val anchor: BlockPos,
        val deviceKey: String,
        val name: String,
    ) {
        fun toDeviceName(): PeripheralDeviceName =
            PeripheralDeviceName(PeripheralDeviceIdentity(providerId, dimension, anchor, deviceKey), name)

        companion object {
            val CODEC: Codec<PersistentEntry> =
                RecordCodecBuilder.create { instance ->
                    instance
                        .group(
                            Codec.STRING.fieldOf("provider").forGetter(PersistentEntry::providerId),
                            Codec.STRING.fieldOf("dimension").forGetter(PersistentEntry::dimension),
                            BlockPos.CODEC.fieldOf("anchor").forGetter(PersistentEntry::anchor),
                            Codec.STRING.fieldOf("device_key").forGetter(PersistentEntry::deviceKey),
                            Codec.STRING.fieldOf("name").forGetter(PersistentEntry::name),
                        ).apply(instance, ::PersistentEntry)
                }

            fun from(entry: PeripheralDeviceName): PersistentEntry =
                PersistentEntry(
                    entry.identity.providerId,
                    entry.identity.dimension,
                    entry.identity.anchor,
                    entry.identity.deviceKey,
                    entry.name,
                )
        }
    }
}

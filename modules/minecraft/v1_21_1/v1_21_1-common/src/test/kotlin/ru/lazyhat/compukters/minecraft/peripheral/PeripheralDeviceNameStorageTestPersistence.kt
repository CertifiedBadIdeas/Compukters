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
import java.lang.reflect.Proxy

internal object PeripheralDeviceNameStorageTestPersistence {
    fun roundTrip(storage: PeripheralDeviceNameStorage): PeripheralDeviceNameStorage {
        val registries =
            Proxy.newProxyInstance(
                HolderLookup.Provider::class.java.classLoader,
                arrayOf(HolderLookup.Provider::class.java),
            ) { _, _, _ -> error("registry lookup is not expected") } as HolderLookup.Provider
        return PeripheralDeviceNameStorage.load(storage.save(CompoundTag(), registries), registries)
    }
}

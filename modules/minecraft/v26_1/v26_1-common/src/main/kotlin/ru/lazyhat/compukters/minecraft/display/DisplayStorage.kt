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

import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import ru.lazyhat.compukters.core.display.DisplayDirectory
import java.util.function.Supplier

internal class DisplayStorage : SavedData() {
    var directory: DisplayDirectory = DisplayDirectory { setDirty() }
        private set

    companion object {
        internal val CODEC =
            DisplayStorageCodec.codec.xmap({ decoded ->
                DisplayStorage().apply { directory = DisplayDirectory.decode(decoded) { setDirty() } }
            }, { it.directory.encode() })

        private val type =
            SavedDataType(
                Identifier.fromNamespaceAndPath("compukters", "displays"),
                Supplier(::DisplayStorage),
                CODEC,
                DataFixTypes.LEVEL,
            )

        fun get(level: ServerLevel): DisplayStorage = level.dataStorage.computeIfAbsent(type)
    }
}

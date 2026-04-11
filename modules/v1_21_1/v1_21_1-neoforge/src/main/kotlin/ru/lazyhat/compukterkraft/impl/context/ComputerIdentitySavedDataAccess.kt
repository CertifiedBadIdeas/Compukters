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

package ru.lazyhat.compukterkraft.impl.context

import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData.Factory
import ru.lazyhat.compukterkraft.common.context.COMPUTER_IDENTITY_SAVED_DATA_NAME
import ru.lazyhat.compukterkraft.common.context.ComputerIdentitySavedData
import ru.lazyhat.compukterkraft.common.context.loadComputerIdentitySavedData

fun getComputerIdentitySavedData(server: MinecraftServer): ComputerIdentitySavedData =
    server
        .overworld()
        .dataStorage
        .computeIfAbsent(
            Factory(::ComputerIdentitySavedData, ::loadComputerIdentitySavedData),
            COMPUTER_IDENTITY_SAVED_DATA_NAME,
        )

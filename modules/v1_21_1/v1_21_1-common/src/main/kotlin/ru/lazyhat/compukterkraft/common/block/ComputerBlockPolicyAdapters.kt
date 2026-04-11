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

package ru.lazyhat.compukterkraft.common.block

import net.minecraft.core.Direction
import ru.lazyhat.compukterkraft.core.content.ComputerVisualStateModel
import ru.lazyhat.compukterkraft.core.content.HorizontalFacingModel

internal fun HorizontalFacingModel.toMinecraftDirection(): Direction =
    when (this) {
        HorizontalFacingModel.NORTH -> Direction.NORTH
        HorizontalFacingModel.EAST -> Direction.EAST
        HorizontalFacingModel.SOUTH -> Direction.SOUTH
        HorizontalFacingModel.WEST -> Direction.WEST
    }

internal fun Direction.toFacingModel(): HorizontalFacingModel =
    when (this) {
        Direction.NORTH -> HorizontalFacingModel.NORTH
        Direction.EAST -> HorizontalFacingModel.EAST
        Direction.SOUTH -> HorizontalFacingModel.SOUTH
        Direction.WEST -> HorizontalFacingModel.WEST
        else -> error("Only horizontal directions are supported: $this")
    }

internal fun ComputerVisualStateModel.toMinecraftState(): ComputerState =
    when (this) {
        ComputerVisualStateModel.OFF -> ComputerState.OFF
        ComputerVisualStateModel.ON -> ComputerState.ON
        ComputerVisualStateModel.BLINKING -> ComputerState.BLINKING
    }

internal fun ComputerState.toStateModel(): ComputerVisualStateModel =
    when (this) {
        ComputerState.OFF -> ComputerVisualStateModel.OFF
        ComputerState.ON -> ComputerVisualStateModel.ON
        ComputerState.BLINKING -> ComputerVisualStateModel.BLINKING
    }
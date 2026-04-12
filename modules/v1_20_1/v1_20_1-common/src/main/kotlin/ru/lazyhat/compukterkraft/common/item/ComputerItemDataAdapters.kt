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

package ru.lazyhat.compukterkraft.common.item

import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.computerLabel
import ru.lazyhat.compukterkraft.common.utils.computerLabelByHoverName
import ru.lazyhat.compukterkraft.core.content.ComputerItemData

fun ItemStack.readComputerItemData(): ComputerItemData =
    ComputerItemData(
        computerId = tag?.computerID,
        label = tag?.computerLabel ?: computerLabelByHoverName,
    )

fun ItemStack.writeComputerItemData(data: ComputerItemData) {
    val nbt = orCreateTag
    val label = data.label
    nbt.computerID = data.computerId
    nbt.computerLabel = label

    if (label != null) {
        hoverName = Component.literal(label)
    } else {
        resetHoverName()
    }
}
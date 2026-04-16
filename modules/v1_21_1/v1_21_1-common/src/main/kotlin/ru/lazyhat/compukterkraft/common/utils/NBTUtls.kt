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

package ru.lazyhat.compukterkraft.common.utils

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

private object NBT {
    const val ID: String = "ComputerID"
    const val FAMILY_ID: String = "ComputerFamilyId"
    const val LABEL: String = "Label"
    const val ON: String = "On"
}

var CompoundTag.computerID: Int?
    get() = takeIf { it.contains(NBT.ID) }?.let { getInt(NBT.ID) }
    set(value) {
        value?.let { putInt(NBT.ID, it) }
    }

var CompoundTag.computerLabel: String?
    get() = takeIf { it.contains(NBT.LABEL) }?.let { getString(NBT.LABEL) }
    set(value) {
        value?.let { putString(NBT.LABEL, it) }
    }

var CompoundTag.computerFamilyId: String?
    get() = takeIf { it.contains(NBT.FAMILY_ID) }?.let { getString(NBT.FAMILY_ID) }
    set(value) {
        value?.let { putString(NBT.FAMILY_ID, it) }
    }

var CompoundTag.computerOn: Boolean
    get() = getBoolean(NBT.ON)
    set(value) {
        putBoolean(NBT.ON, value)
    }

val ItemStack.computerDataTag: CompoundTag?
    get() = get(DataComponents.CUSTOM_DATA)?.copyTag()

fun ItemStack.updateComputerData(update: CompoundTag.() -> Unit) {
    CustomData.update(DataComponents.CUSTOM_DATA, this) { tag ->
        tag.update()
    }
}

val ItemStack.computerLabelByHoverName: String?
    get() = takeIf { it.has(DataComponents.CUSTOM_NAME) }?.hoverName?.string

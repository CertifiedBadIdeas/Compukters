package ru.lazyhat.compuktercraft.utils

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

private object NBT {
    const val ID: String = "ComputerID"
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

var CompoundTag.computerOn: Boolean
    get() = getBoolean(NBT.ON)
    set(value) {
        putBoolean(NBT.ON, value)
    }

val ItemStack.computerLabelByHoverName: String?
    get() = takeIf { it.hasCustomHoverName() }?.hoverName?.string

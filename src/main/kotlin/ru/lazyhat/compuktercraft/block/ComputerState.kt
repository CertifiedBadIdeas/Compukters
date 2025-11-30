// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL
package ru.lazyhat.compuktercraft.block

import net.minecraft.util.StringRepresentable

enum class ComputerState(
    private val value: String,
    val texture: String,
) : StringRepresentable {
    OFF("off", ""),
    ON("on", "_on"),
    BLINKING("blinking", "_blink"),
    ;

    override fun getSerializedName(): String = value

    override fun toString(): String = value
}

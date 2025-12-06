// SPDX-FileCopyrightText: 2019 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0
package ru.lazyhat.compuktercraft.menu

import net.minecraft.world.inventory.ContainerData

/**
 * A basic [ContainerData] implementation which provides a single value.
 */
fun interface SingleContainerData : ContainerData {
    fun get(): Int

    override fun get(property: Int): Int = if (property == 0) get() else 0

    override fun set(
        property: Int,
        value: Int,
    ) {
    }

    override fun getCount(): Int = 1
}

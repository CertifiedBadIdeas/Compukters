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
package ru.lazyhat.compukterkraft.menu

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

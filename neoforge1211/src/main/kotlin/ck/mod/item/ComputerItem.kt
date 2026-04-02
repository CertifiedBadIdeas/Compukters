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

package ck.mod.item

import ck.mod.block.ComputerBlock
import ck.mod.utils.computerID
import ck.mod.utils.updateComputerData
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

class ComputerItem(
    block: ComputerBlock,
    properties: Properties,
) : AbstractComputerItem(block, properties) {
    fun create(
        id: Int?,
        label: String?,
    ): ItemStack =
        ItemStack(this).apply {
            updateComputerData { computerID = id }
            label?.let { set(DataComponents.CUSTOM_NAME, Component.literal(it)) }
        }
}

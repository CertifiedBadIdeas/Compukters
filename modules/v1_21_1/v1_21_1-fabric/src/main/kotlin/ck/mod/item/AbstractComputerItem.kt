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

import ck.mod.block.AbstractComputerBlock
import ck.mod.block.AbstractComputerBlockEntity
import ck.mod.utils.computerDataTag
import ck.mod.utils.computerID
import ck.mod.utils.computerLabelByHoverName
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

abstract class AbstractComputerItem(
    block: AbstractComputerBlock<out AbstractComputerBlockEntity>,
    properties: Properties,
) : BlockItem(block, properties) {
    override fun appendHoverText(
        stack: ItemStack,
        context: Item.TooltipContext,
        list: MutableList<Component>,
        options: TooltipFlag,
    ) {
        if (options.isAdvanced || stack.computerLabelByHoverName == null) {
            stack.computerDataTag?.computerID?.let {
                list.add(Component.translatable("gui.compukterkraft.tooltip.computer_id", it).withStyle(ChatFormatting.GRAY))
            }
        }
    }
}

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

package ru.lazyhat.compukterkraft.common.notebook.item

import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.block.Block
import ru.lazyhat.compukterkraft.common.localization.CompukterComponents
import ru.lazyhat.compukterkraft.common.utils.computerDataTagCopy
import ru.lazyhat.compukterkraft.common.utils.computerID
import ru.lazyhat.compukterkraft.common.utils.computerLabel
import ru.lazyhat.compukterkraft.common.utils.computerLabelByHoverName
import ru.lazyhat.compukterkraft.common.utils.deviceFamilyId
import ru.lazyhat.compukterkraft.common.utils.updateComputerDataTag
import ru.lazyhat.compukterkraft.core.block.DeviceFamily

open class NotebookItem(
    block: Block,
    properties: Properties,
) : BlockItem(block, properties) {
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        list: MutableList<Component>,
        options: TooltipFlag,
    ) {
        if (options.isAdvanced || stack.computerLabelByHoverName == null) {
            stack.computerDataTagCopy()?.computerID?.let {
                list.add(
                    CompukterComponents.Gui.Tooltip
                        .computerId(it)
                        .withStyle(ChatFormatting.GRAY),
                )
            }
        }
    }

    fun create(
        id: Int?,
        label: String?,
    ): ItemStack =
        ItemStack(this).apply {
            updateComputerDataTag {
                computerID = id
                deviceFamilyId = DeviceFamily.NORMAL.name.lowercase()
                computerLabel = label
            }
            label?.let { set(DataComponents.CUSTOM_NAME, Component.literal(it)) }
        }
}

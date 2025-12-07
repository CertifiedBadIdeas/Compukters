package ru.lazyhat.compuktercraft.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import ru.lazyhat.compuktercraft.block.AbstractComputerBlock
import ru.lazyhat.compuktercraft.block.AbstractComputerBlockEntity
import ru.lazyhat.compuktercraft.utils.computerID
import ru.lazyhat.compuktercraft.utils.computerLabelByHoverName

abstract class AbstractComputerItem(
    block: AbstractComputerBlock<out AbstractComputerBlockEntity>,
    properties: Properties,
) : BlockItem(block, properties) {
    override fun appendHoverText(
        stack: ItemStack,
        world: Level?,
        list: MutableList<Component>,
        options: TooltipFlag,
    ) {
        if (options.isAdvanced || stack.computerLabelByHoverName == null) {
            stack.tag?.computerID?.let {
                list.add(Component.translatable("gui.compuktercraft.tooltip.computer_id", it).withStyle(ChatFormatting.GRAY))
            }
        }
    }
}

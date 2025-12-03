package ru.lazyhat.compuktercraft.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import ru.lazyhat.compuktercraft.block.ComputerBlock
import ru.lazyhat.compuktercraft.utils.computerID
import ru.lazyhat.compuktercraft.utils.computerLabelByHoverName

class ComputerItem(
    block: ComputerBlock,
    properties: Properties,
) : AbstractComputerItem(block, properties) {
    fun create(
        id: Int?,
        label: String?,
    ): ItemStack =
        ItemStack(this).apply {
            orCreateTag.computerID = id
            label?.let { hoverName = Component.literal(it) }
        }

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

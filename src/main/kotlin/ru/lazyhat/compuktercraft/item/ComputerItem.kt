package ru.lazyhat.compuktercraft.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import ru.lazyhat.compuktercraft.block.ComputerBlock

class ComputerItem(
    block: ComputerBlock,
    properties: Properties,
) : BlockItem(block, properties) {
    companion object {
        const val NBT_ID = "ComputerId"
    }

    fun getComputerID(stack: ItemStack): Int? = stack.tag?.takeIf { it.contains(NBT_ID) }?.getInt(NBT_ID)

    fun getLabel(stack: ItemStack): String? = stack.takeIf { it.hasCustomHoverName() }?.hoverName?.string

    fun create(
        id: Int?,
        label: String?,
    ): ItemStack =
        ItemStack(this).apply {
            id?.let { orCreateTag.putInt(NBT_ID, it) }
            label?.let { hoverName = Component.literal(it) }
        }

    override fun appendHoverText(
        stack: ItemStack,
        world: Level?,
        list: MutableList<Component>,
        options: TooltipFlag,
    ) {
        if (options.isAdvanced || getLabel(stack) == null) {
            getComputerID(stack)?.let {
                list.add(Component.translatable("gui.compuktercraft.tooltip.computer_id").withStyle(ChatFormatting.GRAY))
            }
        }
    }
}

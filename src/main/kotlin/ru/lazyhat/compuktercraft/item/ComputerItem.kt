package ru.lazyhat.compuktercraft.item

import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import ru.lazyhat.compuktercraft.block.ComputerBlock
import ru.lazyhat.compuktercraft.utils.computerID

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
}

package ru.lazyhat.compuktercraft.item

import net.minecraft.world.item.BlockItem
import ru.lazyhat.compuktercraft.block.AbstractComputerBlock
import ru.lazyhat.compuktercraft.block.AbstractComputerBlockEntity

abstract class AbstractComputerItem(
    block: AbstractComputerBlock<out AbstractComputerBlockEntity>,
    properties: Properties,
) : BlockItem(block, properties)

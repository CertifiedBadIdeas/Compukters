package ru.lazyhat.compuktercraft.block

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.Nameable
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

class ComputerBlockEntity(
    type: BlockEntityType<ComputerBlockEntity>,
    pos: BlockPos,
    state: BlockState,
) : BlockEntity(type, pos, state),
    Nameable {
    var label: String? = null
        private set

    var computerId: Int? = null
        private set

    override fun getName(): Component = customName ?: Component.translatable(blockState.block.getDescriptionId())

    override fun hasCustomName(): Boolean = !label.isNullOrEmpty()

    override fun getCustomName(): Component? = label?.takeIf { it.isEmpty() }?.let { Component.literal(it) }
}

package ru.lazyhat.compuktercraft.utils

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker

fun BlockEntity.updateBlock() {
    setChanged()
    level?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_ALL)
}

@Suppress("UNCHECKED_CAST")
fun <A : BlockEntity, B : BlockEntity> BlockEntityTicker<A>.castTicker(): BlockEntityTicker<B>? = (this as? BlockEntityTicker<B>)

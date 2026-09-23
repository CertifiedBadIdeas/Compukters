/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

open class DisplayBlockEntity(
    type: BlockEntityType<*>,
    position: BlockPos,
    blockState: BlockState,
) : BlockEntity(type, position, blockState) {
    internal val buffer = DisplayBuffer()
    private var publishedRevision = 0L

    internal fun serverTick() {
        val serverLevel = level as? ServerLevel ?: return
        buffer.tick()
        if (buffer.revision != publishedRevision) {
            publishedRevision = buffer.revision
            serverLevel.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
        }
    }

    fun displayRows(): List<String> = buffer.rows()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().apply {
            putBoolean(SYNC_KEY, true)
            displayRows().forEachIndexed { index, row -> putString("$ROW_KEY$index", row) }
        }

    override fun loadAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        super.loadAdditional(tag, registries)
        if (tag.getBoolean(SYNC_KEY)) {
            buffer.applySnapshot(List(DisplayBuffer.HEIGHT) { tag.getString("$ROW_KEY$it") })
        }
    }

    private companion object {
        const val SYNC_KEY = "display_sync"
        const val ROW_KEY = "display_row_"
    }
}

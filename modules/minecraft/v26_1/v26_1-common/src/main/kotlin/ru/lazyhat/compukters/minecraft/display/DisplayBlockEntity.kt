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
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

open class DisplayBlockEntity(
    type: BlockEntityType<*>,
    position: BlockPos,
    blockState: BlockState,
) : BlockEntity(type, position, blockState) {
    internal var checkpointIdentity: String =
        java.util.UUID
            .randomUUID()
            .toString()
        private set
    private var publishedRevision = -1L
    private var publishedSurface: java.util.UUID? = null
    private var publishedMode = -1
    private val clientImage = DisplayClientImage()
    private var sentRgb = ByteArray(64 * 64 * 3)

    fun displayColor(
        x: Int,
        y: Int,
    ): Int = requireNotNull(canvasSurface()).canvas.pixelAt(x, y)

    fun screenIdentity(): java.util.UUID? = canvasSurface()?.id

    fun screenMode(): Int = canvasSurface()?.canvas?.mode ?: publishedMode

    fun displayDensity(): Int = clientImage.density

    fun displayPixels(): ByteArray = clientImage.rgb

    fun displayRevision(): Long = clientImage.revision

    internal fun canvasSurface(): ru.lazyhat.compukters.core.display.DisplaySurface? =
        (level as? ServerLevel)?.let { DisplayWorldAccess.surface(it, this) }

    fun publishClientFrame() {
        val clientLevel = level ?: return
        if (!clientLevel.isClientSide) return
        clientImage.publish { position ->
            if (clientLevel.hasChunkAt(BlockPos.of(position))) {
                (clientLevel.getBlockEntity(BlockPos.of(position)) as? DisplayBlockEntity)?.clientImage
            } else {
                null
            }
        }
    }

    internal fun serverTick() {
        val serverLevel = level as? ServerLevel ?: return
        val surface = DisplayWorldAccess.surface(serverLevel, this) ?: return
        surface.canvas.expire()
        val publication = DisplayWorldAccess.publication(serverLevel, surface)
        if (publication.revision == publishedRevision && publication.id == publishedSurface) return
        val instance = java.util.UUID.fromString(checkpointIdentity)
        val pixels = publication.tiles[instance] ?: return
        if (!DisplayWorldAccess.admitSync(serverLevel, pixels.size + publication.panels.length * 2 + 128)) return
        publishedRevision = publication.revision
        publishedSurface = publication.id
        publishedMode = publication.mode
        sentRgb = pixels
        serverLevel.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
        publication.remaining.remove(instance)
    }

    fun hasDisplayPixels(): Boolean = canvasSurface()?.canvas?.encodeRgb()?.any { it != 0.toByte() } ?: sentRgb.any { it != 0.toByte() }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().apply {
            val surface = canvasSurface()
            val publication = surface?.let { DisplayWorldAccess.publication(level as ServerLevel, it) }
            val bytes = publication?.tiles?.get(java.util.UUID.fromString(checkpointIdentity)) ?: sentRgb
            putInt("display_mode", publication?.mode ?: 2)
            putByteArray("display_rgb", bytes)
            if (publication != null) {
                putString("display_surface", publication.id.toString())
                putLong("display_revision", publication.revision)
                putString("display_panels", publication.panels)
            }
        }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        input.getStringOr(IDENTITY_KEY, "").let { value ->
            runCatching {
                java.util.UUID
                    .fromString(value)
                    .toString()
            }.getOrNull()?.let { checkpointIdentity = it }
        }
        input.read("display_rgb", com.mojang.serialization.Codec.BYTE_BUFFER).ifPresent { bytes ->
            if (bytes.remaining() <= 128 * 128 * 3) {
                val rgb = ByteArray(bytes.remaining())
                bytes.duplicate().get(rgb)
                clientImage.receive(
                    input.getIntOr("display_mode", 2),
                    rgb,
                    input.getStringOr("display_surface", ""),
                    input.getLongOr("display_revision", 0),
                    input.getStringOr("display_panels", ""),
                )
            }
        }
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString(IDENTITY_KEY, checkpointIdentity)
    }

    private companion object {
        const val IDENTITY_KEY = "compukters_display_identity"
    }
}

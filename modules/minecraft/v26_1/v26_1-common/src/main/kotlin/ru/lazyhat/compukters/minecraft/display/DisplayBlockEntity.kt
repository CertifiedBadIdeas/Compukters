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
    private var publishedOpacity = 255
    private var clientOpacity = 255
    private val clientImage = DisplayClientImage()
    private var sentRgb = ByteArray(64 * 64 * 3)
    private var snapshotRequestTick = Long.MIN_VALUE

    fun displayColor(
        x: Int,
        y: Int,
    ): Int = requireNotNull(canvasSurface()).canvas.pixelAt(x, y)

    fun screenIdentity(): java.util.UUID? = canvasSurface()?.id

    fun screenMode(): Int = canvasSurface()?.canvas?.mode ?: publishedMode

    fun displayDensity(): Int = clientImage.density

    fun displayPixels(): ByteArray = clientImage.rgb

    fun displayOpacity(): Int = clientOpacity

    fun displayRevision(): Long = clientImage.revision

    fun displayPublication(): Long = clientImage.publication

    fun displaySurfaceIdentity(): String = clientImage.surfaceIdentity

    internal fun canvasSurface(): ru.lazyhat.compukters.core.display.DisplaySurface? =
        (level as? ServerLevel)?.let { DisplayWorldAccess.surface(it, this) }

    fun publishClientFrame() {
        val clientLevel = level ?: return
        if (!clientLevel.isClientSide) return
        requestMissingSnapshot()
        clientImage.publish { position ->
            if (clientLevel.hasChunkAt(BlockPos.of(position))) {
                (clientLevel.getBlockEntity(BlockPos.of(position)) as? DisplayBlockEntity)
                    ?.also {
                        // A loaded peer can block the barrier even when it is outside the camera frustum.
                        it.requestMissingSnapshot()
                    }?.clientImage
            } else {
                null
            }
        }
    }

    private fun requestMissingSnapshot() {
        val clientLevel = level ?: return
        if ((clientImage.needsSnapshot || clientImage.needsInitialSnapshot) &&
            (snapshotRequestTick == Long.MIN_VALUE || clientLevel.gameTime - snapshotRequestTick >= 20)
        ) {
            requestSnapshot?.invoke(this)
            snapshotRequestTick = clientLevel.gameTime
        }
    }

    internal fun serverTick() {
        val serverLevel = level as? ServerLevel ?: return
        val surface = DisplayWorldAccess.surface(serverLevel, this) ?: return
        surface.canvas.expire()
        val publication = DisplayWorldAccess.publication(serverLevel, surface) ?: return
        if (publication.revision == publishedRevision && publication.id == publishedSurface &&
            surface.canvas.opacity == publishedOpacity
        ) {
            return
        }
        val instance = java.util.UUID.fromString(checkpointIdentity)
        val pixels = publication.tiles[instance] ?: return
        val update = publication.updates[instance] ?: return
        if (!DisplayWorldAccess.admitSync(serverLevel, update.rgb.size + publication.panels.length * 2 + 192)) return
        publishedRevision = publication.revision
        publishedSurface = publication.id
        publishedMode = publication.mode
        publishedOpacity = surface.canvas.opacity
        sentRgb = pixels
        sendUpdate?.invoke(this, tile(publication, update))
        publication.remaining.remove(instance)
        ru.lazyhat.compukters.core.display.DisplayPerformanceTrace.begin("sync.admitted")?.apply {
            this.surface = publication.id.toString()
            this.publication = publication.revision
            revision = publication.sourceRevision
            panel = blockPos.asLong()
            bytes = update.rgb.size
            commit()
        }
    }

    fun hasDisplayPixels(): Boolean = canvasSurface()?.canvas?.encodeRgb()?.any { it != 0.toByte() } ?: sentRgb.any { it != 0.toByte() }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    /** Pixels travel in bounded custom payloads; chunk tags contain only stable identity/metadata. */
    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().apply {
            putString(IDENTITY_KEY, checkpointIdentity)
            putInt("display_mode", publishedMode.coerceAtLeast(0))
            putInt("display_opacity", publishedOpacity)
        }

    private fun tile(
        publication: DisplayWorldAccess.Publication,
        update: DisplayTileUpdate,
    ): WorldDisplayTile =
        WorldDisplayTile(
            blockPos.asLong(),
            java.util.UUID.fromString(checkpointIdentity),
            publication.id,
            publication.revision,
            publication.mode,
            publishedOpacity,
            publication.panels.split(',').map(String::toLong),
            update.base,
            update.x,
            update.y,
            update.width,
            update.height,
            update.rgb,
        )

    /** Full recovery independent of a viewer's history. A not-yet-ready publication is retried by the transport. */
    fun snapshotUpdate(): WorldDisplayTile? {
        val serverLevel = level as? ServerLevel ?: return null
        val surface = canvasSurface() ?: return null
        val publication = DisplayWorldAccess.publication(serverLevel, surface) ?: return null
        val pixels = publication.tiles[java.util.UUID.fromString(checkpointIdentity)] ?: return null
        if (!DisplayWorldAccess.admitSync(serverLevel, pixels.size + publication.panels.length * 2 + 192)) return null
        return tile(
            publication,
            DisplayTileUpdate.full(pixels, ru.lazyhat.compukters.core.display.DisplayCanvas.DENSITIES[publication.mode]),
        ).copy(opacity = surface.canvas.opacity)
    }

    /** Called on the client owner thread. The returned work touches only this image, never the world. */
    fun prepareClientUpdate(tile: WorldDisplayTile): (() -> Unit)? {
        if (level?.isClientSide != true || isRemoved || tile.position != blockPos.asLong() ||
            tile.instance.toString() != checkpointIdentity
        ) {
            return null
        }
        clientOpacity = tile.opacity
        val image = clientImage
        val members = tile.members.joinToString(",")
        return {
            image.receive(
                tile.mode,
                tile.rgb,
                tile.surface.toString(),
                tile.publication,
                members,
                tile.base,
                tile.x,
                tile.y,
                tile.width,
                tile.height,
            )
        }
    }

    fun requireClientSnapshot(publication: Long) {
        clientImage.requireSnapshot(publication)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        clientOpacity = input.getIntOr("display_opacity", 255).coerceIn(0, 255)
        input.getStringOr(IDENTITY_KEY, "").let { value ->
            runCatching {
                java.util.UUID
                    .fromString(value)
                    .toString()
            }.getOrNull()?.let { checkpointIdentity = it }
        }
        if (input.getIntOr("display_wire", 1) != 1) return
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
                    input.getLongOr("display_base", -1),
                    input.getIntOr("display_x", 0),
                    input.getIntOr("display_y", 0),
                    input.getIntOr(
                        "display_width",
                        ru.lazyhat.compukters.core.display.DisplayCanvas.DENSITIES.getOrElse(
                            input.getIntOr("display_mode", 2),
                        ) {
                            0
                        },
                    ),
                    input.getIntOr(
                        "display_height",
                        ru.lazyhat.compukters.core.display.DisplayCanvas.DENSITIES.getOrElse(
                            input.getIntOr("display_mode", 2),
                        ) {
                            0
                        },
                    ),
                )
            }
        }
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString(IDENTITY_KEY, checkpointIdentity)
    }

    companion object {
        /** Client bootstrap supplies transport; common block entities never depend on loader client classes. */
        var requestSnapshot: ((DisplayBlockEntity) -> Unit)? = null
        var sendUpdate: ((DisplayBlockEntity, WorldDisplayTile) -> Unit)? = null
        private const val IDENTITY_KEY = "compukters_display_identity"
    }
}

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
    private var sentUpdate = DisplayTileUpdate.full(sentRgb, 64)
    private var sentPanels = ""
    private var snapshotRequestTick = Long.MIN_VALUE

    fun displayColor(
        x: Int,
        y: Int,
    ): Int = requireNotNull(canvasSurface()).canvas.pixelAt(x, y)

    fun screenIdentity(): java.util.UUID? = canvasSurface()?.id

    fun screenMode(): Int = canvasSurface()?.canvas?.mode ?: publishedMode

    fun displayDensity(): Int = clientImage.density

    fun displayPixels(): ByteArray = clientImage.rgb

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
        if (clientImage.needsSnapshot && (snapshotRequestTick == Long.MIN_VALUE || clientLevel.gameTime - snapshotRequestTick >= 20)) {
            requestSnapshot?.invoke(this)
            snapshotRequestTick = clientLevel.gameTime
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
        val update = publication.updates[instance] ?: return
        if (!DisplayWorldAccess.admitSync(serverLevel, update.rgb.size + publication.panels.length * 2 + 192)) return
        publishedRevision = publication.revision
        publishedSurface = publication.id
        publishedMode = publication.mode
        sentRgb = pixels
        sentUpdate = update
        sentPanels = publication.panels
        serverLevel.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
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

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this) { _, _ -> incrementalTag() }

    private fun incrementalTag(): CompoundTag =
        CompoundTag().apply {
            putInt("display_wire", 1)
            putInt("display_mode", publishedMode)
            putString("display_surface", publishedSurface?.toString() ?: "")
            putLong("display_revision", publishedRevision)
            putString("display_panels", sentPanels)
            putByteArray("display_rgb", sentUpdate.rgb)
            putLong("display_base", sentUpdate.base)
            putInt("display_x", sentUpdate.x)
            putInt("display_y", sentUpdate.y)
            putInt("display_width", sentUpdate.width)
            putInt("display_height", sentUpdate.height)
        }

    /** Full snapshot for initial chunk tracking and explicit recovery; never depends on client history. */
    fun snapshotPacket(): Packet<ClientGamePacketListener>? {
        val serverLevel = level as? ServerLevel ?: return null
        val surface = canvasSurface() ?: return null
        val publication = DisplayWorldAccess.publication(serverLevel, surface)
        val pixels = publication.tiles[java.util.UUID.fromString(checkpointIdentity)] ?: return null
        if (!DisplayWorldAccess.admitSync(serverLevel, pixels.size + publication.panels.length * 2 + 192)) return null
        return ClientboundBlockEntityDataPacket.create(this)
    }

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag =
        CompoundTag().apply {
            val surface = canvasSurface()
            val publication = surface?.let { DisplayWorldAccess.publication(level as ServerLevel, it) }
            val bytes = publication?.tiles?.get(java.util.UUID.fromString(checkpointIdentity)) ?: sentRgb
            putInt("display_wire", 1)
            putInt("display_mode", publication?.mode ?: publishedMode.coerceAtLeast(0))
            putByteArray("display_rgb", bytes)
            if (publication != null) {
                putString("display_surface", publication.id.toString())
                putLong("display_revision", publication.revision)
                putString("display_panels", publication.panels)
            }
        }

    override fun loadAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        super.loadAdditional(tag, registries)
        runCatching {
            java.util.UUID
                .fromString(tag.getString(IDENTITY_KEY))
                .toString()
        }.getOrNull()?.let { checkpointIdentity = it }
        if (tag.contains("display_rgb") && (!tag.contains("display_wire") || tag.getInt("display_wire") == 1)) {
            clientImage.receive(
                tag.getInt("display_mode"),
                tag.getByteArray("display_rgb"),
                tag.getString("display_surface"),
                tag.getLong("display_revision"),
                tag.getString("display_panels"),
                if (tag.contains("display_base")) tag.getLong("display_base") else -1,
                tag.getInt("display_x"),
                tag.getInt("display_y"),
                if (tag.contains(
                        "display_width",
                    )
                ) {
                    tag.getInt(
                        "display_width",
                    )
                } else {
                    ru.lazyhat.compukters.core.display.DisplayCanvas.DENSITIES.getOrElse(tag.getInt("display_mode")) {
                        0
                    }
                },
                if (tag.contains(
                        "display_height",
                    )
                ) {
                    tag.getInt(
                        "display_height",
                    )
                } else {
                    ru.lazyhat.compukters.core.display.DisplayCanvas.DENSITIES.getOrElse(tag.getInt("display_mode")) {
                        0
                    }
                },
            )
        }
    }

    override fun saveAdditional(
        tag: CompoundTag,
        registries: HolderLookup.Provider,
    ) {
        super.saveAdditional(tag, registries)
        tag.putString(IDENTITY_KEY, checkpointIdentity)
    }

    companion object {
        /** Client bootstrap supplies transport; common block entities never depend on loader client classes. */
        var requestSnapshot: ((DisplayBlockEntity) -> Unit)? = null
        private const val IDENTITY_KEY = "compukters_display_identity"
    }
}

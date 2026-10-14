/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.compat.Identifier
import ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity
import java.util.WeakHashMap

/** Position-only request. The server reads its own image and never loads chunks for a client request. */
data class DisplaySnapshotRequest(
    val position: Long,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<DisplaySnapshotRequest> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<DisplaySnapshotRequest>(Identifier.fromNamespaceAndPath(MOD_ID, "display_snapshot"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, DisplaySnapshotRequest> =
            StreamCodec.of(
                StreamEncoder { buffer, payload -> buffer.writeLong(payload.position) },
                StreamDecoder { buffer -> DisplaySnapshotRequest(buffer.readLong()) },
            )
    }
}

object DisplaySnapshotNetwork {
    private data class Requests(
        var tick: Long,
        var count: Int,
    )

    private val requests = WeakHashMap<ServerPlayer, Requests>()

    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar("1").playToServer(DisplaySnapshotRequest.TYPE, DisplaySnapshotRequest.STREAM_CODEC) { payload, context ->
            context.enqueueWork {
                val player = context.player() as? ServerPlayer ?: return@enqueueWork
                val level = player.level()
                val pos = BlockPos.of(payload.position)
                if (!player.isAlive || player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > 64.0 * 64.0 ||
                    !level.hasChunkAt(pos)
                ) {
                    return@enqueueWork
                }
                val tick = level.gameTime
                val quota = requests.getOrPut(player) { Requests(tick, 0) }
                if (quota.tick != tick) {
                    quota.tick = tick
                    quota.count = 0
                }
                if (++quota.count > 64) return@enqueueWork
                val entity = level.getBlockEntity(pos) as? DisplayBlockEntity ?: return@enqueueWork
                entity.snapshotPacket()?.let(player.connection::send)
            }
        }
    }
}

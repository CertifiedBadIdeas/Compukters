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
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.NetworkRegistry
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.compat.Identifier
import ru.lazyhat.compukters.minecraft.display.DisplayBlockEntity
import ru.lazyhat.compukters.minecraft.display.DisplayPublications
import ru.lazyhat.compukters.minecraft.display.WorldDisplayTile
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
    private data class Request(
        val level: ServerLevel,
        val expires: Long,
    )

    private data class Requests(
        var tick: Long,
        var count: Int,
        val pending: MutableMap<Long, Request> = linkedMapOf(),
    )

    private val requests = WeakHashMap<ServerPlayer, Requests>()

    fun register(event: RegisterPayloadHandlersEvent) {
        DisplayBlockEntity.sendUpdate = { entity, tile ->
            val level = entity.level as? ServerLevel
            if (level != null) {
                val chunk = ChunkPos(entity.blockPos.x shr 4, entity.blockPos.z shr 4)
                for (player in level.chunkSource.chunkMap.getPlayers(chunk, false)) {
                    if (NetworkRegistry.hasChannel(player.connection, WorldDisplayPayload.TYPE.id())) {
                        PacketDistributor.sendToPlayer(player, WorldDisplayPayload(tile))
                    }
                }
            }
        }

        event.registrar("1").playToClient(WorldDisplayPayload.TYPE, WorldDisplayPayload.CODEC) { payload, _ ->
            WorldDisplayClient.receive(payload.tile)
        }
        event.registrar("1").playToServer(DisplaySnapshotRequest.TYPE, DisplaySnapshotRequest.STREAM_CODEC) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@playToServer
            val level = player.level() as? ServerLevel ?: return@playToServer
            val pos = BlockPos.of(payload.position)
            if (!allowed(player, level, pos)) return@playToServer
            val quota = requests.getOrPut(player) { Requests(level.gameTime, 0) }
            if (quota.tick != level.gameTime) {
                quota.tick = level.gameTime
                quota.count = 0
            }
            if (++quota.count > 64 || quota.pending.size >= 64 || requests.values.sumOf { it.pending.size } >= 1024) return@playToServer
            quota.pending[payload.position] = Request(level, level.gameTime + 40)
        }
    }

    private fun allowed(
        player: ServerPlayer,
        level: ServerLevel,
        pos: BlockPos,
    ): Boolean =
        player.isAlive && player.level() === level && level.hasChunkAt(pos) &&
            player in level.chunkSource.chunkMap.getPlayers(ChunkPos(pos.x shr 4, pos.z shr 4), false)

    fun tick(event: ServerTickEvent.Post) {
        requests.keys.removeIf { it !in event.server.playerList.players }
        for ((player, quota) in requests) {
            val iterator = quota.pending.iterator()
            while (iterator.hasNext()) {
                val (position, request) = iterator.next()
                val pos = BlockPos.of(position)
                val entity =
                    if (allowed(player, request.level, pos) && request.level.gameTime <= request.expires) {
                        request.level.getBlockEntity(pos) as? DisplayBlockEntity
                    } else {
                        null
                    }
                if (entity == null) {
                    iterator.remove()
                    continue
                }
                val tile = entity.snapshotUpdate() ?: continue
                PacketDistributor
                    .sendToPlayer(player, WorldDisplayPayload(tile))
                iterator.remove()
            }
        }
    }

    fun unload(event: LevelEvent.Unload) {
        val level = event.level as? ServerLevel ?: return
        requests.values.forEach { it.pending.values.removeIf { request -> request.level === level } }
        DisplayPublications
            .unload(level)
    }

    fun stop(event: ServerStoppingEvent) {
        requests.keys.removeIf { (it.level() as? ServerLevel)?.server === event.server }
        DisplayPublications
            .stop(event.server)
    }
}

internal data class WorldDisplayPayload(
    val tile: WorldDisplayTile,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<WorldDisplayPayload>(Identifier.fromNamespaceAndPath(MOD_ID, "world_display_tile"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, WorldDisplayPayload> =
            StreamCodec.of(
                StreamEncoder { b, v ->
                    val t = v.tile
                    b.writeLong(t.position)
                    b.writeUUID(t.instance)
                    b.writeUUID(t.surface)
                    b.writeLong(t.publication)
                    b.writeByte(t.mode)
                    b.writeByte(t.opacity)
                    b.writeVarInt(t.members.size)
                    t.members.forEach(b::writeLong)
                    b.writeLong(t.base)
                    b.writeVarInt(t.x)
                    b.writeVarInt(t.y)
                    b.writeVarInt(t.width)
                    b.writeVarInt(t.height)
                    b.writeByteArray(t.rgb)
                },
                StreamDecoder { b ->
                    val position = b.readLong()
                    val instance = b.readUUID()
                    val surface = b.readUUID()
                    val publication = b.readLong()
                    val mode = b.readUnsignedByte().toInt()
                    val opacity = b.readUnsignedByte().toInt()
                    val count = b.readVarInt().also { require(it in 1..64) }
                    val members = List(count) { b.readLong() }
                    WorldDisplayPayload(
                        WorldDisplayTile(
                            position,
                            instance,
                            surface,
                            publication,
                            mode,
                            opacity,
                            members,
                            b.readLong(),
                            b.readVarInt(),
                            b.readVarInt(),
                            b.readVarInt(),
                            b.readVarInt(),
                            b.readByteArray(128 * 128 * 3),
                        ),
                    )
                },
            )
    }
}

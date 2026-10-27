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
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import ru.lazyhat.compukters.impl.compat.Identifier
import ru.lazyhat.compukters.minecraft.display.AttachedDisplayMessage
import ru.lazyhat.compukters.minecraft.display.AttachedDisplayView

object AttachedDisplayNetwork {
    private val snapshotTicks = java.util.WeakHashMap<ServerPlayer, Int>()
    private val viewers = java.util.WeakHashMap<ServerPlayer, AttachedDisplayView>()

    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar("1").playToServer(AttachedDisplaySnapshotRequest.TYPE, AttachedDisplaySnapshotRequest.CODEC) { request, context ->
            val player = context.player() as? ServerPlayer ?: return@playToServer
            val tick = (player.level() as? net.minecraft.server.level.ServerLevel)?.server?.tickCount ?: return@playToServer
            if (snapshotTicks[player] != tick) {
                snapshotTicks[player] = tick
                viewers[player]?.requestSnapshot(request.surface)
            }
        }
        event.registrar("1").playToClient(AttachedDisplayPayload.TYPE, AttachedDisplayPayload.CODEC) { payload, _ ->
            AttachedDisplayClient.receive(payload.message)
        }
    }

    fun tick(event: ServerTickEvent.Post) {
        val players =
            event.server.playerList.players.filter {
                net.neoforged.neoforge.network.registration.NetworkRegistry
                    .hasChannel(it.connection, AttachedDisplayPayload.TYPE.id())
            }
        val active = players.toSet()
        viewers.keys.removeIf { it !in active }
        if (players.isNotEmpty()) {
            val offset = Math.floorMod(event.server.tickCount, players.size)
            for (i in players.indices) {
                val player = players[(i + offset) % players.size]
                val level = player.level() as? net.minecraft.server.level.ServerLevel ?: continue
                viewers
                    .getOrPut(player) {
                        AttachedDisplayView()
                    }.update(
                        level,
                        player.position(),
                    ) { message -> PacketDistributor.sendToPlayer(player, AttachedDisplayPayload(message)) }
            }
        }
        for (level in event.server.allLevels) {
            AttachedDisplayView.expire(
                level,
                players.filter { it.level() === level }.map { it.position() },
            )
        }
    }

    fun stop(event: ServerStoppingEvent) {
        viewers.keys.removeIf {
            (it.level() as? net.minecraft.server.level.ServerLevel)?.server ===
                event.server
        }
    }
}

internal data class AttachedDisplayPayload(
    val message: AttachedDisplayMessage,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<AttachedDisplayPayload>(Identifier.fromNamespaceAndPath("compukters", "attached_display"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, AttachedDisplayPayload> =
            StreamCodec.of(
                StreamEncoder { b, value ->
                    val m = value.message
                    b.writeByte(
                        when (m) {
                            is AttachedDisplayMessage.Manifest -> 0
                            is AttachedDisplayMessage.Tile -> 1
                            is AttachedDisplayMessage.Remove -> 2
                        },
                    )
                    b.writeUUID(m.surface)
                    when (m) {
                        is AttachedDisplayMessage.Manifest -> {
                            b.writeLong(m.publication)
                            b.writeByte(m.mode)
                            b.writeByte(m.facing)
                            b.writeByte(m.opacity)
                            b.writeVarInt(m.members.size)
                            m.members.forEach {
                                b.writeBlockPos(it.position)
                                b.writeUUID(it.instance)
                            }
                        }

                        is AttachedDisplayMessage.Tile -> {
                            b.writeUUID(m.instance)
                            b.writeLong(m.publication)
                            b.writeLong(m.base)
                            b.writeVarInt(m.x)
                            b.writeVarInt(m.y)
                            b.writeVarInt(m.width)
                            b.writeVarInt(m.height)
                            b.writeByteArray(m.rgb)
                        }

                        is AttachedDisplayMessage.Remove -> {
                            Unit
                        }
                    }
                },
                StreamDecoder { b ->
                    val kind = b.readUnsignedByte().toInt()
                    require(kind in 0..2)
                    val surface = b.readUUID()
                    AttachedDisplayPayload(
                        when (kind) {
                            0 -> {
                                val publication = b.readLong()
                                val mode = b.readUnsignedByte().toInt()
                                val facing = b.readUnsignedByte().toInt()
                                val opacity = b.readUnsignedByte().toInt()
                                val count = b.readVarInt().also { require(it in 1..64) }
                                AttachedDisplayMessage.Manifest(
                                    surface,
                                    publication,
                                    mode,
                                    facing,
                                    opacity,
                                    List(count) {
                                        AttachedDisplayMessage.Member(b.readBlockPos(), b.readUUID())
                                    },
                                )
                            }

                            1 -> {
                                AttachedDisplayMessage.Tile(
                                    surface,
                                    b.readUUID(),
                                    b.readLong(),
                                    b.readLong(),
                                    b.readVarInt(),
                                    b.readVarInt(),
                                    b.readVarInt(),
                                    b.readVarInt(),
                                    b.readByteArray(128 * 128 * 3),
                                )
                            }

                            else -> {
                                AttachedDisplayMessage.Remove(surface)
                            }
                        },
                    )
                },
            )
    }
}

internal data class AttachedDisplaySnapshotRequest(
    val surface: java.util.UUID,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE =
            CustomPacketPayload.Type<AttachedDisplaySnapshotRequest>(
                Identifier.fromNamespaceAndPath("compukters", "attached_display_snapshot"),
            )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, AttachedDisplaySnapshotRequest> =
            StreamCodec.of(
                StreamEncoder { b, v -> b.writeUUID(v.surface) },
                StreamDecoder { b -> AttachedDisplaySnapshotRequest(b.readUUID()) },
            )
    }
}

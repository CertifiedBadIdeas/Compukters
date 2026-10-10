/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import ru.lazyhat.compukters.impl.compat.Identifier
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlayDevice
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlayScreen
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlayServer
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlaySnapshot
import java.util.WeakHashMap

internal object ConfiguratorOverlayNetwork {
    private val requests = WeakHashMap<ServerPlayer, Long>()

    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToServer(OverlayRequest.TYPE, OverlayRequest.CODEC) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@playToServer
            val tick = (player.level() as net.minecraft.server.level.ServerLevel).server.overworld().gameTime
            val previous = requests[player]
            if (previous != null && tick - previous in 0..4) return@playToServer
            requests[player] = tick
            val snapshot = ConfiguratorOverlayServer.collect(player, payload.hand) ?: return@playToServer
            context.reply(OverlayReply(payload.token, player.level().dimension().toString(), snapshot))
        }
        registrar.playToClient(OverlayReply.TYPE, OverlayReply.CODEC) { payload, _ -> ConfiguratorOverlayClient.receive(payload) }
    }
}

internal data class OverlayRequest(
    val hand: InteractionHand,
    val token: Long,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OverlayRequest>(Identifier.fromNamespaceAndPath("compukters", "configurator_overlay_request"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, OverlayRequest> =
            StreamCodec.of(
                StreamEncoder { buffer, value ->
                    buffer.writeEnum(value.hand)
                    buffer.writeLong(value.token)
                },
                StreamDecoder { buffer -> OverlayRequest(buffer.readEnum(InteractionHand::class.java), buffer.readLong()) },
            )
    }
}

internal data class OverlayReply(
    val token: Long,
    val dimension: String,
    val snapshot: ConfiguratorOverlaySnapshot,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OverlayReply>(Identifier.fromNamespaceAndPath("compukters", "configurator_overlay_reply"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, OverlayReply> = StreamCodec.of(StreamEncoder(::write), StreamDecoder(::read))

        private fun write(
            buffer: RegistryFriendlyByteBuf,
            value: OverlayReply,
        ) {
            buffer.writeLong(value.token)
            buffer.writeUtf(value.dimension, 128)
            buffer.writeBoolean(value.snapshot.truncated)
            buffer.writeVarInt(value.snapshot.devices.size)
            value.snapshot.devices.forEach { device ->
                buffer.writeBlockPos(device.position)
                buffer.writeUtf(device.title, 256)
                buffer.writeNullable(device.network) { sink, id -> sink.writeUUID(id) }
                buffer.writeUtf(device.networkName, 64)
                buffer.writeNullable(device.screen) { sink, id -> sink.writeUUID(id) }
            }
            buffer.writeVarInt(value.snapshot.screens.size)
            value.snapshot.screens.forEach { screen ->
                buffer.writeUUID(screen.id)
                buffer.writeUtf(screen.name, 64)
                buffer.writeBlockPos(screen.origin)
                buffer.writeEnum(screen.facing)
                buffer.writeVarInt(screen.columns)
                buffer.writeVarInt(screen.rows)
                buffer.writeVarInt(screen.panels.size)
                screen.panels.forEach(buffer::writeBlockPos)
            }
        }

        private fun read(buffer: RegistryFriendlyByteBuf): OverlayReply {
            val token = buffer.readLong()
            val dimension = buffer.readUtf(128)
            val truncated = buffer.readBoolean()
            val devices =
                List(buffer.readVarInt().also { require(it in 0..ConfiguratorOverlaySnapshot.MAX_DEVICES) }) {
                    ConfiguratorOverlayDevice(
                        buffer.readBlockPos(),
                        buffer.readUtf(256),
                        buffer.readNullable {
                            it.readUUID()
                        },
                        buffer.readUtf(64),
                        buffer.readNullable { it.readUUID() },
                    )
                }
            val screens =
                List(buffer.readVarInt().also { require(it in 0..ConfiguratorOverlaySnapshot.MAX_SCREENS) }) {
                    val id = buffer.readUUID()
                    val name = buffer.readUtf(64)
                    val origin = buffer.readBlockPos()
                    val facing = buffer.readEnum(Direction::class.java)
                    val columns = buffer.readVarInt().also { require(it in 1..8) }
                    val rows = buffer.readVarInt().also { require(it in 1..8) }
                    val panels = List(buffer.readVarInt().also { require(it in 1..columns * rows) }) { buffer.readBlockPos() }
                    ConfiguratorOverlayScreen(id, name, origin, facing, columns, rows, panels)
                }
            return OverlayReply(token, dimension, ConfiguratorOverlaySnapshot(devices, screens, truncated))
        }
    }
}

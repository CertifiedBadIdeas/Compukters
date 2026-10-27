/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import ru.lazyhat.compukters.impl.compat.Identifier
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorItem
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralWorldPositions

internal object ConfiguratorActionNetwork {
    private val lastAction = java.util.WeakHashMap<ServerPlayer, Long>()

    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar("1").playToServer(ConfiguratorActionPayload.TYPE, ConfiguratorActionPayload.CODEC) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@playToServer
            val stack = player.getItemInHand(payload.hand)
            val item = stack.item as? PeripheralConfiguratorItem ?: return@playToServer
            val level = player.level() as? net.minecraft.server.level.ServerLevel ?: return@playToServer
            val now = level.server.overworld().gameTime
            if (lastAction[player] == now || player.isSpectator || !player.isAlive) return@playToServer
            lastAction[player] = now
            if (payload.mode >= 0) {
                if (payload.mode !in 0..3) return@playToServer
                item.selectMode(stack, payload.mode)
                player.sendSystemMessage(
                    Component.translatable(
                        "item.compukters.peripheral_configurator.mode." + listOf("name", "network", "display", "keyboard")[payload.mode],
                    ),
                )
            } else if (payload.mode == -1) {
                val position = payload.position
                if (position == null) {
                    item.use(level, player, payload.hand)
                } else {
                    if (!level.hasChunkAt(position) || !player.mayBuild() || !level.mayInteract(player, position) ||
                        !PeripheralWorldPositions.within(level, player.position(), Vec3.atCenterOf(position), 8.0)
                    ) {
                        return@playToServer
                    }
                    item.useOn(UseOnContext(player, payload.hand, BlockHitResult(Vec3.atCenterOf(position), payload.face, position, false)))
                }
            }
        }
    }
}

internal data class ConfiguratorActionPayload(
    val hand: InteractionHand,
    val mode: Int,
    val position: BlockPos? = null,
    val face: Direction = Direction.NORTH,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ConfiguratorActionPayload>(Identifier.fromNamespaceAndPath("compukters", "configurator_action"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ConfiguratorActionPayload> =
            StreamCodec.of(
                StreamEncoder { buffer, value ->
                    buffer.writeEnum(value.hand)
                    buffer.writeVarInt(value.mode)
                    buffer.writeNullable(value.position) { sink, position -> sink.writeBlockPos(position) }
                    buffer.writeEnum(value.face)
                },
                StreamDecoder { buffer ->
                    ConfiguratorActionPayload(
                        buffer.readEnum(InteractionHand::class.java),
                        buffer.readVarInt().also {
                            require(it in -1..3)
                        },
                        buffer.readNullable { it.readBlockPos() },
                        buffer.readEnum(Direction::class.java),
                    )
                },
            )
    }
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.input

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import ru.lazyhat.compukters.impl.compat.Identifier
import ru.lazyhat.compukters.minecraft.input.ComputerKeyboard
import java.util.UUID

object KeyboardControlNetwork {
    private val lastTick = java.util.WeakHashMap<ServerPlayer, Long>()

    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToServer(KeyboardHeldPayload.TYPE, KeyboardHeldPayload.CODEC) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@playToServer
            val level = player.level() as? net.minecraft.server.level.ServerLevel ?: return@playToServer
            val now = level.server.overworld().gameTime
            // Release is always admissible, including after an earlier held packet in the same tick.
            if (!payload.release && lastTick[player] == now) return@playToServer
            lastTick[player] = now
            ComputerKeyboard.heartbeat(player, payload.token, payload.sequence, payload.keys, payload.release)
        }
        registrar.playToClient(
            KeyboardStatusPayload.TYPE,
            KeyboardStatusPayload.CODEC,
        ) { payload, _ -> KeyboardInputClient.status(payload) }
        ComputerKeyboard.sendStatus = { player, token, mask -> PacketDistributor.sendToPlayer(player, KeyboardStatusPayload(token, mask)) }
    }
}

internal data class KeyboardHeldPayload(
    val token: UUID,
    val sequence: Long,
    val keys: Long,
    val release: Boolean,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<KeyboardHeldPayload>(Identifier.fromNamespaceAndPath("compukters", "keyboard_held"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, KeyboardHeldPayload> =
            StreamCodec.of(
                StreamEncoder { b, v ->
                    b.writeUUID(v.token)
                    b.writeLong(v.sequence)
                    b.writeLong(v.keys)
                    b.writeBoolean(v.release)
                },
                StreamDecoder { b ->
                    KeyboardHeldPayload(b.readUUID(), b.readLong().also { require(it >= 0) }, b.readLong(), b.readBoolean())
                },
            )
    }
}

internal data class KeyboardStatusPayload(
    val token: UUID?,
    val mask: Long,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<KeyboardStatusPayload>(Identifier.fromNamespaceAndPath("compukters", "keyboard_status"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, KeyboardStatusPayload> =
            StreamCodec.of(
                StreamEncoder { b, v ->
                    b.writeNullable(v.token) { sink, id -> sink.writeUUID(id) }
                    b.writeLong(v.mask)
                },
                StreamDecoder { b -> KeyboardStatusPayload(b.readNullable { it.readUUID() }, b.readLong()) },
            )
    }
}

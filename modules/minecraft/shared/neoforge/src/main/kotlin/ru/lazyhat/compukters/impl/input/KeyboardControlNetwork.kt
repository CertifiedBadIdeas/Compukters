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
import ru.lazyhat.compukters.core.input.KeyboardPerformanceTrace
import ru.lazyhat.compukters.impl.compat.Identifier
import ru.lazyhat.compukters.minecraft.input.ComputerKeyboard
import java.util.UUID

object KeyboardControlNetwork {
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("2")
        registrar.playToServer(KeyboardHeldPayload.TYPE, KeyboardHeldPayload.CODEC) { payload, context ->
            val player = context.player() as? ServerPlayer ?: return@playToServer
            val level = player.level() as? net.minecraft.server.level.ServerLevel ?: return@playToServer
            val now = level.server.overworld().gameTime
            KeyboardPerformanceTrace.begin("keyboard.receive")?.apply {
                capture = payload.token.toString()
                sequence = payload.sequence
                keys = payload.keys
                pressed = payload.pressed
                released = payload.released
                tick = now
                release = payload.release
                commit()
            }
            // Capture-owned admission retains burst edges and remains confined to the server thread.
            ComputerKeyboard.heartbeat(
                player,
                payload.token,
                payload.sequence,
                payload.keys,
                payload.release,
                payload.pressed,
                payload.released,
            )
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
    val pressed: Long = 0L,
    val released: Long = 0L,
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
                    b.writeLong(v.pressed)
                    b.writeLong(v.released)
                    b.writeBoolean(v.release)
                },
                StreamDecoder { b ->
                    val token = b.readUUID()
                    val sequence = b.readLong().also { require(it >= 0) }
                    val keys = b.readLong()
                    val pressed = b.readLong()
                    val released = b.readLong()
                    KeyboardHeldPayload(token, sequence, keys, b.readBoolean(), pressed, released)
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

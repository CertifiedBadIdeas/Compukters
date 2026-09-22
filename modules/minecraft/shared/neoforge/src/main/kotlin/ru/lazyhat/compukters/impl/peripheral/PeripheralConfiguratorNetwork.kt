/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package ru.lazyhat.compukters.impl.peripheral

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import ru.lazyhat.compukters.core.MOD_ID
import ru.lazyhat.compukters.impl.compat.Identifier
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorContext
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorEntry
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorServer
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSnapshot

object PeripheralConfiguratorNetwork {
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToClient(OpenPayload.TYPE, OpenPayload.CODEC, ::handleOpen)
        registrar.playToServer(SavePayload.TYPE, SavePayload.CODEC, ::handleSave)
        registrar.playToClient(SaveReplyPayload.TYPE, SaveReplyPayload.CODEC, ::handleReply)
        PeripheralConfiguratorServer.installOpener { player, hand, snapshot ->
            PacketDistributor.sendToPlayer(player, OpenPayload(hand, snapshot.toWire()))
        }
    }

    private fun handleOpen(
        payload: OpenPayload,
        @Suppress("UNUSED_PARAMETER") context: IPayloadContext,
    ) {
        Minecraft.getInstance().setScreen(PeripheralConfiguratorScreen(payload.hand, payload.snapshot.toDomain()))
    }

    private fun handleSave(
        payload: SavePayload,
        context: IPayloadContext,
    ) {
        val player = context.player() as? ServerPlayer ?: return
        context.reply(SaveReplyPayload(PeripheralConfiguratorServer.save(player, payload.hand, payload.context.toDomain(), payload.name)))
    }

    private fun handleReply(
        payload: SaveReplyPayload,
        @Suppress("UNUSED_PARAMETER") context: IPayloadContext,
    ) {
        (Minecraft.getInstance().screen as? PeripheralConfiguratorScreen)?.handleSave(payload.result)
    }
}

internal data class OpenPayload(
    val hand: InteractionHand,
    val snapshot: ConfiguratorSnapshotPayload,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<OpenPayload>(Identifier.fromNamespaceAndPath(MOD_ID, "peripheral_configurator_open"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, OpenPayload> = codec(::writeOpen, ::readOpen)
    }
}

internal data class SavePayload(
    val hand: InteractionHand,
    val context: ConfiguratorContextPayload,
    val name: String,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SavePayload>(Identifier.fromNamespaceAndPath(MOD_ID, "peripheral_configurator_save"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SavePayload> =
            codec(
                { buffer, value ->
                    buffer.writeEnum(value.hand)
                    writeContext(buffer, value.context)
                    buffer.writeUtf(value.name, 32)
                },
                { buffer -> SavePayload(buffer.readEnum(InteractionHand::class.java), readContext(buffer), buffer.readUtf(32)) },
            )
    }
}

internal data class ConfiguratorContextPayload(
    val position: BlockPos,
    val face: Direction,
)

internal data class ConfiguratorEntryPayload(
    val name: String,
    val providerId: String,
    val deviceKey: String,
    val duplicate: Boolean,
)

internal data class ConfiguratorSnapshotPayload(
    val context: ConfiguratorContextPayload,
    val configuredName: String,
    val entries: List<ConfiguratorEntryPayload>,
    val nameCounts: Map<String, Int>,
    val targetName: String?,
    val totalNamedDevices: Int,
    val truncated: Boolean,
    val topologyLimitExceeded: Boolean,
)

internal data class SaveReplyPayload(
    val result: PeripheralConfiguratorSaveResult,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SaveReplyPayload>(Identifier.fromNamespaceAndPath(MOD_ID, "peripheral_configurator_save_reply"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SaveReplyPayload> =
            codec(
                { buffer, value -> buffer.writeEnum(value.result) },
                { buffer -> SaveReplyPayload(buffer.readEnum(PeripheralConfiguratorSaveResult::class.java)) },
            )
    }
}

private fun writeOpen(
    buffer: RegistryFriendlyByteBuf,
    value: OpenPayload,
) {
    buffer.writeEnum(value.hand)
    writeContext(buffer, value.snapshot.context)
    buffer.writeUtf(value.snapshot.configuredName, 32)
    buffer.writeVarInt(value.snapshot.entries.size)
    value.snapshot.entries.forEach { entry ->
        buffer.writeUtf(entry.name, 32)
        buffer.writeUtf(entry.providerId, 128)
        buffer.writeUtf(entry.deviceKey, 128)
        buffer.writeBoolean(entry.duplicate)
    }
    buffer.writeVarInt(value.snapshot.nameCounts.size)
    value.snapshot.nameCounts.forEach { (name, count) ->
        buffer.writeUtf(name, 32)
        buffer.writeVarInt(count)
    }
    buffer.writeNullable(value.snapshot.targetName) { sink, name -> sink.writeUtf(name, 32) }
    buffer.writeVarInt(value.snapshot.totalNamedDevices)
    buffer.writeBoolean(value.snapshot.truncated)
    buffer.writeBoolean(value.snapshot.topologyLimitExceeded)
}

private fun readOpen(buffer: RegistryFriendlyByteBuf): OpenPayload {
    val hand = buffer.readEnum(InteractionHand::class.java)
    val context = readContext(buffer)
    val configured = buffer.readUtf(32)
    val entries =
        List(buffer.readVarInt().also { require(it in 0..64) }) {
            ConfiguratorEntryPayload(buffer.readUtf(32), buffer.readUtf(128), buffer.readUtf(128), buffer.readBoolean())
        }
    val counts =
        buildMap {
            repeat(buffer.readVarInt().also { require(it in 0..1024) }) {
                put(
                    buffer.readUtf(32),
                    buffer.readVarInt().also { count ->
                        require(count in 1..1024)
                    },
                )
            }
        }
    val targetName = buffer.readNullable { it.readUtf(32) }
    val total = buffer.readVarInt().also { require(it in 0..1024) }
    return OpenPayload(
        hand,
        ConfiguratorSnapshotPayload(context, configured, entries, counts, targetName, total, buffer.readBoolean(), buffer.readBoolean()),
    )
}

private fun writeContext(
    buffer: RegistryFriendlyByteBuf,
    context: ConfiguratorContextPayload,
) {
    buffer.writeBlockPos(context.position)
    buffer.writeEnum(context.face)
}

private fun readContext(buffer: RegistryFriendlyByteBuf) =
    ConfiguratorContextPayload(
        buffer.readBlockPos(),
        buffer.readEnum(Direction::class.java),
    )

internal fun PeripheralConfiguratorContext.toWire() = ConfiguratorContextPayload(position, face)

private fun ConfiguratorContextPayload.toDomain() = PeripheralConfiguratorContext(position, face)

private fun PeripheralConfiguratorSnapshot.toWire() =
    ConfiguratorSnapshotPayload(
        context.toWire(),
        configuredName,
        entries.map { ConfiguratorEntryPayload(it.name, it.providerId, it.deviceKey, it.duplicate) },
        nameCounts,
        targetName,
        totalNamedDevices,
        truncated,
        topologyLimitExceeded,
    )

private fun ConfiguratorSnapshotPayload.toDomain() =
    PeripheralConfiguratorSnapshot(
        context.toDomain(),
        configuredName,
        entries.map { PeripheralConfiguratorEntry(it.name, it.providerId, it.deviceKey, it.duplicate) },
        nameCounts,
        targetName,
        totalNamedDevices,
        truncated,
        topologyLimitExceeded,
    )

private fun <T : Any> codec(
    writer: (RegistryFriendlyByteBuf, T) -> Unit,
    reader: (RegistryFriendlyByteBuf) -> T,
): StreamCodec<RegistryFriendlyByteBuf, T> = StreamCodec.of(StreamEncoder(writer), StreamDecoder(reader))

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

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.InteractionHand
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorMode
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralConfiguratorSaveResult
import ru.lazyhat.compukters.minecraft.peripheral.PeripheralNetworkAvailability
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeripheralConfiguratorPayloadTest {
    @Test
    fun `open save and reply payloads round trip bounded context`() {
        val context =
            ConfiguratorContextPayload(
                BlockPos(2, 64, -3),
                Direction.NORTH,
                UUID.randomUUID(),
            )
        val open =
            OpenPayload(
                InteractionHand.MAIN_HAND,
                ConfiguratorSnapshotPayload(
                    context,
                    PeripheralConfiguratorMode.INSPECT_NETWORK,
                    "motor",
                    listOf(
                        ConfiguratorEntryPayload("motor", "create", "rotation_controller", false),
                        ConfiguratorEntryPayload(
                            null,
                            "create",
                            "speedometer",
                            false,
                            UUID.randomUUID(),
                            PeripheralNetworkAvailability.OUT_OF_RANGE,
                        ),
                    ),
                    mapOf("motor" to 1, "shared" to 2),
                    "motor",
                    3,
                    true,
                    false,
                ),
            )

        assertEquals(open, roundTrip(OpenPayload.CODEC, open))
        val save = SavePayload(InteractionHand.OFF_HAND, context, "backup")
        assertEquals(save, roundTrip(SavePayload.CODEC, save))
        val reply = SaveReplyPayload(PeripheralConfiguratorSaveResult.CONFLICT)
        assertEquals(reply, roundTrip(SaveReplyPayload.CODEC, reply))
        val remove = RemoveMemberPayload(InteractionHand.MAIN_HAND, context, UUID.randomUUID())
        assertEquals(remove, roundTrip(RemoveMemberPayload.CODEC, remove))
    }

    @Test
    fun `oversized network snapshot is rejected before entries are read`() {
        val buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(Unpooled.buffer())
        buffer.writeEnum(InteractionHand.MAIN_HAND)
        buffer.writeBlockPos(BlockPos.ZERO)
        buffer.writeEnum(Direction.UP)
        buffer.writeNullable<UUID>(null) { sink, value -> sink.writeUUID(value) }
        buffer.writeEnum(PeripheralConfiguratorMode.INSPECT_NETWORK)
        buffer.writeUtf("factory", 32)
        buffer.writeVarInt(1025)
        assertFailsWith<IllegalArgumentException> { OpenPayload.CODEC.decode(buffer) }
    }

    private fun <T : Any> roundTrip(
        codec: StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
    ): T {
        val buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(Unpooled.buffer())
        codec.encode(buffer, value)
        return codec.decode(buffer)
    }
}

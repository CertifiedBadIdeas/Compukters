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
import kotlin.test.Test
import kotlin.test.assertEquals

class PeripheralConfiguratorPayloadTest {
    @Test
    fun `open save and reply payloads round trip bounded context`() {
        val context =
            ConfiguratorContextPayload(
                BlockPos(2, 64, -3),
                Direction.NORTH,
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
                        ConfiguratorEntryPayload(null, "create", "speedometer", false),
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

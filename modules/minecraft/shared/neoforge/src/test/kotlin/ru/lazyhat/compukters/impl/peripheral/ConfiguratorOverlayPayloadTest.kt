/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.peripheral

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.world.InteractionHand
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlayDevice
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlayScreen
import ru.lazyhat.compukters.minecraft.peripheral.ConfiguratorOverlaySnapshot
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfiguratorOverlayPayloadTest {
    private fun buffer() = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(Unpooled.buffer())

    @Test
    fun `nearby groups holes and request ownership survive transport`() {
        val id = UUID.randomUUID()
        val request = OverlayRequest(InteractionHand.OFF_HAND, 42)
        val reply =
            OverlayReply(
                42,
                "minecraft:overworld",
                ConfiguratorOverlaySnapshot(
                    listOf(ConfiguratorOverlayDevice(BlockPos.ZERO, "panel", id, "factory", id)),
                    listOf(ConfiguratorOverlayScreen(id, "wall", BlockPos.ZERO, Direction.NORTH, 3, 2, listOf(BlockPos.ZERO))),
                    true,
                ),
            )
        buffer().let { b ->
            try {
                OverlayRequest.CODEC.encode(b, request)
                assertEquals(request, OverlayRequest.CODEC.decode(b))
            } finally {
                b.release()
            }
        }
        buffer().let { b ->
            try {
                OverlayReply.CODEC.encode(b, reply)
                assertEquals(reply, OverlayReply.CODEC.decode(b))
            } finally {
                b.release()
            }
        }
    }

    @Test
    fun `oversized device lists are rejected before allocating entries`() {
        val b = buffer()
        try {
            b.writeLong(1)
            b.writeUtf("minecraft:overworld")
            b.writeBoolean(false)
            b.writeVarInt(513)
            assertFailsWith<IllegalArgumentException> { OverlayReply.CODEC.decode(b) }
        } finally {
            b.release()
        }
    }
}

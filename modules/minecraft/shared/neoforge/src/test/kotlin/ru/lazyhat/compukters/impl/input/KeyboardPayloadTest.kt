/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.input

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeyboardPayloadTest {
    @Test
    fun `held release and stop round trip including the signed last key`() {
        val token = UUID.randomUUID()
        for (release in listOf(false, true)) {
            val value = KeyboardHeldPayload(token, Long.MAX_VALUE, Long.MIN_VALUE or 1L, release, 1L, Long.MIN_VALUE)
            assertEquals(value, roundTrip(KeyboardHeldPayload.CODEC, value, 49))
        }
        val tap = KeyboardHeldPayload(token, 0, 0, false, Long.MIN_VALUE, Long.MIN_VALUE)
        assertEquals(tap, roundTrip(KeyboardHeldPayload.CODEC, tap, 49))
        for (value in listOf(KeyboardStatusPayload(token, -1L), KeyboardStatusPayload(null, 0))) {
            assertEquals(value, roundTrip(KeyboardStatusPayload.CODEC, value))
        }
    }

    @Test
    fun `negative sequence is rejected at the boundary`() {
        assertFailsWith<IllegalArgumentException> {
            roundTrip(KeyboardHeldPayload.CODEC, KeyboardHeldPayload(UUID.randomUUID(), -1, 0, false))
        }
    }

    private fun <T : Any> roundTrip(
        codec: StreamCodec<RegistryFriendlyByteBuf, T>,
        value: T,
        expectedBytes: Int? = null,
    ): T {
        val buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(Unpooled.buffer())
        try {
            codec.encode(buffer, value)
            if (expectedBytes != null) assertEquals(expectedBytes, buffer.readableBytes())
            return codec.decode(buffer).also { assertEquals(0, buffer.readableBytes()) }
        } finally {
            buffer.release()
        }
    }
}

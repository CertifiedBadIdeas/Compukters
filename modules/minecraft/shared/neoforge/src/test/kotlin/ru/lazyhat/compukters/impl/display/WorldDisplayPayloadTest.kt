/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import ru.lazyhat.compukters.minecraft.display.WorldDisplayTile
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorldDisplayPayloadTest {
    @Test
    fun `full tiles and rectangle deltas round trip at every density`() {
        for ((mode, density) in listOf(16, 32, 64, 128).withIndex()) {
            val full =
                WorldDisplayTile(
                    Long.MIN_VALUE,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    Long.MAX_VALUE,
                    mode,
                    127,
                    listOf(Long.MIN_VALUE, Long.MAX_VALUE),
                    -1,
                    0,
                    0,
                    density,
                    density,
                    ByteArray(density * density * 3) { it.toByte() },
                )
            val values =
                listOf(
                    full,
                    full.copy(base = 1, x = density - 1, y = 0, width = 1, height = 1, rgb = byteArrayOf(1, 2, 3)),
                    full.copy(base = 1, width = 0, height = 0, rgb = byteArrayOf()),
                )
            for (tile in values) {
                val b = buffer()
                try {
                    WorldDisplayPayload.CODEC.encode(b, WorldDisplayPayload(tile))
                    val decoded = WorldDisplayPayload.CODEC.decode(b).tile
                    assertContentEquals(tile.rgb, decoded.rgb)
                    assertEquals(tile, decoded.copy(rgb = tile.rgb))
                    assertEquals(0, b.readableBytes())
                } finally {
                    b.release()
                }
            }
        }
    }

    @Test
    fun `decoder rejects oversized membership and pixel allocation`() {
        for (oversizedMembers in listOf(false, true)) {
            val b = buffer()
            try {
                b.writeLong(1)
                b.writeUUID(UUID.randomUUID())
                b.writeUUID(UUID.randomUUID())
                b.writeLong(2)
                b.writeByte(3)
                b.writeByte(255)
                b.writeVarInt(if (oversizedMembers) 65 else 1)
                if (!oversizedMembers) {
                    b.writeLong(1)
                    b.writeLong(-1)
                    b.writeVarInt(0)
                    b.writeVarInt(0)
                    b.writeVarInt(128)
                    b.writeVarInt(128)
                    b.writeByteArray(ByteArray(128 * 128 * 3 + 1))
                }
                assertFailsWith<RuntimeException> { WorldDisplayPayload.CODEC.decode(b) }
            } finally {
                b.release()
            }
        }
    }

    private fun buffer(): RegistryFriendlyByteBuf = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(Unpooled.buffer())
}

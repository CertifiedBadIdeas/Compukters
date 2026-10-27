/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.impl.display

import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import ru.lazyhat.compukters.minecraft.display.AttachedDisplayMessage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AttachedDisplayPayloadTest {
    @Test
    fun `manifest full image delta removal and recovery round trip`() {
        val surface = UUID.randomUUID()
        val instance = UUID.randomUUID()
        for (face in 0..5) {
            val manifest =
                AttachedDisplayMessage.Manifest(
                    surface,
                    10,
                    3,
                    face,
                    128,
                    listOf(AttachedDisplayMessage.Member(BlockPos(1, 2, 3), instance)),
                )
            assertEquals(manifest, roundTrip(manifest))
        }
        for (tile in listOf(
            AttachedDisplayMessage.Tile(surface, instance, 10, -1, 0, 0, 128, 128, ByteArray(128 * 128 * 3) { it.toByte() }),
            AttachedDisplayMessage.Tile(surface, instance, 11, 10, 1, 2, 2, 1, byteArrayOf(1, 2, 3, 4, 5, 6)),
            AttachedDisplayMessage.Tile(surface, instance, 12, 11, 0, 0, 0, 0, byteArrayOf()),
        )) {
            val result = roundTrip(tile) as AttachedDisplayMessage.Tile
            assertEquals(
                listOf(tile.surface, tile.instance, tile.publication, tile.base, tile.x, tile.y, tile.width, tile.height),
                listOf(result.surface, result.instance, result.publication, result.base, result.x, result.y, result.width, result.height),
            )
            assertContentEquals(tile.rgb, result.rgb)
        }
        val removed = AttachedDisplayMessage.Remove(surface)
        assertEquals(removed, roundTrip(removed))
        val b = buffer()
        try {
            val request = AttachedDisplaySnapshotRequest(surface)
            AttachedDisplaySnapshotRequest.CODEC.encode(b, request)
            assertEquals(request, AttachedDisplaySnapshotRequest.CODEC.decode(b))
        } finally {
            b.release()
        }
    }

    @Test
    fun `oversized manifest is rejected before allocating or reading members`() {
        val b = buffer()
        try {
            b.writeByte(0)
            b.writeUUID(UUID.randomUUID())
            b.writeLong(1)
            b.writeByte(0)
            b.writeByte(0)
            b.writeByte(255)
            b.writeVarInt(65)
            assertFailsWith<IllegalArgumentException> { AttachedDisplayPayload.CODEC.decode(b) }
        } finally {
            b.release()
        }
    }

    private fun buffer() = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(Unpooled.buffer())

    private fun roundTrip(message: AttachedDisplayMessage): AttachedDisplayMessage {
        val b = buffer()
        try {
            AttachedDisplayPayload.CODEC.encode(b, AttachedDisplayPayload(message))
            return AttachedDisplayPayload.CODEC.decode(b).message
        } finally {
            b.release()
        }
    }
}

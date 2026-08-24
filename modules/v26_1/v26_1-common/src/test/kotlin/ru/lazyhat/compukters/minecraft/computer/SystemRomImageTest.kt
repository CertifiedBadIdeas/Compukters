/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.minecraft.computer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SystemRomImageTest {
    @Test
    fun `program ROM is canonical executable and deterministic`() {
        val boot = byteArrayOf(9, 8)
        val shell = byteArrayOf(1, 2, 3, 4)
        val kotlinc = byteArrayOf(5, 6, 7)
        val first = SystemRomImage.encodePrograms(boot, shell, kotlinc)
        boot.fill(0)
        shell.fill(0)
        kotlinc.fill(0)
        val second = SystemRomImage.encodePrograms(byteArrayOf(9, 8), byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7))
        assertContentEquals(first, second)

        val payload = first.copyOf(first.size - 32)
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(payload), first.copyOfRange(payload.size, first.size))
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("CPKTROM\u0000".encodeToByteArray(), ByteArray(8).also(buffer::get))
        assertEquals(1, buffer.short.toInt())
        assertEquals(0, buffer.short.toInt())
        assertEquals(3, buffer.int)
        assertEntry(buffer, "/rom/boot", byteArrayOf(9, 8))
        assertEntry(buffer, "/rom/kotlinc", byteArrayOf(5, 6, 7))
        assertEntry(buffer, "/rom/shell", byteArrayOf(1, 2, 3, 4))
        assertEquals(0, buffer.remaining())
    }

    private fun assertEntry(
        buffer: ByteBuffer,
        expectedPath: String,
        expectedArtifact: ByteArray,
    ) {
        val path = ByteArray(buffer.int).also(buffer::get).decodeToString()
        assertEquals(expectedPath, path)
        assertEquals(2, buffer.get().toInt())
        assertEquals(1, buffer.get().toInt())
        assertEquals(0, buffer.short.toInt())
        assertEquals(expectedArtifact.size.toLong(), buffer.long)
        assertContentEquals(expectedArtifact, ByteArray(expectedArtifact.size).also(buffer::get))
    }
}

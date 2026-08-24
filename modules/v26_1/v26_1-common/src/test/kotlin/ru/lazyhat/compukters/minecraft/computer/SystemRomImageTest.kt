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
    fun `shell ROM is canonical executable and deterministic`() {
        val shell = byteArrayOf(1, 2, 3, 4)
        val first = SystemRomImage.encodeShell(shell)
        shell.fill(0)
        val second = SystemRomImage.encodeShell(byteArrayOf(1, 2, 3, 4))
        assertContentEquals(first, second)

        val payload = first.copyOf(first.size - 32)
        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(payload), first.copyOfRange(payload.size, first.size))
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertContentEquals("CPKTROM\u0000".encodeToByteArray(), ByteArray(8).also(buffer::get))
        assertEquals(1, buffer.short.toInt())
        assertEquals(0, buffer.short.toInt())
        assertEquals(1, buffer.int)
        val path = ByteArray(buffer.int).also(buffer::get).decodeToString()
        assertEquals("/rom/shell", path)
        assertEquals(2, buffer.get().toInt())
        assertEquals(1, buffer.get().toInt())
        assertEquals(0, buffer.short.toInt())
        assertEquals(4, buffer.long)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), ByteArray(4).also(buffer::get))
        assertEquals(0, buffer.remaining())
    }
}

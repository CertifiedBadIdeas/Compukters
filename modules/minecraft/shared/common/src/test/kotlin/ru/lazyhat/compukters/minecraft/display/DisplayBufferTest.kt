/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.minecraft.display

import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayBufferTest {
    @Test
    fun `writes stay within the fixed grid and preserve unicode code points`() {
        val buffer = DisplayBuffer()
        val owner = Any()
        assertEquals(DisplayWriteResult.SUCCESS, buffer.writeAt(owner, { true }, 18, 9, "Ж😀"))
        assertEquals("                  Ж😀", buffer.rows()[9])
        assertEquals(DisplayWriteResult.INVALID, buffer.writeAt(owner, { true }, 19, 9, "AB"))
        assertEquals(DisplayWriteResult.INVALID, buffer.writeAt(owner, { true }, -1, 0, "A"))
        assertEquals(DisplayWriteResult.INVALID, buffer.writeAt(owner, { true }, 0, 0, "A\nB"))
        assertEquals("                  Ж😀", buffer.rows()[9])
    }

    @Test
    fun `one writer holds the screen until it stops or loses reachability`() {
        val buffer = DisplayBuffer()
        val first = Any()
        val second = Any()
        var connected = true
        assertEquals(DisplayWriteResult.SUCCESS, buffer.writeAt(first, { connected }, 0, 0, "First"))
        assertEquals(DisplayWriteResult.BUSY, buffer.writeAt(second, { true }, 0, 0, "Second"))
        connected = false
        buffer.tick()
        assertEquals(" ".repeat(DisplayBuffer.WIDTH), buffer.rows()[0])
        assertEquals(DisplayWriteResult.SUCCESS, buffer.writeAt(second, { true }, 0, 0, "Second"))
        buffer.release(second)
        assertEquals(" ".repeat(DisplayBuffer.WIDTH), buffer.rows()[0])
    }

    @Test
    fun `invalid and disconnected writes never acquire the screen`() {
        val buffer = DisplayBuffer()
        val first = Any()
        val second = Any()
        assertEquals(DisplayWriteResult.INVALID, buffer.writeAt(first, { true }, 0, 0, "\uD800"))
        assertEquals(DisplayWriteResult.DISCONNECTED, buffer.writeAt(first, { false }, 0, 0, "x"))
        assertEquals(DisplayWriteResult.SUCCESS, buffer.clear(second) { true })
        assertEquals(DisplayWriteResult.BUSY, buffer.writeAt(first, { true }, 0, 0, "x"))
    }

    @Test
    fun `client snapshot replaces a whole grid only when every row is valid`() {
        val buffer = DisplayBuffer()
        val rows = List(DisplayBuffer.HEIGHT) { " ".repeat(DisplayBuffer.WIDTH) }.toMutableList()
        rows[0] = "Ready" + " ".repeat(DisplayBuffer.WIDTH - 5)
        assertEquals(true, buffer.applySnapshot(rows))
        assertEquals(rows, buffer.rows())
        rows[9] = "short"
        assertEquals(false, buffer.applySnapshot(rows))
        assertEquals(" ".repeat(DisplayBuffer.WIDTH), buffer.rows()[9])
    }
}

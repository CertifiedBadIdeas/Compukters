/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DisplayCanvasTest {
    @Test
    fun `published pixels outlive the writer and round trip all RGB channels`() {
        val screen = DisplayCanvas(2, 1, 0)
        val owner = Any()
        var connected = true
        screen.acquire(owner) { connected }
        screen.pixel(owner, 19, 5, 0xFF8001)
        connected = false
        screen.expire()
        assertFalse(screen.owns(owner))
        assertEquals(0xFF8001, screen.pixelAt(19, 5))
        val restored = DisplayCanvas(2, 1)
        restored.restoreRgb(screen.mode, screen.encodeRgb())
        assertEquals(0, restored.mode)
        assertContentEquals(screen.encodeRgb(), restored.encodeRgb())
        assertFailsWith<IllegalArgumentException> { restored.restoreRgb(0, byteArrayOf(1)) }
        assertEquals(0xFF8001, restored.pixelAt(19, 5))
    }

    @Test
    fun `frames publish together and abandoned drafts never replace a published image`() {
        val screen = DisplayCanvas(1, 1, 0)
        val owner = Any()
        screen.acquire(owner) { true }
        screen.begin(owner)
        screen.pixel(owner, 0, 0, 1)
        screen.pixel(owner, 1, 0, 2)
        assertEquals(0, screen.pixelAt(0, 0))
        screen.finish(owner, true)
        assertEquals(1, screen.pixelAt(0, 0))
        assertEquals(2, screen.pixelAt(1, 0))
        screen.begin(owner)
        screen.setMode(owner, 3)
        assertEquals(0, screen.mode)
        assertEquals(128, screen.activeWidth(owner))
        screen.release(owner)
        assertEquals(0, screen.mode)
        assertEquals(1, screen.pixelAt(0, 0))
    }

    @Test
    fun `hibernation retains private frame pixels and work without replaying the published image`() {
        val screen = DisplayCanvas(1, 1, 0)
        val owner = Any()
        screen.acquire(owner) { true }
        screen.pixel(owner, 0, 0, 111)
        screen.begin(owner)
        screen.setMode(owner, 1)
        screen.pixel(owner, 0, 0, 222)
        val draft = requireNotNull(screen.frameSnapshot(owner))
        val work = screen.frameWorkUsed(owner)
        screen.release(owner)
        val other = Any()
        screen.acquire(other) { true }
        screen.pixel(other, 0, 0, 333)
        screen.release(other)
        screen.acquire(owner) { true }
        screen.restoreFrame(owner, draft.first, draft.second, work)
        assertEquals(333, screen.pixelAt(0, 0))
        assertEquals(work, screen.frameWorkUsed(owner))
        screen.finish(owner, true)
        assertEquals(1, screen.mode)
        assertEquals(222, screen.pixelAt(0, 0))
    }

    @Test
    fun `mode selection persists while selecting the same mode retains pixels`() {
        val screen = DisplayCanvas(1, 2)
        val owner = Any()
        screen.acquire(owner) { true }
        screen.pixel(owner, 0, 0, 123)
        screen.setMode(owner, 2)
        assertEquals(123, screen.pixelAt(0, 0))
        screen.setMode(owner, 1)
        assertEquals(32, screen.width)
        assertEquals(64, screen.height)
        assertEquals(0, screen.pixelAt(0, 0))
        screen.release(owner)
        assertEquals(1, screen.mode)
    }

    @Test
    fun `clipping handles extreme coordinates without excessive loops or overflow`() {
        val screen = DisplayCanvas(1, 1, 0)
        val owner = Any()
        screen.acquire(owner) { true }
        screen.line(owner, Int.MIN_VALUE, 8, Int.MAX_VALUE, 8, 0x00FF00)
        for (x in 0 until 16) assertEquals(0x00FF00, screen.pixelAt(x, 8))
        screen.fill(owner, -3, -2, 5, 4, 0xFF0000)
        assertEquals(0xFF0000, screen.pixelAt(1, 1))
        assertEquals(0, screen.pixelAt(2, 1))
        screen.imageRow(owner, -1, 15, intArrayOf(1, 2, 3))
        assertEquals(2, screen.pixelAt(0, 15))
        assertEquals(3, screen.pixelAt(1, 15))
    }

    @Test
    fun `leases limits and mode admission reject mutation before changing published state`() {
        val screen = DisplayCanvas(1, 1, 0, admitMode = { it < 2 })
        val owner = Any()
        screen.acquire(owner) { true }
        assertFailsWith<IllegalStateException> { screen.acquire(Any()) { true } }
        assertFailsWith<IllegalStateException> { screen.setMode(owner, 3) }
        assertEquals(0, screen.mode)
        assertFailsWith<IllegalArgumentException> { screen.pixel(owner, 0, 0, -1) }
        assertFailsWith<IllegalArgumentException> { DisplayCanvas(9, 1) }
        screen.begin(owner)
        assertFailsWith<IllegalStateException> { screen.begin(owner) }
        screen.finish(owner, false)
        assertEquals(0, screen.pixelAt(0, 0))
    }

    @Test
    fun `a tile is the corresponding portion of the shared image including missing panels`() {
        val screen = DisplayCanvas(2, 2, 0)
        val owner = Any()
        screen.acquire(owner) { true }
        screen.pixel(owner, 16, 16, 0x123456)
        assertContentEquals(byteArrayOf(0x12, 0x34, 0x56), screen.tile(1, 1).take(3).toByteArray())
        assertContentEquals(byteArrayOf(0, 0, 0), screen.tile(0, 0).take(3).toByteArray())
    }
}

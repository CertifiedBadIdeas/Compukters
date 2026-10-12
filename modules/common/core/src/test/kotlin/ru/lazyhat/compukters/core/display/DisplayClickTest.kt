/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.display

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisplayClickTest {
    @Test
    fun `renderer coordinates include panel offsets for each facing and density`() {
        for (density in DisplayCanvas.DENSITIES) {
            val expected = DisplayClick(2 * density + density / 4, density + density / 4)
            assertEquals(expected, displayClickAt(0, 2, 1, density, .75, .75, 0.0))
            assertEquals(expected, displayClickAt(1, 2, 1, density, .25, .75, 1.0))
            assertEquals(expected, displayClickAt(2, 2, 1, density, 0.0, .75, .25))
            assertEquals(expected, displayClickAt(3, 2, 1, density, 1.0, .75, .75))
            assertEquals(DisplayClick(density - 1, density - 1), displayClickAt(1, 0, 0, density, 1.0, 0.0, 1.0))
        }
        assertNull(displayClickAt(1, 0, 0, 16, Double.NaN, .5, 1.0))
        assertNull(displayClickAt(1, 0, 0, 16, 1.01, .5, 1.0))
    }

    @Test
    fun `input is bounded FIFO and exclusive to the live writer`() {
        val canvas = DisplayCanvas(5, 2, 0)
        val owner = Any()
        var valid = true
        assertFalse(canvas.offerClick(1, 1))
        canvas.acquire(owner) { valid }
        repeat(64) { assertTrue(canvas.offerClick(it, 2)) }
        assertFalse(canvas.offerClick(0, 0))
        assertFalse(canvas.offerClick(-1, 0))
        assertFailsWith<IllegalStateException> { canvas.pollClick(Any()) }
        repeat(64) { assertEquals(DisplayClick(it, 2), canvas.pollClick(owner)) }
        assertNull(canvas.pollClick(owner))
        canvas.offerClick(0, 0)
        valid = false
        canvas.expire()
        val replacement = Any()
        canvas.acquire(replacement) { true }
        assertNull(canvas.pollClick(replacement))
        assertFalse(canvas.owns(owner))
    }

    @Test
    fun `only published resize clears input and restart never replays it`() {
        val canvas = DisplayCanvas(1, 1, 0)
        val owner = Any()
        canvas.acquire(owner) { true }
        canvas.offerClick(1, 2)
        canvas.begin(owner)
        canvas.setMode(owner, 1)
        canvas.finish(owner, false)
        assertEquals(DisplayClick(1, 2), canvas.pollClick(owner))
        canvas.offerClick(1, 2)
        canvas.begin(owner)
        canvas.setMode(owner, 1)
        canvas.finish(owner, true)
        assertNull(canvas.pollClick(owner))
        canvas.offerClick(1, 2)
        canvas.setMode(owner, 2)
        assertNull(canvas.pollClick(owner))
        canvas.offerClick(1, 2)
        canvas.release(owner)
        canvas.acquire(owner) { true }
        assertNull(canvas.pollClick(owner))
        canvas.offerClick(1, 2)
        canvas.restoreRgb(canvas.mode, canvas.encodeRgb())
        canvas.acquire(owner) { true }
        assertNull(canvas.pollClick(owner))
    }
}

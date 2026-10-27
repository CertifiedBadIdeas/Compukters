/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardInputStateTest {
    @Test fun `press and release survive between reads while held state stays current`() {
        val state = KeyboardInputState(3)
        state.connect(10)
        assertTrue(state.update(0, 1, 10))
        assertTrue(state.update(1, 0, 11))
        val frame = state.poll(12)
        assertEquals(0L, frame.held)
        assertEquals(1L, frame.pressed)
        assertEquals(1L, frame.released)
        assertEquals(0L, state.poll(12).pressed)
        assertEquals(0L, state.poll(12).released)
        state.update(2, 2, 13)
        assertEquals(2L, state.poll(13).held)
        assertEquals(2L, state.poll(14).held)
        assertEquals(0L, state.poll(14).pressed)
    }

    @Test fun `stale or unrequested input cannot overwrite a newer sample`() {
        val state = KeyboardInputState(1)
        state.connect(10)
        assertTrue(state.update(4, 1, 10))
        assertFalse(state.update(3, 0, 11))
        assertFalse(state.update(5, 2, 11))
        assertFalse(state.update(5, 0, 9))
        assertEquals(1L, state.poll(11).held)
    }

    @Test fun `heartbeat loss and explicit exit clear keys and reconnect starts empty`() {
        val state = KeyboardInputState(-1L)
        state.connect(20)
        state.update(0, Long.MIN_VALUE, 20)
        assertTrue(state.poll(25).connected)
        val expired = state.poll(26)
        assertFalse(expired.connected)
        assertEquals(0L, expired.held)
        assertEquals(Long.MIN_VALUE, expired.released)
        assertFalse(state.update(1, 1, 27))
        state.connect(28)
        assertEquals(0L, state.poll(28).held)
        state.update(0, 3, 28)
        state.disconnect()
        val exited = state.poll(29)
        assertEquals(0L, exited.held)
        assertEquals(0L, exited.pressed)
        assertEquals(3L, exited.released)
    }
}

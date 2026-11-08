/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardTransportTest {
    @Test
    fun `release and repress inside one client tick produce distinct immediate updates`() {
        val sent = mutableListOf<KeyboardInputUpdate>()
        val sender = KeyboardInputSender(1, sent::add)
        sender.keyDown(65)
        sender.keyUp(65)
        sender.keyDown(65)
        assertEquals(listOf(1L, 0L, 1L), sent.map { it.keys })
        assertEquals(listOf(0L, 1L, 2L), sent.map { it.sequence })
        assertEquals(1L, sent[1].released)
        assertEquals(1L, sent[2].pressed)
        repeat(20) { sender.keyDown(65) }
        assertEquals(3, sent.size, "GLFW repeats must not send new transitions")
    }

    @Test
    fun `held keys use sparse heartbeats and reconciliation repairs missed physical release`() {
        val sent = mutableListOf<KeyboardInputUpdate>()
        val sender = KeyboardInputSender(1, sent::add)
        sender.keyDown(65)
        sender.tick(setOf(65))
        assertEquals(1, sent.size)
        sender.tick(setOf(65))
        assertEquals(2, sent.size)
        assertEquals(0L, sent.last().pressed)
        sender.tick(emptySet())
        assertEquals(0L, sent.last().keys)
        assertEquals(1L, sent.last().released)
    }

    @Test
    fun `left and right modifiers share one logical key without releasing each other`() {
        val bit = 1L shl KeyboardKeys.index(340)
        val sent = mutableListOf<KeyboardInputUpdate>()
        val sender = KeyboardInputSender(bit, sent::add)
        sender.keyDown(340)
        sender.keyDown(344)
        sender.keyUp(340)
        assertEquals(1, sent.size)
        sender.keyUp(344)
        assertEquals(2, sent.size)
        assertEquals(bit, sent.last().released)
        sender.keyDown(65)
        assertEquals(2, sent.size, "unrequested keys must not enter a captured sample")
    }

    @Test
    fun `client burst overflow is bounded and retains a complete short tap`() {
        val sent = mutableListOf<KeyboardInputUpdate>()
        val sender = KeyboardInputSender(1, sent::add)
        repeat(KeyboardInputSender.MAX_UPDATES_PER_TICK / 2) {
            sender.keyDown(65)
            sender.keyUp(65)
        }
        sender.keyDown(65)
        sender.keyUp(65)
        assertEquals(KeyboardInputSender.MAX_UPDATES_PER_TICK, sent.size)
        sender.tick(emptySet())
        assertEquals(KeyboardInputSender.MAX_UPDATES_PER_TICK + 1, sent.size)
        assertEquals(0L, sent.last().keys)
        assertEquals(1L, sent.last().pressed)
        assertEquals(1L, sent.last().released)
    }

    @Test
    fun `server coalesces a burst in one slot while retaining both edges and latest held state`() {
        val gate = KeyboardInputGate(1)
        val state = KeyboardInputState(1)
        state.connect(10)
        repeat(40) { sequence ->
            val down = sequence % 2 == 0
            val update = KeyboardInputUpdate(sequence.toLong(), if (down) 1 else 0, if (down) 1 else 0, if (down) 0 else 1)
            val admission = gate.offer(update, 10)
            assertTrue(admission.accepted)
            admission.ready?.let { state.update(it.sequence, it.keys, 10, it.pressed, it.released) }
            if (sequence >= KeyboardInputGate.MAX_IMMEDIATE_PER_TICK) assertNull(admission.ready)
        }
        val tail = requireNotNull(gate.drain(10))
        assertEquals(39L, tail.sequence)
        state.update(tail.sequence, tail.keys, 10, tail.pressed, tail.released)
        val frame = state.poll(10)
        assertEquals(0L, frame.held)
        assertEquals(1L, frame.pressed)
        assertEquals(1L, frame.released)
        assertNull(gate.drain(10))
        assertEquals(0L, state.poll(10).pressed)
    }

    @Test
    fun `invalid or stale updates cannot poison gate sequence or consume a valid transition`() {
        val gate = KeyboardInputGate(1)
        assertTrue(gate.offer(KeyboardInputUpdate(2, 1, 1, 0), 10).accepted)
        for (bad in listOf(
            KeyboardInputUpdate(1, 0, 0, 1),
            KeyboardInputUpdate(3, 2, 0, 0),
            KeyboardInputUpdate(3, 0, 2, 0),
            KeyboardInputUpdate(3, 0, 0, 2),
            KeyboardInputUpdate(-1, 0, 0, 0),
        )) {
            assertFalse(gate.offer(bad, 10).accepted)
        }
        assertFalse(gate.offer(KeyboardInputUpdate(3, 0, 0, 1), 9).accepted)
        assertTrue(gate.offer(KeyboardInputUpdate(3, 0, 0, 1), 10).accepted)
        gate.reset()
        assertNull(gate.drain(11))
        assertTrue(gate.offer(KeyboardInputUpdate(0, 1, 1, 0), 11).accepted)
    }

    @Test
    fun `explicit coalesced edges reach the existing snapshot with unchanged wire layout`() {
        val state = KeyboardInputState(Long.MIN_VALUE)
        state.connect(1)
        assertTrue(state.update(0, 0, 1, Long.MIN_VALUE, Long.MIN_VALUE))
        val frame = state.poll(1)
        assertEquals(0L, frame.held)
        assertEquals(Long.MIN_VALUE, frame.pressed)
        assertEquals(Long.MIN_VALUE, frame.released)
        assertEquals(34, frame.encode().size)
        assertFalse(state.update(1, 0, 1, 1, 0))
        assertEquals(0L, state.poll(1).pressed)
    }
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.core.redstone

import ru.lazyhat.compukters.core.device.runtime.peripheral.PeripheralFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RedstonePortStateTest {
    @Test
    fun `all outputs change atomically before one notification and invalid levels do not mutate them`() {
        val notifications = mutableListOf<Pair<Int, List<Int>>>()
        lateinit var state: RedstonePortState
        state = RedstonePortState { mask -> notifications += mask to (0..5).map(state::output) }
        val lease = Any()
        state.acquire(lease) { true }
        state.setAll(lease, 31)
        assertEquals(listOf(63 to List(6) { 31 }), notifications)
        state.setAll(lease, 31)
        assertEquals(1, notifications.size)
        assertFailsWith<IllegalArgumentException> { state.setAll(lease, 32) }
        assertEquals(List(6) { 31 }, (0..5).map(state::output))
        state.release(lease)
        assertEquals(63 to List(6) { 0 }, notifications.last())
        assertFailsWith<PeripheralFailure> { state.setAll(lease, 15) }
    }

    @Test
    fun `one process shares ownership across handles and last release clears every face`() {
        val notifications = mutableListOf<Int>()
        val state = RedstonePortState(notifications::add)
        val firstHandle = Any()
        val secondHandle = Any()
        state.acquire(firstHandle) { true }
        state.acquire(secondHandle) { true }
        state.set(firstHandle, 0, 15)
        state.set(secondHandle, 4, 16 or 7)
        state.release(secondHandle)
        assertEquals(15, state.output(0))
        assertEquals(23, state.output(4))
        state.release(firstHandle)
        assertEquals(List(6) { 0 }, (0..5).map(state::output))
        assertEquals(listOf(1, 16, 17), notifications)
        state.acquire(Any()) { true }
    }

    @Test
    fun `lost reachability clears outputs without a guest call and cannot revive an old lease`() {
        val state = RedstonePortState()
        val lease = Any()
        var connected = true
        state.acquire(lease) { connected }
        state.set(lease, 2, 15)
        connected = false
        state.expire()
        assertEquals(0, state.output(2))
        assertFalse(state.owns(lease))
        connected = true
        assertFailsWith<PeripheralFailure> { state.set(lease, 2, 15) }
        state.acquire(Any()) { true }
    }

    @Test
    fun `sample revisions advance only for changed input faces`() {
        val state = RedstonePortState()
        state.sample(intArrayOf(0, 7, 0, 0, 15, 0))
        assertEquals(7, state.input(1))
        assertEquals(1L, state.revision(1))
        assertEquals(0L, state.revision(0))
        state.sample(intArrayOf(0, 7, 0, 0, 15, 0))
        assertEquals(1L, state.revision(1))
        state.sample(IntArray(6))
        assertEquals(2L, state.revision(1))
        assertEquals(0, state.input(1))
        assertFailsWith<IllegalArgumentException> { state.sample(intArrayOf(16, 0, 0, 0, 0, 0)) }
    }

    @Test
    fun `reset clears direct and weak outputs and invalidates retained ownership`() {
        val state = RedstonePortState()
        val lease = Any()
        state.acquire(lease) { true }
        state.set(lease, 5, 31)
        state.reset()
        assertEquals(0, state.output(5))
        assertFalse(state.owns(lease))
        assertFailsWith<PeripheralFailure> { state.set(lease, 5, 15) }
    }
}

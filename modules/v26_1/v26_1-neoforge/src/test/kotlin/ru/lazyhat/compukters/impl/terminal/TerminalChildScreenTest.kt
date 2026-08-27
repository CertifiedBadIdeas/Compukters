/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package ru.lazyhat.compukters.impl.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalChildScreenTest {
    @Test
    fun `suspended terminal retains hidden state and resyncs before resuming`() {
        var connection: Any? = Any()
        var revision = 1L
        var retained = false
        val resyncs = mutableListOf<Long>()
        val lifecycle =
            TerminalChildLifecycle(
                { connection },
                { connection != null },
                { resyncs += revision },
                { retained = true },
                { retained = false },
            )

        lifecycle.suspend()
        revision = 2
        assertTrue(lifecycle.suspended)
        assertTrue(lifecycle.sameConnection())
        assertTrue(retained)

        assertTrue(lifecycle.resume())
        assertEquals(listOf(2L), resyncs)
        assertFalse(retained)
    }

    @Test
    fun `changed connection abandons suspended terminal without resync`() {
        var connection: Any? = Any()
        var released = false
        var resynced = false
        val lifecycle =
            TerminalChildLifecycle(
                { connection },
                { true },
                { resynced = true },
                {},
                { released = true },
            )
        lifecycle.suspend()
        connection = Any()

        assertFalse(lifecycle.resume())
        assertFalse(resynced)
        assertTrue(released)
    }
}

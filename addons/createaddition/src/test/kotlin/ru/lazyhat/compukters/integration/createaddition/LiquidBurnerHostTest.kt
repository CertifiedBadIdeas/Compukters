/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.integration.createaddition

import createaddition.BurnerStatus
import ru.lazyhat.compukters.api.addon.AddonCallResult
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LiquidBurnerHostTest {
    private class Burner(
        var reading: BurnerStatus,
    ) : BurnerAccess {
        override val identity = Any()
        var connected = true
        var reads = 0

        override fun valid() = connected

        override fun status(): BurnerStatus {
            reads++
            return reading
        }
    }

    @Test
    fun `one observation distinguishes a recoverable millibucket from burning fuel`() {
        val burner = Burner(BurnerStatus(1, 4000, 3, 200))
        val host = LiquidBurnerHost { burner }
        assertEquals(burner.reading, assertIs<AddonCallResult.Completed<BurnerStatus>>(host.status(1)).value)
        assertEquals(1, burner.reads)
        burner.reading = burner.reading.copy(fuelAmount = 0)
        assertEquals(200, assertIs<AddonCallResult.Completed<BurnerStatus>>(host.status(1)).value.burnTicks)
    }

    @Test
    fun `stale endpoint cannot return readings`() {
        val burner = Burner(BurnerStatus(100, 4000, 3, 200))
        burner.connected = false
        val result = LiquidBurnerHost { burner }.status(1)
        assertEquals(HostFailureKind.INPUT_OUTPUT, assertIs<AddonCallResult.Failed>(result).kind)
        assertEquals(0, burner.reads)
    }

    @Test
    fun `malformed producer readings are rejected`() {
        val valid = BurnerStatus(100, 4000, 3, 200)
        for (invalid in listOf(
            valid.copy(fuelAmount = -1),
            valid.copy(fuelAmount = 4001),
            valid.copy(fuelCapacity = 0),
            valid.copy(heat = 5),
            valid.copy(burnTicks = -1),
        )) {
            val result = LiquidBurnerHost { Burner(invalid) }.status(1)
            assertEquals(HostFailureKind.OTHER, assertIs<AddonCallResult.Failed>(result).kind)
        }
    }

    @Test
    fun `active resources do not pretend to support checkpoint restoration`() {
        val host = LiquidBurnerHost { Burner(BurnerStatus(0, 4000, 1, 0)) }
        assertEquals(0, host.checkpoint().size)
        host.status(1)
        assertFailsWith<IllegalStateException> { host.checkpoint() }
        host.reset()
        assertEquals(0, host.checkpoint().size)
        assertFailsWith<IllegalArgumentException> { host.restoreCheckpoint(byteArrayOf(1)) }
    }
}

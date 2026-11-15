/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.core.device.runtime.actor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProgramRuntimeFrameCreditTest {
    @Test
    fun `cumulative native reports charge only new instructions`() {
        val credit = ProgramRuntimeFrameCredit(1, 100, Long.MAX_VALUE, true)
        assertEquals(30L, credit.finishCumulative(30, 100))
        assertEquals(70L, credit.remaining)
        credit.inFlight = true
        assertEquals(15L, credit.finishCumulative(45, 70))
        assertEquals(55L, credit.remaining)
    }

    @Test
    fun `invalid reports cannot consume or increase a reservation`() {
        val credit = ProgramRuntimeFrameCredit(1, 10, Long.MAX_VALUE, true)
        assertFailsWith<IllegalStateException> { credit.finishCumulative(11, 10) }
        assertEquals(10L, credit.remaining)
        assertEquals(7L, credit.finishCumulative(7, 10))
        credit.inFlight = true
        assertFailsWith<IllegalStateException> { credit.finishCumulative(6, 3) }
        assertEquals(3L, credit.remaining)
        assertEquals(2L, credit.finishCumulative(9, 3))
    }
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.lang.runtime.vm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class JniAdvanceScratchPoolTest {
    @Test
    fun `leases are exclusive and returned even after failure`() {
        val pool = JniAdvanceScratchPool(64, maximumRetainedBuffers = 1)
        lateinit var first: JniAdvanceScratch
        assertFailsWith<IllegalStateException> {
            pool.withScratch { outer ->
                first = outer
                outer.written[0] = 8
                outer.retired[0] = 4
                pool.withScratch { inner -> assertNotSame(outer, inner) }
                throw IllegalStateException("failure")
            }
        }
        pool.withScratch { reused ->
            // The nested buffer filled the bounded idle pool; the failed lease was discarded.
            assertNotSame(first, reused)
            reused.written[0] = 3
            reused.retired[0] = 7
        }
        pool.withScratch { reused ->
            assertEquals(0L, reused.written[0])
            assertEquals(0L, reused.retired[0])
        }
        val subsequent = pool.withScratch { it }
        assertSame(subsequent, pool.withScratch { it })
    }
}

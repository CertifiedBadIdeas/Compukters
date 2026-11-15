/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */
package ru.lazyhat.compukters.lang.runtime.vm

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExperimentalFleetFailureTest {
    @Test
    fun `native fleet statuses distinguish contention from stale handles`() {
        requireFleetSuccess("grant", 0)
        assertFailsWith<VmFleetBusyException> { requireFleetSuccess("grant", 7) }
        assertFailsWith<VmFleetStaleException> { requireFleetSuccess("grant", 6) }
        assertFailsWith<VmBridgeException> { requireFleetSuccess("grant", 1) }
    }
}

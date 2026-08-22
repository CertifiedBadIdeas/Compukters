/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ru.lazyhat.compukters.compiler.worker.k2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrustedIntrinsicRegistryTest {
    @Test
    fun `guest declaration cannot spoof a terminal intrinsic by name and signature`() {
        val guestPrint =
            TrustedCallableIdentity(
                bundleIdentity = null,
                name = "print",
                suspending = true,
                parameters = listOf(TrustedValueType.STRING),
                result = TrustedValueType.UNIT,
            )

        assertNull(TrustedIntrinsicRegistry.resolve(guestPrint))
    }

    @Test
    fun `terminal provider requires its trusted bundle and exact signatures`() {
        val trustedReadln =
            TrustedCallableIdentity(
                bundleIdentity = TrustedIntrinsicRegistry.TERMINAL_BUNDLE_ID,
                name = "readln",
                suspending = true,
                parameters = emptyList(),
                result = TrustedValueType.STRING,
            )

        assertEquals(
            TrustedIntrinsic.CapabilityOperation(capability = 0u, operation = 2u),
            TrustedIntrinsicRegistry.resolve(trustedReadln),
        )
        assertNull(TrustedIntrinsicRegistry.resolve(trustedReadln.copy(suspending = false)))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedReadln.copy(parameters = listOf(TrustedValueType.STRING))))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedReadln.copy(result = TrustedValueType.UNIT)))
    }
}

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
        val guestWrite =
            TrustedCallableIdentity(
                bundleIdentity = null,
                name = "terminalWrite",
                suspending = false,
                parameters = listOf(TrustedValueType.STRING),
                result = TrustedValueType.UNIT,
            )

        assertNull(TrustedIntrinsicRegistry.resolve(guestWrite))
    }

    @Test
    fun `terminal provider requires its trusted bundle and exact signatures`() {
        val trustedAwait =
            TrustedCallableIdentity(
                bundleIdentity = TrustedIntrinsicRegistry.TERMINAL_BUNDLE_ID,
                name = "terminalAwaitEvent",
                suspending = true,
                parameters = emptyList(),
                result = TrustedValueType.INT,
            )

        assertEquals(
            TrustedIntrinsic.CapabilityOperation(capability = 0u, operation = 3u, asynchronous = true),
            TrustedIntrinsicRegistry.resolve(trustedAwait),
        )
        assertNull(TrustedIntrinsicRegistry.resolve(trustedAwait.copy(suspending = false)))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedAwait.copy(parameters = listOf(TrustedValueType.STRING))))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedAwait.copy(result = TrustedValueType.UNIT)))
    }

    @Test
    fun `terminal provider exposes raw synchronous operations`() {
        fun resolve(
            name: String,
            parameters: List<TrustedValueType>,
            result: TrustedValueType,
        ) = TrustedIntrinsicRegistry.resolve(
            TrustedCallableIdentity(
                TrustedIntrinsicRegistry.TERMINAL_BUNDLE_ID,
                name,
                false,
                parameters,
                result,
            ),
        )

        assertEquals(
            TrustedIntrinsic.CapabilityOperation(0u, 0u, asynchronous = false),
            resolve("terminalWrite", listOf(TrustedValueType.STRING), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(0u, 1u, asynchronous = false),
            resolve("terminalErasePrevious", emptyList(), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(0u, 2u, asynchronous = false),
            resolve("terminalClear", emptyList(), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(0u, 4u, asynchronous = false),
            resolve("terminalEventText", emptyList(), TrustedValueType.STRING),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(0u, 5u, asynchronous = false),
            resolve("terminalEventKey", emptyList(), TrustedValueType.INT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(0u, 6u, asynchronous = false),
            resolve("terminalEventAction", emptyList(), TrustedValueType.INT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(0u, 7u, asynchronous = false),
            resolve("terminalEventModifiers", emptyList(), TrustedValueType.INT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(0u, 8u, asynchronous = false),
            resolve("terminalFinishEvent", emptyList(), TrustedValueType.UNIT),
        )
    }
}

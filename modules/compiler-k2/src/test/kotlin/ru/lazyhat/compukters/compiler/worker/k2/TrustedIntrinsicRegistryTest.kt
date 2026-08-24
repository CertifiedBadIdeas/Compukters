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
    fun `compiler provider requires trusted bundle and exact async and sync signatures`() {
        fun resolve(
            name: String,
            suspending: Boolean,
            parameters: List<TrustedValueType>,
            result: TrustedValueType,
            bundle: String? = "compukter.compiler-api@1",
        ) = TrustedIntrinsicRegistry.resolve(
            TrustedCallableIdentity(bundle, name, suspending, parameters, result),
        )

        val compiler = TrustedCapabilityIdentity("compukter", "compiler", 1u.toUShort(), 0u.toUShort(), 2u)
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(compiler, 0u, asynchronous = true),
            resolve(
                "compukter.compiler.Compiler.compile",
                suspending = true,
                parameters = listOf(TrustedValueType.STRING, TrustedValueType.STRING),
                result = TrustedValueType.INT,
            ),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(compiler, 1u, asynchronous = false),
            resolve(
                "compukter.compiler.Compiler.diagnostics",
                suspending = false,
                parameters = emptyList(),
                result = TrustedValueType.STRING,
            ),
        )
        assertNull(
            resolve(
                "compukter.compiler.Compiler.compile",
                suspending = true,
                parameters = listOf(TrustedValueType.STRING, TrustedValueType.STRING),
                result = TrustedValueType.INT,
                bundle = null,
            ),
        )
        assertNull(
            resolve(
                "compukter.compiler.Other.compile",
                suspending = true,
                parameters = listOf(TrustedValueType.STRING, TrustedValueType.STRING),
                result = TrustedValueType.INT,
            ),
        )
        assertNull(
            resolve(
                "compukter.compiler.Compiler.compile",
                suspending = false,
                parameters = listOf(TrustedValueType.STRING, TrustedValueType.STRING),
                result = TrustedValueType.INT,
            ),
        )
        assertNull(
            resolve(
                "compukter.compiler.Compiler.diagnostics",
                suspending = true,
                parameters = emptyList(),
                result = TrustedValueType.STRING,
            ),
        )
        assertNull(
            resolve(
                "compukter.compiler.Compiler.compile",
                suspending = true,
                parameters = listOf(TrustedValueType.STRING),
                result = TrustedValueType.INT,
            ),
        )
    }

    @Test
    fun `filesystem provider requires trusted facade and exact synchronous signatures`() {
        fun resolve(
            name: String,
            parameters: List<TrustedValueType>,
            result: TrustedValueType,
            bundle: String? = "compukter.filesystem-api@1",
            suspending: Boolean = false,
        ) = TrustedIntrinsicRegistry.resolve(
            TrustedCallableIdentity(bundle, name, suspending, parameters, result),
        )

        val filesystem = TrustedCapabilityIdentity("compukter", "filesystem", 1u.toUShort(), 0u.toUShort(), 7u)
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(filesystem, 0u, asynchronous = false),
            resolve("compukter.filesystem.FileSystem.stat", listOf(TrustedValueType.STRING), TrustedValueType.INT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(filesystem, 1u, asynchronous = false),
            resolve("compukter.filesystem.FileSystem.list", listOf(TrustedValueType.STRING), TrustedValueType.STRING),
        )
        assertNull(
            resolve(
                "compukter.filesystem.FileSystem.stat",
                listOf(TrustedValueType.STRING),
                TrustedValueType.INT,
                bundle = null,
            ),
        )
        assertNull(
            resolve(
                "compukter.filesystem.Other.stat",
                listOf(TrustedValueType.STRING),
                TrustedValueType.INT,
            ),
        )
        assertNull(
            resolve(
                "compukter.filesystem.FileSystem.stat",
                listOf(TrustedValueType.STRING),
                TrustedValueType.INT,
                suspending = true,
            ),
        )
        assertNull(
            resolve("compukter.filesystem.FileSystem.list", emptyList(), TrustedValueType.STRING),
        )
    }

    @Test
    fun `process provider preserves ABI 1_0 and requires exact ABI 1_1 signatures`() {
        val trustedRun =
            TrustedCallableIdentity(
                bundleIdentity = TrustedIntrinsicRegistry.PROCESS_BUNDLE_ID,
                name = "compukter.process.Process.run",
                suspending = true,
                parameters = listOf(TrustedValueType.STRING, TrustedValueType.INT),
                result = TrustedValueType.INT,
            )

        val process = TrustedCapabilityIdentity("compukter", "process", 1u.toUShort(), 1u.toUShort(), 3u)
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(
                capability = process,
                operation = 0u,
                asynchronous = true,
            ),
            TrustedIntrinsicRegistry.resolve(trustedRun),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(process, 1u, asynchronous = true),
            TrustedIntrinsicRegistry.resolve(
                trustedRun.copy(parameters = listOf(TrustedValueType.STRING, TrustedValueType.INT, TrustedValueType.STRING)),
            ),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(process, 2u, asynchronous = false),
            TrustedIntrinsicRegistry.resolve(
                trustedRun.copy(
                    name = "compukter.process.Process.commandLine",
                    suspending = false,
                    parameters = emptyList(),
                    result = TrustedValueType.STRING,
                ),
            ),
        )
        assertNull(TrustedIntrinsicRegistry.resolve(trustedRun.copy(bundleIdentity = null)))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedRun.copy(suspending = false)))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedRun.copy(parameters = listOf(TrustedValueType.STRING))))
        assertNull(TrustedIntrinsicRegistry.resolve(trustedRun.copy(result = TrustedValueType.UNIT)))
        assertNull(
            TrustedIntrinsicRegistry.resolve(
                trustedRun.copy(
                    name = "compukter.process.Process.commandLine",
                    suspending = true,
                    parameters = emptyList(),
                    result = TrustedValueType.STRING,
                ),
            ),
        )
    }

    @Test
    fun `guest declaration cannot spoof a terminal intrinsic by name and signature`() {
        val guestWrite =
            TrustedCallableIdentity(
                bundleIdentity = null,
                name = "compukter.terminal.Terminal.write",
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
                name = "compukter.terminal.Terminal.awaitEvent",
                suspending = true,
                parameters = emptyList(),
                result = TrustedValueType.INT,
            )

        assertEquals(
            TrustedIntrinsic.CapabilityOperation(
                capability = TrustedIntrinsicRegistry.TERMINAL_CAPABILITY,
                operation = 3u,
                asynchronous = true,
            ),
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
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 0u, asynchronous = false),
            resolve("compukter.terminal.Terminal.write", listOf(TrustedValueType.STRING), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 1u, asynchronous = false),
            resolve("compukter.terminal.Terminal.erasePrevious", emptyList(), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 2u, asynchronous = false),
            resolve("compukter.terminal.Terminal.clear", emptyList(), TrustedValueType.UNIT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 4u, asynchronous = false),
            resolve("compukter.terminal.Terminal.eventText", emptyList(), TrustedValueType.STRING),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 5u, asynchronous = false),
            resolve("compukter.terminal.Terminal.eventKey", emptyList(), TrustedValueType.INT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 6u, asynchronous = false),
            resolve("compukter.terminal.Terminal.eventAction", emptyList(), TrustedValueType.INT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 7u, asynchronous = false),
            resolve("compukter.terminal.Terminal.eventModifiers", emptyList(), TrustedValueType.INT),
        )
        assertEquals(
            TrustedIntrinsic.CapabilityOperation(TrustedIntrinsicRegistry.TERMINAL_CAPABILITY, 8u, asynchronous = false),
            resolve("compukter.terminal.Terminal.finishEvent", emptyList(), TrustedValueType.UNIT),
        )
    }
}

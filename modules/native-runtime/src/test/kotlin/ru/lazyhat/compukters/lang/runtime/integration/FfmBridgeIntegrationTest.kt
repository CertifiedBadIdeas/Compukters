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

package ru.lazyhat.compukters.lang.runtime.integration

import kotlinx.coroutines.runBlocking
import ru.lazyhat.compukters.lang.runtime.vm.FfmBridge
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FfmBridgeIntegrationTest {
    @Test
    fun `JDK 25 FFM reads the native ABI version`() {
        FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
            assertEquals(1, bridge.abiVersion())
        }
    }

    @Test
    fun `terminal artifact runs through Kotlin FFM and Rust VM`() =
        runBlocking {
            FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
                TerminalFixtureProgram.run(Path.of(requiredProperty("compukter.vm.terminalFixture"))) { artifact ->
                    VmSession.open(artifact, bridge)
                }
            }
        }

    @Test
    fun `FFM preserves typed create failures`() {
        FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
            assertFailsWith<VmVerificationException> { VmSession.open(byteArrayOf(0), bridge) }
        }
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "missing $name test property" }
}

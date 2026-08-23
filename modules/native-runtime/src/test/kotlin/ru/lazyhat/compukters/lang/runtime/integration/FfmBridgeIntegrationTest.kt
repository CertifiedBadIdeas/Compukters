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
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.nio.file.Path
import kotlin.io.path.readText
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

    @Test
    fun `VmSession exposes the Rust owned terminal and compatibility input`() {
        val artifact = decodeHex(Path.of(requiredProperty("compukter.vm.terminalFixture")).readText())
        FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
            VmSession.open(artifact, bridge).use { session ->
                repeat(1_024) {
                    if (session.advance(64, 64) == VmOutcome.WaitingForLine) return@repeat
                }
                assertEquals(VmOutcome.WaitingForLine, session.advance(64, 64))

                session.commitTerminal()
                val state = session.terminalFullState()
                assertEquals(51, state.width)
                assertEquals(19, state.height)
                assertEquals(1, state.revision)
                assertEquals('>'.code, state.cells.first().codePoint)
                val delta = session.terminalChangesSince(0)
                require(delta is TerminalUpdate.Delta)
                assertEquals(1, delta.targetRevision)

                session.sendTerminalKey(
                    TerminalKey.ENTER,
                    TerminalKeyAction.PRESS,
                    setOf(TerminalModifier.SHIFT),
                )
                session.sendTerminalText("λ😀")
                session.provideCompatibilityLine("answer")
                repeat(1_024) {
                    if (session.advance(64, 64) is VmOutcome.Halted) return
                }
                error("fixture did not halt")
            }
        }
    }

    private fun decodeHex(encoded: String): ByteArray {
        val value = encoded.trim()
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun requiredProperty(name: String): String =
        requireNotNull(System.getProperty(name)) { "missing $name test property" }
}

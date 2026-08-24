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
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FfmBridgeIntegrationTest {
    @Test
    fun `JDK 25 FFM reads the native ABI version`() {
        FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
            assertEquals(4, bridge.abiVersion())
        }
    }

    @Test
    fun `terminal artifact runs through Kotlin FFM and Rust VM`() =
        runBlocking {
            FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
                ShellProgram.run(Path.of(requiredProperty("compukters.shell.artifact"))) { artifact ->
                    VmSession.open(artifact, bridge)
                }
            }
        }

    @Test
    fun `terminal FFM transport reuses one session scratch and isolates another session`() {
        FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
            val artifact = Path.of(requiredProperty("compukters.shell.artifact")).readBytes()
            VmSession.open(artifact, bridge).use { first ->
                val initial = first.terminalFullState()
                assertEquals(initial, first.terminalFullState())
                assertEquals(TerminalUpdate.Unchanged(initial.revision), first.terminalChangesSince(initial.revision))

                advanceUntilWaiting(first)
                first.commitTerminal()
                val delta = assertIs<TerminalUpdate.Delta>(first.terminalChangesSince(initial.revision))
                val current = first.terminalFullState()
                assertEquals(initial.revision, delta.baseRevision)
                assertEquals(current.revision, delta.targetRevision)
                assertEquals(current, first.terminalFullState())

                VmSession.open(artifact, bridge).use { second ->
                    val independent = second.terminalFullState()
                    assertEquals(0, independent.revision)
                    assertEquals(TerminalUpdate.Unchanged(0), second.terminalChangesSince(0))
                }
            }
        }
    }

    @Test
    fun `FFM preserves typed create failures`() {
        FfmBridge.open(Path.of(requiredProperty("compukter.ffi.library"))).use { bridge ->
            assertFailsWith<VmVerificationException> { VmSession.open(byteArrayOf(0), bridge) }
        }
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing $name test property" }

    private fun advanceUntilWaiting(session: VmSession) {
        repeat(10_000) {
            when (val outcome = session.advance(64, 64)) {
                VmOutcome.SliceExhausted -> Unit
                VmOutcome.WaitingForTerminalEvent -> return
                else -> error("unexpected VM outcome: $outcome")
            }
        }
        error("shell did not wait")
    }
}

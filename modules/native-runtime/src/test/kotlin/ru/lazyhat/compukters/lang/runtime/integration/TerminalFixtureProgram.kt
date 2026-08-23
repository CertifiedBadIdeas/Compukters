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

import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal object TerminalFixtureProgram {
    fun run(
        fixture: Path,
        openSession: (ByteArray) -> VmSession = { VmSession.open(it) },
    ) {
        val artifact = decodeHex(fixture.readText())

        openSession(artifact).use { session ->
            var lineProvided = false
            repeat(MAXIMUM_ADVANCES) {
                when (val outcome = session.advance(GUEST_BUDGET, MAINTENANCE_BUDGET)) {
                    VmOutcome.WaitingForLine -> {
                        check(!lineProvided) { "terminal artifact requested more than one line" }
                        session.commitTerminal()
                        assertEquals("> 😀> 😀\n", terminalText(session.terminalFullState()))
                        session.provideCompatibilityLine("answer")
                        lineProvided = true
                    }

                    is VmOutcome.Halted -> {
                        check(lineProvided) { "terminal artifact halted before reading its line" }
                        assertIs<VmValue.I32>(outcome.value)
                        assertEquals("> 😀> 😀\n", terminalText(session.terminalFullState()))
                        return
                    }

                    VmOutcome.SliceExhausted -> {
                        return@repeat
                    }

                    else -> {
                        error("unexpected VM outcome: $outcome")
                    }
                }
            }
        }
        error("terminal artifact did not halt within $MAXIMUM_ADVANCES advances")
    }

    private fun terminalText(state: TerminalState): String {
        val output = StringBuilder()
        repeat(state.height) { y ->
            repeat(state.width) { x ->
                output.appendCodePoint(state.cells[y * state.width + x].codePoint)
            }
            output.append('\n')
        }
        return output.toString().trimEnd(' ', '\n') + '\n'
    }

    private fun decodeHex(encoded: String): ByteArray {
        val value = encoded.trim()
        require(value.length % 2 == 0) { "fixture contains incomplete hexadecimal byte" }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private const val GUEST_BUDGET = 64
    private const val MAINTENANCE_BUDGET = 64
    private const val MAXIMUM_ADVANCES = 10_000
}

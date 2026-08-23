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

import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.assertEquals

internal object ShellProgram {
    fun run(
        artifactPath: Path,
        openSession: (ByteArray) -> VmSession = { VmSession.open(it) },
    ) {
        openSession(artifactPath.readBytes()).use { session ->
            advanceUntilWaiting(session)
            session.commitTerminal()
            assertEquals(">\n", terminalText(session.terminalFullState()))

            session.sendTerminalText("echo native")
            advanceUntilWaiting(session)
            session.sendTerminalKey(TerminalKey.ENTER, TerminalKeyAction.PRESS)
            advanceUntilWaiting(session)
            session.commitTerminal()
            assertEquals("> echo native\nnative\n>\n", terminalText(session.terminalFullState()))
        }
    }

    private fun advanceUntilWaiting(session: VmSession) {
        repeat(MAXIMUM_ADVANCES) {
            when (val outcome = session.advance(GUEST_BUDGET, MAINTENANCE_BUDGET)) {
                VmOutcome.SliceExhausted -> Unit
                VmOutcome.WaitingForTerminalEvent -> return
                else -> error("unexpected VM outcome: $outcome")
            }
        }
        error("shell did not wait within $MAXIMUM_ADVANCES advances")
    }

    private fun terminalText(state: TerminalState): String {
        val output = StringBuilder()
        repeat(state.height) { y ->
            val row = StringBuilder()
            repeat(state.width) { x -> row.appendCodePoint(state.cells[y * state.width + x].codePoint) }
            output.append(row.toString().trimEnd()).append('\n')
        }
        return output.toString().trimEnd('\n') + '\n'
    }

    private const val GUEST_BUDGET = 64
    private const val MAINTENANCE_BUDGET = 64
    private const val MAXIMUM_ADVANCES = 10_000
}

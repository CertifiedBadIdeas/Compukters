/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ru.lazyhat.compukters.core.device.runtime.program.integration

import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgramRuntimeHostIntegrationTest {
    @Test
    fun `compiled Kotlin shell edits Unicode and dispatches built-ins`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val artifact = Path.of(requiredProperty("compukters.programRuntime.artifact")).readBytes()
        val host = ProgramRuntimeHost(ProgramTickBudget(64, 64, 4))

        assertEquals(ProgramStartResult.Started, host.start(artifact))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
        assertEquals(">\n", terminalText(requireNotNull(host.terminalFullState())))

        submit(host, "help")
        pressEnter(host)
        assertEquals("> help\nhelp echo clear\n>\n", terminalText(requireNotNull(host.terminalFullState())))

        submit(host, "echo λ😀")
        press(host, TerminalKey.BACKSPACE)
        submit(host, "😀")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> echo λ😀\nλ😀\n>\n"))

        submit(host, "wat argument")
        pressEnter(host)
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("unknown command: wat\n>\n"))

        submit(host, "clear")
        pressEnter(host)
        submit(host, "a".repeat(255) + "😀")
        val bounded = requireNotNull(host.terminalFullState())
        assertEquals(255, bounded.cells.count { it.codePoint == 'a'.code })
        assertTrue(bounded.cells.none { it.codePoint == 0x1f600 })
        pressEnter(host)

        submit(host, "clear\u0000\r\n")
        assertTrue(terminalText(requireNotNull(host.terminalFullState())).endsWith("> clear\n"))
        pressEnter(host)
        assertEquals(">\n", terminalText(requireNotNull(host.terminalFullState())))
        assertEquals(ProgramRuntimeState.WaitingForInput, host.state)
        host.close()
    }

    private fun submit(
        host: ProgramRuntimeHost,
        text: String,
    ) {
        assertTrue(host.sendTerminalText(text))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
    }

    private fun pressEnter(host: ProgramRuntimeHost) = press(host, TerminalKey.ENTER)

    private fun press(
        host: ProgramRuntimeHost,
        key: TerminalKey,
    ) {
        assertTrue(host.sendTerminalKey(key, TerminalKeyAction.PRESS))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
    }

    private fun advanceUntil(
        host: ProgramRuntimeHost,
        predicate: (ProgramRuntimeState) -> Boolean,
    ): ProgramRuntimeState {
        repeat(MAXIMUM_TICKS) {
            val state = host.serverTick()
            if (predicate(state)) return state
            check(state == ProgramRuntimeState.Running) { "program terminated before expected state: $state" }
        }
        error("program did not reach expected state within $MAXIMUM_TICKS ticks; last state was ${host.state}")
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }

    private fun terminalText(state: TerminalState): String {
        val text = StringBuilder()
        repeat(state.height) { y ->
            val row = StringBuilder()
            repeat(state.width) { x -> row.appendCodePoint(state.cells[y * state.width + x].codePoint) }
            text.append(row.toString().trimEnd()).append('\n')
        }
        return text.toString().trimEnd('\n') + '\n'
    }

    private companion object {
        const val MAXIMUM_TICKS = 10_000
    }
}

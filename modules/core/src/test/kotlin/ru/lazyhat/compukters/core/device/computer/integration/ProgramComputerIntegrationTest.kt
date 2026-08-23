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

package ru.lazyhat.compukters.core.device.computer.integration

import ru.lazyhat.compukters.core.device.computer.ProgramComputer
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStateSink
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStopReason
import ru.lazyhat.compukters.core.device.computer.ProgramImageSource
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProgramComputerIntegrationTest {
    @Test
    fun `compiled Kotlin terminal artifact runs through server computer carrier`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val artifact = Path.of(requiredProperty("compukters.programRuntime.artifact")).readBytes()
        val events = mutableListOf<ObservedEvent>()
        val computer =
            ProgramComputer(
                deviceId = DEVICE_ID,
                imageSource = ProgramImageSource { deviceId -> artifact.also { assertEquals(DEVICE_ID, deviceId) } },
                stateSink =
                    ProgramComputerStateSink { deviceId, state ->
                        assertEquals(DEVICE_ID, deviceId)
                        events += ObservedEvent.State(state)
                    },
                tickBudget = ProgramTickBudget(64, 64, 4),
            )

        assertEquals(ProgramComputerState.Running, computer.turnOn())
        advanceUntil(computer) { it == ProgramComputerState.WaitingForInput }
        assertEquals(
            ObservedEvent.State(ProgramComputerState.WaitingForInput),
            events.last(),
        )
        assertEquals("Your name:\n", terminalText(requireNotNull(computer.terminalFullState())))

        assertTrue(computer.submitLine("Ada"))
        val finalState = advanceUntil(computer) { it is ProgramComputerState.PoweredOff }

        val poweredOff = assertIs<ProgramComputerState.PoweredOff>(finalState)
        assertIs<ProgramComputerStopReason.Halted>(poweredOff.reason)
        assertEquals(
            ObservedEvent.State(poweredOff),
            events.last(),
        )
        assertEquals("Your name: Hello, Ada!\n", terminalText(requireNotNull(computer.terminalFullState())))
        computer.close()
    }

    private fun advanceUntil(
        computer: ProgramComputer,
        predicate: (ProgramComputerState) -> Boolean,
    ): ProgramComputerState {
        repeat(MAXIMUM_TICKS) {
            val state = computer.serverTick()
            if (predicate(state)) return state
            check(state == ProgramComputerState.Running) { "computer terminated before expected state: $state" }
        }
        error("computer did not reach expected state within $MAXIMUM_TICKS ticks; last state was ${computer.state}")
    }

    private fun requiredProperty(name: String): String = requireNotNull(System.getProperty(name)) { "missing test system property $name" }

    private fun terminalText(state: TerminalState): String {
        val text = StringBuilder()
        repeat(state.height) { y ->
            repeat(state.width) { x -> text.appendCodePoint(state.cells[y * state.width + x].codePoint) }
            text.append('\n')
        }
        return text.toString().trimEnd(' ', '\n') + '\n'
    }

    private sealed interface ObservedEvent {
        data class State(
            val state: ProgramComputerState,
        ) : ObservedEvent
    }

    private companion object {
        const val DEVICE_ID = 41
        const val MAXIMUM_TICKS = 10_000
    }
}

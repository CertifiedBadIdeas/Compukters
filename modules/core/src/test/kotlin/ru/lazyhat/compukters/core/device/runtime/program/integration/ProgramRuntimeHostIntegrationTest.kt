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
import ru.lazyhat.compukters.lang.runtime.vm.VmRuntime
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProgramRuntimeHostIntegrationTest {
    @Test
    fun `compiled Kotlin terminal program runs across bounded server ticks`() {
        VmRuntime.loadNativeLibrary(Path.of(requiredProperty("compukters.ffi.library")))
        val artifact = Path.of(requiredProperty("compukters.programRuntime.artifact")).readBytes()
        val host = ProgramRuntimeHost(ProgramTickBudget(64, 64, 4))

        assertEquals(ProgramStartResult.Started, host.start(artifact))
        advanceUntil(host) { it == ProgramRuntimeState.WaitingForInput }
        assertEquals("Your name: ", host.drainOutput())

        assertTrue(host.submitLine("Ada"))
        val finalState = advanceUntil(host) { it is ProgramRuntimeState.Halted }

        assertIs<ProgramRuntimeState.Halted>(finalState)
        assertEquals("Hello, Ada!\n", host.drainOutput())
        host.close()
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

    private companion object {
        const val MAXIMUM_TICKS = 10_000
    }
}

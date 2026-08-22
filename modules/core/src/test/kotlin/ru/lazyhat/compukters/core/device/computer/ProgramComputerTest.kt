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

package ru.lazyhat.compukters.core.device.computer

import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ProgramComputerTest {
    @Test
    fun `construction is powered off without publishing state`() {
        val fixture = fixture(image = byteArrayOf(1))

        assertEquals(
            ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted),
            fixture.computer.state,
        )
        assertEquals(emptyList(), fixture.states)
        assertEquals(0, fixture.imageLoads)
        assertEquals(0, fixture.host.startCalls.size)
    }

    @Test
    fun `missing image publishes typed powered off failure`() {
        val fixture = fixture(image = null)

        assertEquals(
            ProgramComputerState.PoweredOff(
                ProgramComputerStopReason.Failure(ProgramComputerFailure.MissingImage),
            ),
            fixture.computer.turnOn(),
        )
        assertEquals(listOf(fixture.computer.state), fixture.states)
        assertEquals(1, fixture.imageLoads)
        assertEquals(0, fixture.host.startCalls.size)
    }

    @Test
    fun `valid image starts one host and publishes running once`() {
        val image = byteArrayOf(1, 2, 3)
        val fixture = fixture(image = image)

        assertEquals(ProgramComputerState.Running, fixture.computer.turnOn())
        assertEquals(ProgramComputerState.Running, fixture.computer.turnOn())

        assertEquals(listOf(ProgramComputerState.Running), fixture.states)
        assertEquals(1, fixture.imageLoads)
        assertEquals(1, fixture.host.startCalls.size)
        assertContentEquals(image, fixture.host.startCalls.single())
    }

    private fun fixture(image: ByteArray?): Fixture {
        val host = FakeProgramHost()
        val states = mutableListOf<ProgramComputerState>()
        var imageLoads = 0
        val computer =
            ProgramComputer(
                deviceId = 7,
                imageSource = ProgramImageSource {
                    imageLoads++
                    image
                },
                terminalSink = ProgramTerminalSink { _, _ -> },
                stateSink = ProgramComputerStateSink { _, state -> states += state },
                host = host,
            )
        return Fixture(computer, host, states) { imageLoads }
    }

    private class Fixture(
        val computer: ProgramComputer,
        val host: FakeProgramHost,
        val states: List<ProgramComputerState>,
        private val imageLoadsProvider: () -> Int,
    ) {
        val imageLoads: Int
            get() = imageLoadsProvider()
    }

    private class FakeProgramHost : ProgramHost {
        override var state: ProgramRuntimeState = ProgramRuntimeState.Idle
        val startCalls = mutableListOf<ByteArray>()

        override fun start(artifact: ByteArray): ProgramStartResult {
            startCalls += artifact.copyOf()
            state = ProgramRuntimeState.Running
            return ProgramStartResult.Started
        }

        override fun serverTick(): ProgramRuntimeState = state

        override fun submitLine(line: String): Boolean = false

        override fun drainOutput(): String = ""

        override fun shutdown() {
            state = ProgramRuntimeState.Idle
        }

        override fun close() {
            state = ProgramRuntimeState.Closed
        }
    }
}

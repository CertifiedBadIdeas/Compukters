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

import ru.lazyhat.compukters.core.device.runtime.program.ProgramFailure
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.lang.runtime.vm.GuestTrap
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `powered on tick advances before publishing wait state`() {
        val host =
            FakeProgramHost(
                tickStates = listOf(ProgramRuntimeState.WaitingForInput),
            )
        val fixture = fixture(image = byteArrayOf(1), host = host)
        fixture.computer.turnOn()
        fixture.events.clear()

        assertEquals(ProgramComputerState.WaitingForInput, fixture.computer.serverTick())

        assertEquals(1, host.tickCalls)
        assertEquals(
            listOf<ObservedEvent>(ObservedEvent.State(ProgramComputerState.WaitingForInput)),
            fixture.events,
        )
    }

    @Test
    fun `powered off tick performs no host work`() {
        val fixture = fixture(image = byteArrayOf(1))

        assertEquals(fixture.computer.state, fixture.computer.serverTick())
        assertEquals(0, fixture.host.tickCalls)
    }

    @Test
    fun `halt and runtime failure power off`() {
        val cases =
            listOf(
                ProgramRuntimeState.Halted(VmValue.I32(42)) to
                    ProgramComputerStopReason.Halted(VmValue.I32(42)),
                ProgramRuntimeState.Failed(ProgramFailure.Trap(GuestTrap.DIVISION_BY_ZERO)) to
                    ProgramComputerStopReason.Failure(
                        ProgramComputerFailure.Runtime(ProgramFailure.Trap(GuestTrap.DIVISION_BY_ZERO)),
                    ),
            )

        cases.forEach { (runtimeState, stopReason) ->
            val host = FakeProgramHost(tickStates = listOf(runtimeState))
            val fixture = fixture(image = byteArrayOf(1), host = host)
            fixture.computer.turnOn()
            fixture.events.clear()

            val expected = ProgramComputerState.PoweredOff(stopReason)
            assertEquals(expected, fixture.computer.serverTick())
            assertEquals(listOf<ObservedEvent>(ObservedEvent.State(expected)), fixture.events)
        }
    }

    @Test
    fun `terminal facade delegates state deltas and merged input without Kotlin echo`() {
        val terminal = terminalState(4)
        val host =
            FakeProgramHost(
                tickStates = listOf(ProgramRuntimeState.WaitingForInput),
                terminalState = terminal,
                terminalUpdate = TerminalUpdate.Unchanged(4),
                submitResult = true,
                submitState = ProgramRuntimeState.Running,
            )
        val fixture = fixture(image = byteArrayOf(1), host = host)
        fixture.computer.turnOn()
        fixture.events.clear()
        fixture.computer.serverTick()

        assertEquals(terminal, fixture.computer.terminalFullState())
        assertEquals(TerminalUpdate.Unchanged(4), fixture.computer.terminalChangesSince(4))
        assertTrue(
            fixture.computer.sendTerminalKey(
                TerminalKey.ENTER,
                TerminalKeyAction.PRESS,
                setOf(TerminalModifier.SHIFT),
            ),
        )
        assertTrue(fixture.computer.sendTerminalText("λ😀"))
        assertTrue(fixture.computer.submitLine("answer"))
        assertEquals(listOf("answer"), host.submittedLines)
        assertEquals(Triple(TerminalKey.ENTER, TerminalKeyAction.PRESS, setOf(TerminalModifier.SHIFT)), host.keys.single())
        assertEquals(listOf("λ😀"), host.texts)
        assertEquals(
            listOf<ObservedEvent>(
                ObservedEvent.State(ProgramComputerState.WaitingForInput),
                ObservedEvent.State(ProgramComputerState.Running),
            ),
            fixture.events,
        )
    }

    @Test
    fun `accepted line publishes running without recursively ticking`() {
        val host =
            FakeProgramHost(
                tickStates = listOf(ProgramRuntimeState.WaitingForInput),
                submitResult = true,
                submitState = ProgramRuntimeState.Running,
            )
        val fixture = fixture(image = byteArrayOf(1), host = host)
        fixture.computer.turnOn()
        fixture.computer.serverTick()
        fixture.events.clear()

        assertTrue(fixture.computer.submitLine("Ada"))

        assertEquals(listOf("Ada"), host.submittedLines)
        assertEquals(1, host.tickCalls)
        assertEquals(listOf<ObservedEvent>(ObservedEvent.State(ProgramComputerState.Running)), fixture.events)
    }

    @Test
    fun `input is gated and rejection preserves waiting state`() {
        val host =
            FakeProgramHost(
                tickStates = listOf(ProgramRuntimeState.WaitingForInput),
                submitResult = false,
                submitState = ProgramRuntimeState.WaitingForInput,
            )
        val fixture = fixture(image = byteArrayOf(1), host = host)

        assertFalse(fixture.computer.submitLine("before start"))
        fixture.computer.turnOn()
        assertFalse(fixture.computer.submitLine("before wait"))
        fixture.computer.serverTick()
        fixture.events.clear()

        assertFalse(fixture.computer.submitLine("too long"))
        assertEquals(ProgramComputerState.WaitingForInput, fixture.computer.state)
        assertEquals(listOf("too long"), host.submittedLines)
        assertEquals(emptyList(), fixture.events)
    }

    @Test
    fun `resume failure becomes powered off runtime failure`() {
        val failure = ProgramFailure.Trap(GuestTrap.NULL_REFERENCE)
        val host =
            FakeProgramHost(
                tickStates = listOf(ProgramRuntimeState.WaitingForInput),
                submitResult = false,
                submitState = ProgramRuntimeState.Failed(failure),
            )
        val fixture = fixture(image = byteArrayOf(1), host = host)
        fixture.computer.turnOn()
        fixture.computer.serverTick()

        assertFalse(fixture.computer.submitLine("Ada"))
        assertEquals(
            ProgramComputerState.PoweredOff(
                ProgramComputerStopReason.Failure(ProgramComputerFailure.Runtime(failure)),
            ),
            fixture.computer.state,
        )
    }

    @Test
    fun `rejected line with running host becomes contract failure`() {
        val host =
            FakeProgramHost(
                tickStates = listOf(ProgramRuntimeState.WaitingForInput),
                submitResult = false,
                submitState = ProgramRuntimeState.Running,
            )
        val fixture = fixture(image = byteArrayOf(1), host = host)
        fixture.computer.turnOn()
        fixture.computer.serverTick()

        assertFalse(fixture.computer.submitLine("Ada"))
        assertEquals(
            ProgramComputerState.PoweredOff(
                ProgramComputerStopReason.Failure(
                    ProgramComputerFailure.RuntimeContract(ProgramRuntimeState.Running),
                ),
            ),
            fixture.computer.state,
        )
    }

    @Test
    fun `unexpected inactive host state after tick becomes contract failure`() {
        listOf(ProgramRuntimeState.Idle, ProgramRuntimeState.Closed).forEach { runtimeState ->
            val host = FakeProgramHost(tickStates = listOf(runtimeState))
            val fixture = fixture(image = byteArrayOf(1), host = host)
            fixture.computer.turnOn()

            assertEquals(
                ProgramComputerState.PoweredOff(
                    ProgramComputerStopReason.Failure(
                        ProgramComputerFailure.RuntimeContract(runtimeState),
                    ),
                ),
                fixture.computer.serverTick(),
            )
        }
    }

    @Test
    fun `rejected host start publishes typed runtime failure`() {
        val failure = ProgramFailure.Verification
        val host = FakeProgramHost(startResult = ProgramStartResult.Rejected(failure))
        val fixture = fixture(image = byteArrayOf(1), host = host)

        assertEquals(
            ProgramComputerState.PoweredOff(
                ProgramComputerStopReason.Failure(ProgramComputerFailure.Runtime(failure)),
            ),
            fixture.computer.turnOn(),
        )
        assertEquals(1, host.startCalls.size)
    }

    @Test
    fun `shutdown and close release host exactly once and publish once`() {
        val shutdownFixture = fixture(image = byteArrayOf(1))
        shutdownFixture.computer.turnOn()
        shutdownFixture.events.clear()

        shutdownFixture.computer.shutdown()
        shutdownFixture.computer.shutdown()

        val shutdownState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.Shutdown)
        assertEquals(shutdownState, shutdownFixture.computer.state)
        assertEquals(1, shutdownFixture.host.shutdownCalls)
        assertEquals(listOf<ObservedEvent>(ObservedEvent.State(shutdownState)), shutdownFixture.events)

        val closeFixture = fixture(image = byteArrayOf(1))
        closeFixture.computer.turnOn()
        closeFixture.events.clear()

        closeFixture.computer.close()
        closeFixture.computer.close()

        assertEquals(ProgramComputerState.Closed, closeFixture.computer.state)
        assertEquals(1, closeFixture.host.closeCalls)
        assertEquals(
            listOf<ObservedEvent>(ObservedEvent.State(ProgramComputerState.Closed)),
            closeFixture.events,
        )
        assertEquals(ProgramComputerState.Closed, closeFixture.computer.turnOn())
        assertEquals(ProgramComputerState.Closed, closeFixture.computer.reboot())
        assertFalse(closeFixture.computer.submitLine("ignored"))
        assertEquals(1, closeFixture.imageLoads)
    }

    @Test
    fun `reboot reloads image without intermediate shutdown publication`() {
        val images = ArrayDeque(listOf(byteArrayOf(1), byteArrayOf(2)))
        val fixture = fixture(image = null, imageLoader = { images.removeFirst() })
        fixture.computer.turnOn()
        fixture.events.clear()

        assertEquals(ProgramComputerState.Running, fixture.computer.reboot())

        assertEquals(1, fixture.host.shutdownCalls)
        assertEquals(2, fixture.host.startCalls.size)
        assertContentEquals(byteArrayOf(1), fixture.host.startCalls[0])
        assertContentEquals(byteArrayOf(2), fixture.host.startCalls[1])
        assertEquals(emptyList(), fixture.events)
    }

    @Test
    fun `image exception becomes bounded single line failure`() {
        val detail = "x".repeat(300)
        val fixture =
            fixture(
                image = null,
                imageLoader = { throw IllegalStateException("$detail\nignored") },
            )

        assertEquals(
            ProgramComputerState.PoweredOff(
                ProgramComputerStopReason.Failure(
                    ProgramComputerFailure.ImageSource("x".repeat(256)),
                ),
            ),
            fixture.computer.turnOn(),
        )
    }

    @Test
    fun `state sink exception propagates after authoritative state changes`() {
        val fixture =
            fixture(
                image = byteArrayOf(1),
                statePublisher = { throw IllegalStateException("observer down") },
            )

        assertFailsWith<IllegalStateException> { fixture.computer.turnOn() }

        assertEquals(ProgramComputerState.Running, fixture.computer.state)
        assertEquals(ProgramComputerState.Running, fixture.computer.turnOn())
        assertEquals(1, fixture.host.startCalls.size)
        assertEquals(1, fixture.states.size)
    }

    private fun fixture(
        image: ByteArray?,
        host: FakeProgramHost = FakeProgramHost(),
        imageLoader: () -> ByteArray? = { image },
        statePublisher: (ProgramComputerState) -> Unit = {},
    ): Fixture {
        val states = mutableListOf<ProgramComputerState>()
        val events = mutableListOf<ObservedEvent>()
        var imageLoads = 0
        val computer =
            ProgramComputer(
                deviceId = 7,
                imageSource =
                    ProgramImageSource {
                        imageLoads++
                        imageLoader()
                    },
                stateSink =
                    ProgramComputerStateSink { _, state ->
                        states += state
                        events += ObservedEvent.State(state)
                        statePublisher(state)
                    },
                host = host,
            )
        return Fixture(computer, host, states, events) { imageLoads }
    }

    private class Fixture(
        val computer: ProgramComputer,
        val host: FakeProgramHost,
        val states: List<ProgramComputerState>,
        val events: MutableList<ObservedEvent>,
        private val imageLoadsProvider: () -> Int,
    ) {
        val imageLoads: Int
            get() = imageLoadsProvider()
    }

    private class FakeProgramHost(
        tickStates: List<ProgramRuntimeState> = emptyList(),
        val terminalState: TerminalState = terminalState(0),
        val terminalUpdate: TerminalUpdate = TerminalUpdate.Unchanged(0),
        private val submitResult: Boolean = false,
        private val submitState: ProgramRuntimeState = ProgramRuntimeState.WaitingForInput,
        private val startResult: ProgramStartResult = ProgramStartResult.Started,
    ) : ProgramHost {
        private val tickStates = ArrayDeque(tickStates)
        override var state: ProgramRuntimeState = ProgramRuntimeState.Idle
        val startCalls = mutableListOf<ByteArray>()
        val submittedLines = mutableListOf<String>()
        var tickCalls = 0
        var shutdownCalls = 0
        var closeCalls = 0
        val keys = mutableListOf<Triple<TerminalKey, TerminalKeyAction, Set<TerminalModifier>>>()
        val texts = mutableListOf<String>()

        override fun start(artifact: ByteArray): ProgramStartResult {
            startCalls += artifact.copyOf()
            state =
                when (val result = startResult) {
                    ProgramStartResult.Started -> ProgramRuntimeState.Running
                    is ProgramStartResult.Rejected -> ProgramRuntimeState.Failed(result.failure)
                    ProgramStartResult.Closed -> ProgramRuntimeState.Closed
                }
            return startResult
        }

        override fun serverTick(): ProgramRuntimeState {
            tickCalls++
            state = tickStates.removeFirstOrNull() ?: state
            return state
        }

        override fun submitLine(line: String): Boolean {
            submittedLines += line
            state = submitState
            return submitResult
        }

        override fun terminalFullState(): TerminalState = terminalState

        override fun terminalChangesSince(revision: Long): TerminalUpdate = terminalUpdate

        override fun sendTerminalKey(
            key: TerminalKey,
            action: TerminalKeyAction,
            modifiers: Set<TerminalModifier>,
        ): Boolean {
            keys += Triple(key, action, modifiers)
            return true
        }

        override fun sendTerminalText(value: String): Boolean {
            texts += value
            return true
        }

        override fun shutdown() {
            shutdownCalls++
            state = ProgramRuntimeState.Idle
        }

        override fun close() {
            closeCalls++
            state = ProgramRuntimeState.Closed
        }
    }

    private sealed interface ObservedEvent {
        data class State(
            val state: ProgramComputerState,
        ) : ObservedEvent
    }

    private companion object {
        fun terminalState(revision: Long): TerminalState =
            TerminalState(
                revision,
                51,
                19,
                List(51 * 19) { TerminalCell(' '.code, 15, 0) },
                TerminalPosition(0, 0),
                true,
            )
    }
}

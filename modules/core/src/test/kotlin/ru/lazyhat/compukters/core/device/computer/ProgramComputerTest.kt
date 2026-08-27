/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.core.device.computer

import ru.lazyhat.compukters.core.device.runtime.program.ProgramFailure
import ru.lazyhat.compukters.core.device.runtime.program.ProgramDeploymentCandidate
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
import ru.lazyhat.compukters.lang.runtime.vm.VmExecutableRevision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProgramComputerTest {
    @Test
    fun `deployment facade delegates opaque candidates and exact revisions`() {
        val candidate = fakeDeploymentCandidate()
        val host = FakeProgramHost(deploymentCandidate = candidate)
        val fixture = fixture(host)
        fixture.computer.turnOn()

        assertEquals(candidate, fixture.computer.verifyForDeploy(byteArrayOf(1, 2)))
        assertEquals(VmExecutableRevision.Absent, fixture.computer.executableRevision("/home/demo"))
        assertEquals(
            VmExecutableRevision.Present(1),
            fixture.computer.deploy("/home/demo", VmExecutableRevision.Absent, candidate),
        )
        assertTrue(fixture.computer.submitCanonicalLine("/home/demo".toCharArray()))

        assertEquals(listOf<Byte>(1, 2), host.verifiedArtifact)
        assertEquals(listOf("/home/demo"), host.revisionPaths)
        assertEquals(listOf("/home/demo"), host.deploymentPaths)
        assertEquals(listOf("/home/demo"), host.canonicalLines)
    }

    @Test
    fun `construction is powered off without publishing or booting`() {
        val fixture = fixture()

        assertEquals(ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted), fixture.computer.state)
        assertEquals(emptyList(), fixture.states)
        assertEquals(0, fixture.host.bootCalls)
    }

    @Test
    fun `turn on boots exactly once and publishes running`() {
        val fixture = fixture()

        assertEquals(ProgramComputerState.Running, fixture.computer.turnOn())
        assertEquals(ProgramComputerState.Running, fixture.computer.turnOn())
        assertEquals(1, fixture.host.bootCalls)
        assertEquals(listOf<ProgramComputerState>(ProgramComputerState.Running), fixture.states)
    }

    @Test
    fun `rejected boot publishes typed runtime failure`() {
        val failure = ProgramFailure.Admission(7)
        val fixture = fixture(FakeProgramHost(startResult = ProgramStartResult.Rejected(failure)))

        assertEquals(
            ProgramComputerState.PoweredOff(ProgramComputerStopReason.Failure(ProgramComputerFailure.Runtime(failure))),
            fixture.computer.turnOn(),
        )
        assertEquals(1, fixture.host.bootCalls)
    }

    @Test
    fun `powered on tick advances and publishes terminal wait`() {
        val fixture = fixture(FakeProgramHost(tickStates = listOf(ProgramRuntimeState.WaitingForInput)))
        fixture.computer.turnOn()
        fixture.states.clear()

        assertEquals(ProgramComputerState.WaitingForInput, fixture.computer.serverTick())
        assertEquals(1, fixture.host.tickCalls)
        assertEquals(listOf<ProgramComputerState>(ProgramComputerState.WaitingForInput), fixture.states)
    }

    @Test
    fun `powered off tick performs no host work`() {
        val fixture = fixture()

        assertEquals(fixture.computer.state, fixture.computer.serverTick())
        assertEquals(0, fixture.host.tickCalls)
    }

    @Test
    fun `halt and runtime failure power off`() {
        val cases =
            listOf(
                ProgramRuntimeState.Halted(VmValue.I32(42)) to ProgramComputerStopReason.Halted(VmValue.I32(42)),
                ProgramRuntimeState.Failed(ProgramFailure.Trap(GuestTrap.DIVISION_BY_ZERO)) to
                    ProgramComputerStopReason.Failure(
                        ProgramComputerFailure.Runtime(ProgramFailure.Trap(GuestTrap.DIVISION_BY_ZERO)),
                    ),
            )

        cases.forEach { (runtimeState, stopReason) ->
            val fixture = fixture(FakeProgramHost(tickStates = listOf(runtimeState)))
            fixture.computer.turnOn()
            assertEquals(ProgramComputerState.PoweredOff(stopReason), fixture.computer.serverTick())
        }
    }

    @Test
    fun `terminal facade delegates Rust state and merged input`() {
        val terminal = terminalState(4)
        val host =
            FakeProgramHost(
                tickStates = listOf(ProgramRuntimeState.WaitingForInput),
                terminalState = terminal,
                terminalUpdate = TerminalUpdate.Unchanged(4),
            )
        val fixture = fixture(host)
        fixture.computer.turnOn()
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
        assertEquals(1, host.keys.size)
        assertEquals(listOf("λ😀"), host.texts)
    }

    @Test
    fun `reboot drops the old session and boots a fresh one without intermediate publication`() {
        val fixture = fixture()
        fixture.computer.turnOn()
        fixture.states.clear()

        assertEquals(ProgramComputerState.Running, fixture.computer.reboot())
        assertEquals(1, fixture.host.shutdownCalls)
        assertEquals(2, fixture.host.bootCalls)
        assertEquals(emptyList(), fixture.states)
    }

    @Test
    fun `shutdown and close are idempotent`() {
        val fixture = fixture()
        fixture.computer.turnOn()

        fixture.computer.shutdown()
        fixture.computer.shutdown()
        assertEquals(1, fixture.host.shutdownCalls)

        fixture.computer.close()
        fixture.computer.close()
        assertEquals(1, fixture.host.closeCalls)
        assertEquals(ProgramComputerState.Closed, fixture.computer.turnOn())
        assertEquals(ProgramComputerState.Closed, fixture.computer.reboot())
        assertEquals(1, fixture.host.bootCalls)
    }

    @Test
    fun `state sink exception propagates after authoritative state changes`() {
        val fixture = fixture(statePublisher = { throw IllegalStateException("observer down") })

        assertFailsWith<IllegalStateException> { fixture.computer.turnOn() }
        assertEquals(ProgramComputerState.Running, fixture.computer.state)
        assertEquals(1, fixture.host.bootCalls)
    }

    private fun fixture(
        host: FakeProgramHost = FakeProgramHost(),
        statePublisher: (ProgramComputerState) -> Unit = {},
    ): Fixture {
        val states = mutableListOf<ProgramComputerState>()
        val computer =
            ProgramComputer(
                deviceId = 7,
                stateSink =
                    ProgramComputerStateSink { _, state ->
                        states += state
                        statePublisher(state)
                    },
                host = host,
            )
        return Fixture(computer, host, states)
    }

    private data class Fixture(
        val computer: ProgramComputer,
        val host: FakeProgramHost,
        val states: MutableList<ProgramComputerState>,
    )

    private class FakeProgramHost(
        tickStates: List<ProgramRuntimeState> = emptyList(),
        private val terminalState: TerminalState = terminalState(0),
        private val terminalUpdate: TerminalUpdate = TerminalUpdate.Unchanged(0),
        private val startResult: ProgramStartResult = ProgramStartResult.Started,
        private val deploymentCandidate: ProgramDeploymentCandidate = fakeDeploymentCandidate(),
    ) : ProgramHost {
        private val tickStates = ArrayDeque(tickStates)
        override var state: ProgramRuntimeState = ProgramRuntimeState.Idle
        var bootCalls = 0
        var tickCalls = 0
        var shutdownCalls = 0
        var closeCalls = 0
        val keys = mutableListOf<Triple<TerminalKey, TerminalKeyAction, Set<TerminalModifier>>>()
        val texts = mutableListOf<String>()
        var verifiedArtifact = emptyList<Byte>()
        val revisionPaths = mutableListOf<String>()
        val deploymentPaths = mutableListOf<String>()
        val canonicalLines = mutableListOf<String>()

        override fun startBoot(): ProgramStartResult {
            bootCalls++
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

        override fun filesystemGeneration(): Long? = null

        override fun verifyForDeploy(artifact: ByteArray): ProgramDeploymentCandidate? {
            verifiedArtifact = artifact.toList()
            return deploymentCandidate
        }

        override fun executableRevision(path: String): VmExecutableRevision? {
            revisionPaths += path
            return VmExecutableRevision.Absent
        }

        override fun deploy(
            path: String,
            expected: VmExecutableRevision,
            candidate: ProgramDeploymentCandidate,
        ): VmExecutableRevision? {
            deploymentPaths += path
            assertEquals(deploymentCandidate, candidate)
            return VmExecutableRevision.Present(1)
        }

        override fun submitCanonicalLine(line: CharArray): Boolean {
            canonicalLines += line.concatToString()
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

    private companion object {
        fun fakeDeploymentCandidate(): ProgramDeploymentCandidate =
            object : ProgramDeploymentCandidate {
                override fun close() = Unit
            }

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

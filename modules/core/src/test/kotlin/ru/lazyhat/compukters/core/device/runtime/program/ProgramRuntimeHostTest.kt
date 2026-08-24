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

package ru.lazyhat.compukters.core.device.runtime.program

import ru.lazyhat.compukters.lang.runtime.capability.HostResponse
import ru.lazyhat.compukters.lang.runtime.vm.CapabilityIdentity
import ru.lazyhat.compukters.lang.runtime.vm.GuestTrap
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.QuotaKind
import ru.lazyhat.compukters.lang.runtime.vm.TerminalCell
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalPosition
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmAdmissionException
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmFault
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmStartException
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramRuntimeHostTest {
    @Test
    fun `execution limits must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ProgramTickBudget(
                guestBudgetPerAdvance = 0,
                maintenanceBudgetPerAdvance = 1,
                maximumAdvancesPerTick = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProgramTickBudget(
                guestBudgetPerAdvance = 1,
                maintenanceBudgetPerAdvance = 0,
                maximumAdvancesPerTick = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProgramTickBudget(
                guestBudgetPerAdvance = 1,
                maintenanceBudgetPerAdvance = 1,
                maximumAdvancesPerTick = 0,
            )
        }
    }

    @Test
    fun `defaults expose a bounded idle runtime model`() {
        assertEquals(4_096, ProgramTickBudget().guestBudgetPerAdvance)
        assertEquals(256, ProgramTickBudget().maintenanceBudgetPerAdvance)
        assertEquals(32, ProgramTickBudget().maximumAdvancesPerTick)
        assertEquals(ProgramRuntimeState.Idle, ProgramRuntimeState.Idle)
    }

    @Test
    fun `valid artifact starts one session in running state`() {
        val session = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val host = host(session)

        assertEquals(ProgramRuntimeState.Idle, host.state)
        assertEquals(ProgramStartResult.Started, host.start(byteArrayOf(1, 2, 3)))
        assertEquals(ProgramRuntimeState.Running, host.state)
    }

    @Test
    fun `tick never advances more than configured maximum`() {
        val session = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val host = host(session, ProgramTickBudget(7, 3, 4))
        assertEquals(ProgramStartResult.Started, host.start(byteArrayOf(1)))

        assertEquals(ProgramRuntimeState.Running, host.serverTick())

        assertEquals(listOf(7 to 3, 7 to 3, 7 to 3, 7 to 3), session.advances)
    }

    @Test
    fun `Rust terminal is committed once per active tick and remains available after halt`() {
        val terminal = terminalState(revision = 7)
        val session =
            ScriptedSession(
                outcomes = listOf(VmOutcome.WaitingForTerminalEvent, VmOutcome.Halted(VmValue.I32(0))),
                terminalState = terminal,
                terminalUpdate = TerminalUpdate.Unchanged(7),
            )
        val host = host(session, ProgramTickBudget(8, 4, 1))
        host.start(byteArrayOf(1))

        assertEquals(ProgramRuntimeState.WaitingForInput, host.serverTick())
        assertEquals(1, session.terminalCommits)
        assertEquals(terminal, host.terminalFullState())
        assertEquals(TerminalUpdate.Unchanged(7), host.terminalChangesSince(7))
        host.sendTerminalKey(TerminalKey.ENTER, TerminalKeyAction.PRESS, setOf(TerminalModifier.SHIFT))
        host.sendTerminalText("λ😀")
        assertEquals(ProgramRuntimeState.Halted(VmValue.I32(0)), host.serverTick())
        assertEquals(2, session.terminalCommits)
        assertEquals(0, session.closeCalls)
        assertEquals(terminal, host.terminalFullState())
        host.shutdown()
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `typed creation failures never publish running`() {
        val cases =
            listOf(
                VmVerificationException() to ProgramFailure.Verification,
                VmAdmissionException(7) to ProgramFailure.Admission(7),
                VmStartException(8) to ProgramFailure.Start(8),
                VmBridgeException("bridge down") to ProgramFailure.Bridge("bridge down"),
            )

        cases.forEach { (error, expected) ->
            val host =
                ProgramRuntimeHost(
                    sessionFactory = ProgramVmSessionFactory { throw error },
                )

            assertEquals(ProgramStartResult.Rejected(expected), host.start(byteArrayOf(1)))
            assertEquals(ProgramRuntimeState.Failed(expected), host.state)
        }
    }

    @Test
    fun `starting a replacement closes the previous session exactly once`() {
        val first = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val second = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val sessions = ArrayDeque(listOf(first, second))
        val host = ProgramRuntimeHost(sessionFactory = ProgramVmSessionFactory { sessions.removeFirst() })

        assertEquals(ProgramStartResult.Started, host.start(byteArrayOf(1)))
        assertEquals(ProgramStartResult.Started, host.start(byteArrayOf(2)))

        assertEquals(1, first.closeCalls)
        assertEquals(0, second.closeCalls)
        assertEquals(ProgramRuntimeState.Running, host.state)
    }

    @Test
    fun `active session publishes its current filesystem generation`() {
        val session = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted, filesystemGeneration = 7)
        val host = host(session)

        host.start(byteArrayOf(1))
        assertEquals(7, host.filesystemGeneration())
        host.shutdown()
        assertNull(host.filesystemGeneration())
    }

    @Test
    fun `halt retains its session while VM failures release theirs`() {
        val cases =
            listOf(
                Triple(VmOutcome.Halted(VmValue.I32(42)), ProgramRuntimeState.Halted(VmValue.I32(42)), 0),
                Triple(
                    VmOutcome.AllocationExhausted(true),
                    ProgramRuntimeState.Failed(ProgramFailure.Allocation(collectionAttempted = true)),
                    1,
                ),
                Triple(
                    VmOutcome.QuotaExhausted(QuotaKind.HOST_REQUESTS, 10, 11),
                    ProgramRuntimeState.Failed(ProgramFailure.Quota(QuotaKind.HOST_REQUESTS, 10, 11)),
                    1,
                ),
                Triple(
                    VmOutcome.Crashed(GuestTrap.DIVISION_BY_ZERO),
                    ProgramRuntimeState.Failed(ProgramFailure.Trap(GuestTrap.DIVISION_BY_ZERO)),
                    1,
                ),
                Triple(
                    VmOutcome.Faulted(VmFault.CORRUPT_LIFECYCLE),
                    ProgramRuntimeState.Failed(ProgramFailure.Fault(VmFault.CORRUPT_LIFECYCLE)),
                    1,
                ),
                Triple(
                    VmOutcome.HostFailed(HostFailureKind.INPUT_OUTPUT, 9),
                    ProgramRuntimeState.Failed(ProgramFailure.Host(HostFailureKind.INPUT_OUTPUT, 9)),
                    1,
                ),
            )

        cases.forEach { (outcome, expected, closeCalls) ->
            val session = ScriptedSession(outcomes = listOf(outcome))
            val host = host(session)
            host.start(byteArrayOf(1))

            assertEquals(expected, host.serverTick())
            assertEquals(expected, host.serverTick())
            assertEquals(closeCalls, session.closeCalls)
            assertEquals(1, session.advances.size)
        }
    }

    @Test
    fun `shutdown is reusable while close is permanent and both are idempotent`() {
        val first = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val second = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val sessions = ArrayDeque(listOf(first, second))
        val host = ProgramRuntimeHost(sessionFactory = ProgramVmSessionFactory { sessions.removeFirst() })
        host.start(byteArrayOf(1))

        host.shutdown()
        host.shutdown()
        assertEquals(ProgramRuntimeState.Idle, host.state)
        assertEquals(1, first.closeCalls)
        assertEquals(ProgramStartResult.Started, host.start(byteArrayOf(2)))

        host.close()
        host.close()
        assertEquals(ProgramRuntimeState.Closed, host.state)
        assertEquals(1, second.closeCalls)
        assertEquals(ProgramStartResult.Closed, host.start(byteArrayOf(3)))
        assertEquals(ProgramRuntimeState.Closed, host.serverTick())
    }

    @Test
    fun `addon host requests remain host owned and receive stable unavailable failures`() {
        val unknown = CapabilityIdentity("addon", "terminal", 1, 0)
        val request = VmOutcome.HostRequest(VmHostRequest(1, unknown, 0, listOf(VmValue.StringValue("x"))))
        val session = ScriptedSession(outcomes = listOf(request), defaultOutcome = VmOutcome.SliceExhausted)
        val host = host(session, ProgramTickBudget(8, 4, 1))
        host.start(byteArrayOf(1))

        assertEquals(ProgramRuntimeState.Running, host.serverTick())
        assertEquals(listOf(response(1, HostResponse.Failure(HostFailureKind.UNAVAILABLE, 0))), session.responses)
    }

    @Test
    fun `raw terminal wait wakes after accepted input and executes only on the next tick`() {
        val session =
            ScriptedSession(
                outcomes =
                    listOf(
                        VmOutcome.WaitingForTerminalEvent,
                        VmOutcome.Halted(VmValue.StringValue("done")),
                    ),
            )
        val host = host(session)
        host.start(byteArrayOf(1))

        assertEquals(ProgramRuntimeState.WaitingForInput, host.serverTick())
        assertEquals(1, session.advances.size)
        assertEquals(1, session.terminalCommits)
        assertTrue(host.sendTerminalText("λ😀"))
        assertEquals(listOf("λ😀"), session.terminalTexts)
        assertEquals(ProgramRuntimeState.Running, host.state)
        assertEquals(1, session.advances.size)
        assertEquals(1, session.terminalCommits)

        assertEquals(ProgramRuntimeState.Halted(VmValue.StringValue("done")), host.serverTick())
        assertEquals(2, session.terminalCommits)
    }

    @Test
    fun `replacement and shutdown close the previous retained terminal`() {
        val first =
            ScriptedSession(
                outcomes = listOf(VmOutcome.WaitingForTerminalEvent),
            )
        val second = ScriptedSession(defaultOutcome = VmOutcome.SliceExhausted)
        val sessions = ArrayDeque(listOf(first, second))
        val host =
            ProgramRuntimeHost(
                sessionFactory = ProgramVmSessionFactory { sessions.removeFirst() },
                tickBudget = ProgramTickBudget(8, 4, 2),
            )
        host.start(byteArrayOf(1))
        assertEquals(ProgramRuntimeState.WaitingForInput, host.serverTick())

        host.start(byteArrayOf(2))

        assertEquals(1, first.closeCalls)
        host.shutdown()
        assertEquals(ProgramRuntimeState.Idle, host.state)
        assertEquals(1, second.closeCalls)
    }

    private fun host(
        session: ScriptedSession,
        budget: ProgramTickBudget = ProgramTickBudget(),
    ): ProgramRuntimeHost =
        ProgramRuntimeHost(
            tickBudget = budget,
            sessionFactory = ProgramVmSessionFactory { session },
        )

    private class ScriptedSession(
        outcomes: List<VmOutcome> = emptyList(),
        private val defaultOutcome: VmOutcome? = null,
        private val resumeError: VmBridgeException? = null,
        val terminalState: TerminalState = terminalState(0),
        val terminalUpdate: TerminalUpdate = TerminalUpdate.Unchanged(0),
        private val filesystemGeneration: Long = 0,
    ) : ProgramVmSession {
        private val outcomes = ArrayDeque(outcomes)
        val advances = mutableListOf<Pair<Int, Int>>()
        val responses = mutableListOf<Pair<Long, HostResponse>>()
        var closeCalls = 0
        var terminalCommits = 0
        val terminalKeys = mutableListOf<Triple<TerminalKey, TerminalKeyAction, Set<TerminalModifier>>>()
        val terminalTexts = mutableListOf<String>()

        override fun advance(
            guestBudget: Int,
            maintenanceBudget: Int,
        ): VmOutcome {
            advances += guestBudget to maintenanceBudget
            return outcomes.removeFirstOrNull() ?: requireNotNull(defaultOutcome) { "no scripted outcome" }
        }

        override fun resume(
            requestId: Long,
            response: HostResponse,
        ) {
            resumeError?.let { throw it }
            responses += requestId to response
        }

        override fun close() {
            closeCalls++
        }

        override fun commitTerminal() {
            terminalCommits++
        }

        override fun terminalFullState(): TerminalState = terminalState

        override fun terminalChangesSince(revision: Long): TerminalUpdate = terminalUpdate

        override fun sendTerminalKey(
            key: TerminalKey,
            action: TerminalKeyAction,
            modifiers: Set<TerminalModifier>,
        ) {
            terminalKeys += Triple(key, action, modifiers)
        }

        override fun sendTerminalText(value: String) {
            terminalTexts += value
        }

        override fun filesystemGeneration(): Long = filesystemGeneration
    }

    private fun response(
        requestId: Long,
        response: HostResponse,
    ): Pair<Long, HostResponse> = requestId to response

    private companion object {
        fun terminalState(revision: Long): TerminalState =
            TerminalState(
                revision = revision,
                width = 51,
                height = 19,
                cells = List(51 * 19) { TerminalCell(' '.code, 15, 0) },
                cursor = TerminalPosition(0, 0),
                cursorVisible = true,
            )
    }
}

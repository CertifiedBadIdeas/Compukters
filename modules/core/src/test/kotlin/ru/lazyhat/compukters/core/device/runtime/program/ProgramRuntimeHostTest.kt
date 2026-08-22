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
import ru.lazyhat.compukters.lang.runtime.vm.GuestTrap
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.QuotaKind
import ru.lazyhat.compukters.lang.runtime.vm.VmAdmissionException
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmFault
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmStartException
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `terminal limits must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ProgramTerminalLimits(
                maximumInputLineCodeUnits = 0,
                maximumPendingOutputCodeUnits = 1,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProgramTerminalLimits(
                maximumInputLineCodeUnits = 1,
                maximumPendingOutputCodeUnits = 0,
            )
        }
    }

    @Test
    fun `defaults expose a bounded idle runtime model`() {
        assertEquals(4_096, ProgramTickBudget().guestBudgetPerAdvance)
        assertEquals(256, ProgramTickBudget().maintenanceBudgetPerAdvance)
        assertEquals(32, ProgramTickBudget().maximumAdvancesPerTick)
        assertEquals(4_096, ProgramTerminalLimits().maximumInputLineCodeUnits)
        assertEquals(65_536, ProgramTerminalLimits().maximumPendingOutputCodeUnits)
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
    fun `halt and VM failure outcomes close their session and remain terminal`() {
        val cases =
            listOf(
                VmOutcome.Halted(VmValue.I32(42)) to ProgramRuntimeState.Halted(VmValue.I32(42)),
                VmOutcome.AllocationExhausted(true) to
                    ProgramRuntimeState.Failed(ProgramFailure.Allocation(collectionAttempted = true)),
                VmOutcome.QuotaExhausted(QuotaKind.HOST_REQUESTS, 10, 11) to
                    ProgramRuntimeState.Failed(ProgramFailure.Quota(QuotaKind.HOST_REQUESTS, 10, 11)),
                VmOutcome.Crashed(GuestTrap.DIVISION_BY_ZERO) to
                    ProgramRuntimeState.Failed(ProgramFailure.Trap(GuestTrap.DIVISION_BY_ZERO)),
                VmOutcome.Faulted(VmFault.CORRUPT_LIFECYCLE) to
                    ProgramRuntimeState.Failed(ProgramFailure.Fault(VmFault.CORRUPT_LIFECYCLE)),
                VmOutcome.HostFailed(HostFailureKind.INPUT_OUTPUT, 9) to
                    ProgramRuntimeState.Failed(ProgramFailure.Host(HostFailureKind.INPUT_OUTPUT, 9)),
            )

        cases.forEach { (outcome, expected) ->
            val session = ScriptedSession(outcomes = listOf(outcome))
            val host = host(session)
            host.start(byteArrayOf(1))

            assertEquals(expected, host.serverTick())
            assertEquals(expected, host.serverTick())
            assertEquals(1, session.closeCalls)
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
    ) : ProgramVmSession {
        private val outcomes = ArrayDeque(outcomes)
        val advances = mutableListOf<Pair<Int, Int>>()
        val responses = mutableListOf<Pair<Long, HostResponse>>()
        var closeCalls = 0

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
            responses += requestId to response
        }

        override fun close() {
            closeCalls++
        }
    }
}

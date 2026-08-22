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

import ru.lazyhat.compukters.lang.runtime.vm.VmAdmissionException
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmStartException
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException

class ProgramRuntimeHost internal constructor(
    private val tickBudget: ProgramTickBudget,
    private val terminalLimits: ProgramTerminalLimits,
    private val sessionFactory: ProgramVmSessionFactory,
) : AutoCloseable {
    constructor(
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        terminalLimits: ProgramTerminalLimits = ProgramTerminalLimits(),
    ) : this(tickBudget, terminalLimits, NativeProgramVmSessionFactory)

    internal constructor(
        sessionFactory: ProgramVmSessionFactory,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        terminalLimits: ProgramTerminalLimits = ProgramTerminalLimits(),
    ) : this(tickBudget, terminalLimits, sessionFactory)

    private var session: ProgramVmSession? = null

    var state: ProgramRuntimeState = ProgramRuntimeState.Idle
        private set

    fun start(artifact: ByteArray): ProgramStartResult {
        if (state == ProgramRuntimeState.Closed) return ProgramStartResult.Closed
        releaseSession()
        state = ProgramRuntimeState.Idle
        return try {
            session = sessionFactory.open(artifact)
            state = ProgramRuntimeState.Running
            ProgramStartResult.Started
        } catch (_: VmVerificationException) {
            rejectStart(ProgramFailure.Verification)
        } catch (error: VmAdmissionException) {
            rejectStart(ProgramFailure.Admission(error.code))
        } catch (error: VmStartException) {
            rejectStart(ProgramFailure.Start(error.code))
        } catch (error: VmBridgeException) {
            rejectStart(ProgramFailure.Bridge(error.bridgeDetail()))
        }
    }

    fun serverTick(): ProgramRuntimeState {
        if (state != ProgramRuntimeState.Running) return state
        repeat(tickBudget.maximumAdvancesPerTick) {
            val outcome =
                try {
                    requireNotNull(session).advance(
                        tickBudget.guestBudgetPerAdvance,
                        tickBudget.maintenanceBudgetPerAdvance,
                    )
                } catch (error: VmBridgeException) {
                    return finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
                }
            when (outcome) {
                VmOutcome.SliceExhausted -> Unit
                is VmOutcome.Halted -> return finish(ProgramRuntimeState.Halted(outcome.value))
                is VmOutcome.AllocationExhausted ->
                    return finish(
                        ProgramRuntimeState.Failed(ProgramFailure.Allocation(outcome.collectionAttempted)),
                    )

                is VmOutcome.QuotaExhausted ->
                    return finish(
                        ProgramRuntimeState.Failed(
                            ProgramFailure.Quota(outcome.kind, outcome.limit, outcome.consumed),
                        ),
                    )

                is VmOutcome.Crashed -> return finish(ProgramRuntimeState.Failed(ProgramFailure.Trap(outcome.trap)))
                is VmOutcome.Faulted -> return finish(ProgramRuntimeState.Failed(ProgramFailure.Fault(outcome.fault)))
                is VmOutcome.HostFailed ->
                    return finish(
                        ProgramRuntimeState.Failed(ProgramFailure.Host(outcome.kind, outcome.code)),
                    )

                is VmOutcome.HostRequest -> error("terminal host requests are not implemented")
            }
        }
        return state
    }

    fun shutdown() {
        if (state == ProgramRuntimeState.Closed) return
        releaseSession()
        state = ProgramRuntimeState.Idle
    }

    override fun close() {
        if (state == ProgramRuntimeState.Closed) return
        releaseSession()
        state = ProgramRuntimeState.Closed
    }

    private fun rejectStart(failure: ProgramFailure): ProgramStartResult.Rejected {
        state = ProgramRuntimeState.Failed(failure)
        return ProgramStartResult.Rejected(failure)
    }

    private fun finish(finalState: ProgramRuntimeState): ProgramRuntimeState {
        releaseSession()
        state = finalState
        return finalState
    }

    private fun releaseSession() {
        session?.close()
        session = null
    }

    private fun VmBridgeException.bridgeDetail(): String = message ?: "native VM bridge failure"
}

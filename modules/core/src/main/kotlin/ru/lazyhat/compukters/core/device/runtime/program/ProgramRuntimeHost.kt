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
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.VmAdmissionException
import ru.lazyhat.compukters.lang.runtime.vm.VmBridgeException
import ru.lazyhat.compukters.lang.runtime.vm.VmHostRequest
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmStartException
import ru.lazyhat.compukters.lang.runtime.vm.VmValue
import ru.lazyhat.compukters.lang.runtime.vm.VmVerificationException

class ProgramRuntimeHost internal constructor(
    private val sessionFactory: ProgramVmSessionFactory,
    private val tickBudget: ProgramTickBudget = ProgramTickBudget(),
    private val terminalLimits: ProgramTerminalLimits = ProgramTerminalLimits(),
) : AutoCloseable {
    constructor(
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        terminalLimits: ProgramTerminalLimits = ProgramTerminalLimits(),
    ) : this(NativeProgramVmSessionFactory, tickBudget, terminalLimits)

    private var session: ProgramVmSession? = null
    private var pendingInputRequestId: Long? = null
    private val output = StringBuilder()

    var state: ProgramRuntimeState = ProgramRuntimeState.Idle
        private set

    fun start(artifact: ByteArray): ProgramStartResult {
        if (state == ProgramRuntimeState.Closed) return ProgramStartResult.Closed
        releaseSession()
        pendingInputRequestId = null
        output.clear()
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
                VmOutcome.SliceExhausted -> {
                    return@repeat
                }

                is VmOutcome.Halted -> {
                    return finish(ProgramRuntimeState.Halted(outcome.value))
                }

                is VmOutcome.AllocationExhausted -> {
                    return finish(
                        ProgramRuntimeState.Failed(ProgramFailure.Allocation(outcome.collectionAttempted)),
                    )
                }

                is VmOutcome.QuotaExhausted -> {
                    return finish(
                        ProgramRuntimeState.Failed(
                            ProgramFailure.Quota(outcome.kind, outcome.limit, outcome.consumed),
                        ),
                    )
                }

                is VmOutcome.Crashed -> {
                    return finish(ProgramRuntimeState.Failed(ProgramFailure.Trap(outcome.trap)))
                }

                is VmOutcome.Faulted -> {
                    return finish(ProgramRuntimeState.Failed(ProgramFailure.Fault(outcome.fault)))
                }

                is VmOutcome.HostFailed -> {
                    return finish(
                        ProgramRuntimeState.Failed(ProgramFailure.Host(outcome.kind, outcome.code)),
                    )
                }

                is VmOutcome.HostRequest -> {
                    if (!handleHostRequest(outcome.request)) return state
                }
            }
        }
        return state
    }

    fun submitLine(line: String): Boolean {
        if (state != ProgramRuntimeState.WaitingForInput) return false
        if (line.length > terminalLimits.maximumInputLineCodeUnits) return false
        val requestId = pendingInputRequestId ?: return false
        return try {
            requireNotNull(session).resume(requestId, HostResponse.StringSuccess(line))
            pendingInputRequestId = null
            state = ProgramRuntimeState.Running
            true
        } catch (error: VmBridgeException) {
            finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
            false
        }
    }

    fun drainOutput(): String = output.toString().also { output.clear() }

    fun shutdown() {
        if (state == ProgramRuntimeState.Closed) return
        releaseSession()
        pendingInputRequestId = null
        state = ProgramRuntimeState.Idle
    }

    override fun close() {
        if (state == ProgramRuntimeState.Closed) return
        releaseSession()
        pendingInputRequestId = null
        state = ProgramRuntimeState.Closed
    }

    private fun handleHostRequest(request: VmHostRequest): Boolean {
        if (request.capability != TERMINAL_CAPABILITY) {
            return resume(request, HostResponse.Failure(HostFailureKind.UNAVAILABLE, 0))
        }
        return when (request.operation) {
            PRINT_OPERATION -> write(request, newline = false)
            PRINTLN_OPERATION -> write(request, newline = true)
            READLN_OPERATION -> read(request)
            else -> resume(request, invalidRequest())
        }
    }

    private fun write(
        request: VmHostRequest,
        newline: Boolean,
    ): Boolean {
        val value =
            (request.arguments.singleOrNull() as? VmValue.StringValue)?.value
                ?: return resume(request, invalidRequest())
        val newlineLength = if (newline) 1 else 0
        val remaining = terminalLimits.maximumPendingOutputCodeUnits - output.length
        if (newlineLength > remaining || value.length > remaining - newlineLength) {
            return resume(request, HostResponse.Failure(HostFailureKind.OTHER, OUTPUT_LIMIT_CODE))
        }
        output.append(value)
        if (newline) output.append('\n')
        return resume(request, HostResponse.UnitSuccess)
    }

    private fun read(request: VmHostRequest): Boolean {
        if (request.arguments.isNotEmpty()) return resume(request, invalidRequest())
        pendingInputRequestId = request.id
        state = ProgramRuntimeState.WaitingForInput
        return false
    }

    private fun resume(
        request: VmHostRequest,
        response: HostResponse,
    ): Boolean =
        try {
            requireNotNull(session).resume(request.id, response)
            true
        } catch (error: VmBridgeException) {
            finish(ProgramRuntimeState.Failed(ProgramFailure.Bridge(error.bridgeDetail())))
            false
        }

    private fun invalidRequest(): HostResponse.Failure = HostResponse.Failure(HostFailureKind.OTHER, INVALID_REQUEST_CODE)

    private fun rejectStart(failure: ProgramFailure): ProgramStartResult.Rejected {
        state = ProgramRuntimeState.Failed(failure)
        return ProgramStartResult.Rejected(failure)
    }

    private fun finish(finalState: ProgramRuntimeState): ProgramRuntimeState {
        releaseSession()
        pendingInputRequestId = null
        state = finalState
        return finalState
    }

    private fun releaseSession() {
        session?.close()
        session = null
    }

    private fun VmBridgeException.bridgeDetail(): String = message ?: "native VM bridge failure"

    private companion object {
        val TERMINAL_CAPABILITY = CapabilityIdentity("compukter", "terminal", 1, 0)
        const val PRINT_OPERATION = 0
        const val PRINTLN_OPERATION = 1
        const val READLN_OPERATION = 2
        const val INVALID_REQUEST_CODE = 1L
        const val OUTPUT_LIMIT_CODE = 3L
    }
}

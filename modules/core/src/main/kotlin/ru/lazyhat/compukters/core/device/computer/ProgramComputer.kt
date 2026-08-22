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

import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTerminalLimits
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget

class ProgramComputer internal constructor(
    private val deviceId: Int,
    private val imageSource: ProgramImageSource,
    private val terminalSink: ProgramTerminalSink,
    private val stateSink: ProgramComputerStateSink,
    private val host: ProgramHost,
) : AutoCloseable {
    constructor(
        deviceId: Int,
        imageSource: ProgramImageSource,
        terminalSink: ProgramTerminalSink,
        stateSink: ProgramComputerStateSink,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        terminalLimits: ProgramTerminalLimits = ProgramTerminalLimits(),
    ) : this(
        deviceId,
        imageSource,
        terminalSink,
        stateSink,
        RuntimeProgramHost(ProgramRuntimeHost(tickBudget, terminalLimits)),
    )

    var state: ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
        private set

    fun turnOn(): ProgramComputerState {
        if (state == ProgramComputerState.Closed || state.isPoweredOn()) return state
        val artifact =
            try {
                imageSource.loadInstalledArtifact(deviceId)
            } catch (error: Exception) {
                return transitionTo(failure(ProgramComputerFailure.ImageSource(error.message ?: "image source failure")))
            } ?: return transitionTo(failure(ProgramComputerFailure.MissingImage))

        return when (val result = host.start(artifact)) {
            ProgramStartResult.Started -> transitionTo(ProgramComputerState.Running)
            is ProgramStartResult.Rejected -> transitionTo(failure(ProgramComputerFailure.Runtime(result.failure)))
            ProgramStartResult.Closed -> transitionTo(failure(ProgramComputerFailure.RuntimeContract(host.state)))
        }
    }

    fun serverTick(): ProgramComputerState = state

    fun submitLine(line: String): Boolean = false

    fun shutdown() = Unit

    fun reboot(): ProgramComputerState = state

    override fun close() = Unit

    private fun transitionTo(next: ProgramComputerState): ProgramComputerState {
        if (next == state) return state
        state = next
        stateSink.publishState(deviceId, next)
        return state
    }

    private fun failure(failure: ProgramComputerFailure): ProgramComputerState.PoweredOff =
        ProgramComputerState.PoweredOff(ProgramComputerStopReason.Failure(failure))

    private fun ProgramComputerState.isPoweredOn(): Boolean =
        this == ProgramComputerState.Running || this == ProgramComputerState.WaitingForInput
}

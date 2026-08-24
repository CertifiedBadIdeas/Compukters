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

import ru.lazyhat.compukters.core.device.runtime.compiler.CompilerCompletionRouter
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeHost
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.core.device.runtime.program.ProgramTickBudget
import ru.lazyhat.compukters.lang.runtime.fs.ComputerId
import ru.lazyhat.compukters.lang.runtime.fs.WorldFileSystemStore
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate

class ProgramComputer internal constructor(
    private val deviceId: Int,
    private val stateSink: ProgramComputerStateSink,
    private val host: ProgramHost,
) : AutoCloseable {
    constructor(
        deviceId: Int,
        stateSink: ProgramComputerStateSink,
        store: WorldFileSystemStore,
        computerId: ComputerId,
        romImage: ByteArray,
        tickBudget: ProgramTickBudget = ProgramTickBudget(),
        compilerRouter: CompilerCompletionRouter? = null,
    ) : this(
        deviceId,
        stateSink,
        RuntimeProgramHost(ProgramRuntimeHost(store, computerId, romImage, tickBudget, compilerRouter)),
    )

    var state: ProgramComputerState = ProgramComputerState.PoweredOff(ProgramComputerStopReason.NeverStarted)
        private set

    fun turnOn(): ProgramComputerState {
        if (state == ProgramComputerState.Closed || state.isPoweredOn()) return state
        return startBoot()
    }

    private fun startBoot(): ProgramComputerState =
        when (val result = host.startBoot()) {
            ProgramStartResult.Started -> transitionTo(ProgramComputerState.Running)
            is ProgramStartResult.Rejected -> transitionTo(failure(ProgramComputerFailure.Runtime(result.failure)))
            ProgramStartResult.Closed -> transitionTo(failure(ProgramComputerFailure.RuntimeContract(host.state)))
        }

    fun serverTick(): ProgramComputerState {
        if (!state.isPoweredOn()) return state
        val runtimeState = host.serverTick()
        return transitionFrom(runtimeState)
    }

    fun terminalFullState(): TerminalState? = host.terminalFullState()

    fun terminalChangesSince(revision: Long): TerminalUpdate? = host.terminalChangesSince(revision)

    fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier> = emptySet(),
    ): Boolean = state.isPoweredOn() && host.sendTerminalKey(key, action, modifiers)

    fun sendTerminalText(value: String): Boolean = state.isPoweredOn() && host.sendTerminalText(value)

    fun filesystemGeneration(): Long? = host.filesystemGeneration()

    fun shutdown() {
        if (state == ProgramComputerState.Closed || state == SHUTDOWN_STATE) return
        host.shutdown()
        transitionTo(SHUTDOWN_STATE)
    }

    fun reboot(): ProgramComputerState {
        if (state == ProgramComputerState.Closed) return state
        host.shutdown()
        return startBoot()
    }

    override fun close() {
        if (state == ProgramComputerState.Closed) return
        host.close()
        transitionTo(ProgramComputerState.Closed)
    }

    private fun transitionTo(next: ProgramComputerState): ProgramComputerState {
        if (next == state) return state
        state = next
        stateSink.publishState(deviceId, next)
        return state
    }

    private fun failure(failure: ProgramComputerFailure): ProgramComputerState.PoweredOff =
        ProgramComputerState.PoweredOff(ProgramComputerStopReason.Failure(failure))

    private fun transitionFrom(runtimeState: ProgramRuntimeState): ProgramComputerState =
        when (runtimeState) {
            ProgramRuntimeState.Running -> {
                transitionTo(ProgramComputerState.Running)
            }

            ProgramRuntimeState.WaitingForInput -> {
                transitionTo(ProgramComputerState.WaitingForInput)
            }

            ProgramRuntimeState.WaitingForCompiler -> {
                transitionTo(ProgramComputerState.WaitingForCompiler)
            }

            is ProgramRuntimeState.Halted -> {
                transitionTo(ProgramComputerState.PoweredOff(ProgramComputerStopReason.Halted(runtimeState.value)))
            }

            is ProgramRuntimeState.Failed -> {
                transitionTo(failure(ProgramComputerFailure.Runtime(runtimeState.failure)))
            }

            ProgramRuntimeState.Idle,
            ProgramRuntimeState.Closed,
            -> {
                transitionTo(failure(ProgramComputerFailure.RuntimeContract(runtimeState)))
            }
        }

    private fun ProgramComputerState.isPoweredOn(): Boolean =
        this == ProgramComputerState.Running ||
            this == ProgramComputerState.WaitingForInput ||
            this == ProgramComputerState.WaitingForCompiler

    private companion object {
        val SHUTDOWN_STATE = ProgramComputerState.PoweredOff(ProgramComputerStopReason.Shutdown)
    }
}

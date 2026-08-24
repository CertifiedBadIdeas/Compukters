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
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

sealed interface ProgramComputerState {
    data class PoweredOff(
        val reason: ProgramComputerStopReason,
    ) : ProgramComputerState

    data object Running : ProgramComputerState

    data object WaitingForInput : ProgramComputerState

    data object WaitingForCompiler : ProgramComputerState

    data object Closed : ProgramComputerState
}

sealed interface ProgramComputerStopReason {
    data object NeverStarted : ProgramComputerStopReason

    data object Shutdown : ProgramComputerStopReason

    data class Halted(
        val value: VmValue?,
    ) : ProgramComputerStopReason

    data class Failure(
        val failure: ProgramComputerFailure,
    ) : ProgramComputerStopReason
}

sealed interface ProgramComputerFailure {
    data class Runtime(
        val failure: ProgramFailure,
    ) : ProgramComputerFailure

    data class TerminalPublication(
        val detail: String,
    ) : ProgramComputerFailure

    data class RuntimeContract(
        val state: ProgramRuntimeState,
    ) : ProgramComputerFailure
}

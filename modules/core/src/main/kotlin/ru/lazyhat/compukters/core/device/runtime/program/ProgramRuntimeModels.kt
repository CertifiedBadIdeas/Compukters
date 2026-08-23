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

import ru.lazyhat.compukters.lang.runtime.vm.GuestTrap
import ru.lazyhat.compukters.lang.runtime.vm.HostFailureKind
import ru.lazyhat.compukters.lang.runtime.vm.QuotaKind
import ru.lazyhat.compukters.lang.runtime.vm.VmFault
import ru.lazyhat.compukters.lang.runtime.vm.VmValue

data class ProgramTickBudget(
    val guestBudgetPerAdvance: Int = 4_096,
    val maintenanceBudgetPerAdvance: Int = 256,
    val maximumAdvancesPerTick: Int = 32,
) {
    init {
        require(guestBudgetPerAdvance > 0) { "guest budget per advance must be positive" }
        require(maintenanceBudgetPerAdvance > 0) { "maintenance budget per advance must be positive" }
        require(maximumAdvancesPerTick > 0) { "maximum advances per tick must be positive" }
    }
}

data class ProgramTerminalLimits(
    val maximumInputLineCodeUnits: Int = 4_096,
) {
    init {
        require(maximumInputLineCodeUnits > 0) { "maximum input line code units must be positive" }
    }
}

sealed interface ProgramRuntimeState {
    data object Idle : ProgramRuntimeState

    data object Running : ProgramRuntimeState

    data object WaitingForInput : ProgramRuntimeState

    data class Halted(
        val value: VmValue?,
    ) : ProgramRuntimeState

    data class Failed(
        val failure: ProgramFailure,
    ) : ProgramRuntimeState

    data object Closed : ProgramRuntimeState
}

sealed interface ProgramStartResult {
    data object Started : ProgramStartResult

    data class Rejected(
        val failure: ProgramFailure,
    ) : ProgramStartResult

    data object Closed : ProgramStartResult
}

sealed interface ProgramFailure {
    data object Verification : ProgramFailure

    data class Admission(
        val code: Int,
    ) : ProgramFailure

    data class Start(
        val code: Int,
    ) : ProgramFailure

    data class Bridge(
        val detail: String,
    ) : ProgramFailure

    data class Allocation(
        val collectionAttempted: Boolean,
    ) : ProgramFailure

    data class Quota(
        val kind: QuotaKind,
        val limit: Long,
        val consumed: Long,
    ) : ProgramFailure

    data class Trap(
        val trap: GuestTrap,
    ) : ProgramFailure

    data class Fault(
        val fault: VmFault,
    ) : ProgramFailure

    data class Host(
        val kind: HostFailureKind,
        val code: Long,
    ) : ProgramFailure
}

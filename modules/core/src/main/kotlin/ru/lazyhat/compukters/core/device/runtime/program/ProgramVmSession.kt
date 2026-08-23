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
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate
import ru.lazyhat.compukters.lang.runtime.vm.VmOutcome
import ru.lazyhat.compukters.lang.runtime.vm.VmSession

internal interface ProgramVmSession : AutoCloseable {
    fun advance(
        guestBudget: Int,
        maintenanceBudget: Int,
    ): VmOutcome

    fun resume(
        requestId: Long,
        response: HostResponse,
    )

    fun commitTerminal()

    fun terminalFullState(): TerminalState

    fun terminalChangesSince(revision: Long): TerminalUpdate

    fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    )

    fun sendTerminalText(value: String)

    fun provideCompatibilityLine(value: String)
}

internal fun interface ProgramVmSessionFactory {
    fun open(artifact: ByteArray): ProgramVmSession
}

internal object NativeProgramVmSessionFactory : ProgramVmSessionFactory {
    override fun open(artifact: ByteArray): ProgramVmSession = NativeProgramVmSession(VmSession.open(artifact))
}

private class NativeProgramVmSession(
    private val session: VmSession,
) : ProgramVmSession {
    override fun advance(
        guestBudget: Int,
        maintenanceBudget: Int,
    ): VmOutcome = session.advance(guestBudget, maintenanceBudget)

    override fun resume(
        requestId: Long,
        response: HostResponse,
    ) = session.resume(requestId, response)

    override fun commitTerminal() = session.commitTerminal()

    override fun terminalFullState(): TerminalState = session.terminalFullState()

    override fun terminalChangesSince(revision: Long): TerminalUpdate = session.terminalChangesSince(revision)

    override fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ) = session.sendTerminalKey(key, action, modifiers)

    override fun sendTerminalText(value: String) = session.sendTerminalText(value)

    override fun provideCompatibilityLine(value: String) = session.provideCompatibilityLine(value)

    override fun close() = session.close()
}

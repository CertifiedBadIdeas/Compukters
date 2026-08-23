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
import ru.lazyhat.compukters.core.device.runtime.program.ProgramRuntimeState
import ru.lazyhat.compukters.core.device.runtime.program.ProgramStartResult
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate

internal interface ProgramHost : AutoCloseable {
    val state: ProgramRuntimeState

    fun start(artifact: ByteArray): ProgramStartResult

    fun serverTick(): ProgramRuntimeState

    fun terminalFullState(): TerminalState?

    fun terminalChangesSince(revision: Long): TerminalUpdate?

    fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ): Boolean

    fun sendTerminalText(value: String): Boolean

    fun shutdown()
}

internal class RuntimeProgramHost(
    private val delegate: ProgramRuntimeHost,
) : ProgramHost {
    override val state: ProgramRuntimeState
        get() = delegate.state

    override fun start(artifact: ByteArray): ProgramStartResult = delegate.start(artifact)

    override fun serverTick(): ProgramRuntimeState = delegate.serverTick()

    override fun terminalFullState(): TerminalState? = delegate.terminalFullState()

    override fun terminalChangesSince(revision: Long): TerminalUpdate? = delegate.terminalChangesSince(revision)

    override fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ): Boolean = delegate.sendTerminalKey(key, action, modifiers)

    override fun sendTerminalText(value: String): Boolean = delegate.sendTerminalText(value)

    override fun shutdown() = delegate.shutdown()

    override fun close() = delegate.close()
}

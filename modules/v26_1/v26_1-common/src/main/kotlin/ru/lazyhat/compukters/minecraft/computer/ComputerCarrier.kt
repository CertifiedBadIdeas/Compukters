/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.minecraft.computer

import ru.lazyhat.compukters.core.device.computer.ProgramComputer
import ru.lazyhat.compukters.core.device.computer.ProgramComputerState
import ru.lazyhat.compukters.core.device.computer.ProgramComputerStateSink
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKey
import ru.lazyhat.compukters.lang.runtime.vm.TerminalKeyAction
import ru.lazyhat.compukters.lang.runtime.vm.TerminalModifier
import ru.lazyhat.compukters.lang.runtime.vm.TerminalState
import ru.lazyhat.compukters.lang.runtime.vm.TerminalUpdate

internal interface ComputerCarrier : AutoCloseable {
    val state: ProgramComputerState

    fun turnOn(): ProgramComputerState

    fun serverTick(): ProgramComputerState

    fun terminalFullState(): TerminalState?

    fun terminalChangesSince(revision: Long): TerminalUpdate?

    fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ): Boolean

    fun sendTerminalText(value: String): Boolean

    fun filesystemGeneration(): Long?

    fun reboot(): ProgramComputerState

    fun shutdown()
}

internal fun interface ComputerCarrierFactory {
    fun create(
        deviceId: Int,
        stateSink: ProgramComputerStateSink,
        filesystem: ComputerFileSystemContext?,
    ): ComputerCarrier
}

internal object RuntimeComputerCarrierFactory : ComputerCarrierFactory {
    override fun create(
        deviceId: Int,
        stateSink: ProgramComputerStateSink,
        filesystem: ComputerFileSystemContext?,
    ): ComputerCarrier {
        val context = requireNotNull(filesystem) { "production computer boot requires a filesystem context" }
        return ProgramComputerCarrier(
            ProgramComputer(
                deviceId = deviceId,
                stateSink = stateSink,
                store = context.store,
                computerId = context.computerId,
                romImage = context.romImage(),
                compilerRouter = context.compilerRouter,
            ),
        )
    }
}

private class ProgramComputerCarrier(
    private val delegate: ProgramComputer,
) : ComputerCarrier {
    override val state: ProgramComputerState
        get() = delegate.state

    override fun turnOn(): ProgramComputerState = delegate.turnOn()

    override fun serverTick(): ProgramComputerState = delegate.serverTick()

    override fun terminalFullState(): TerminalState? = delegate.terminalFullState()

    override fun terminalChangesSince(revision: Long): TerminalUpdate? = delegate.terminalChangesSince(revision)

    override fun sendTerminalKey(
        key: TerminalKey,
        action: TerminalKeyAction,
        modifiers: Set<TerminalModifier>,
    ): Boolean = delegate.sendTerminalKey(key, action, modifiers)

    override fun sendTerminalText(value: String): Boolean = delegate.sendTerminalText(value)

    override fun filesystemGeneration(): Long? = delegate.filesystemGeneration()

    override fun reboot(): ProgramComputerState = delegate.reboot()

    override fun shutdown() = delegate.shutdown()

    override fun close() = delegate.close()
}

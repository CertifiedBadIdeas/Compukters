/*
 * The Compukters Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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

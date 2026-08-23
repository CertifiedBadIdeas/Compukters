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
import ru.lazyhat.compukters.core.device.computer.ProgramImageSource
import ru.lazyhat.compukters.core.device.computer.ProgramTerminalSink

internal interface ComputerCarrier : AutoCloseable {
    val state: ProgramComputerState

    fun turnOn(): ProgramComputerState

    fun serverTick(): ProgramComputerState

    fun submitLine(line: String): Boolean

    fun reboot(): ProgramComputerState

    fun shutdown()
}

internal fun interface ComputerCarrierFactory {
    fun create(
        deviceId: Int,
        imageSource: ProgramImageSource,
        terminalSink: ProgramTerminalSink,
        stateSink: ProgramComputerStateSink,
    ): ComputerCarrier
}

internal object RuntimeComputerCarrierFactory : ComputerCarrierFactory {
    override fun create(
        deviceId: Int,
        imageSource: ProgramImageSource,
        terminalSink: ProgramTerminalSink,
        stateSink: ProgramComputerStateSink,
    ): ComputerCarrier =
        ProgramComputerCarrier(
            ProgramComputer(
                deviceId = deviceId,
                imageSource = imageSource,
                terminalSink = terminalSink,
                stateSink = stateSink,
            ),
        )
}

private class ProgramComputerCarrier(
    private val delegate: ProgramComputer,
) : ComputerCarrier {
    override val state: ProgramComputerState
        get() = delegate.state

    override fun turnOn(): ProgramComputerState = delegate.turnOn()

    override fun serverTick(): ProgramComputerState = delegate.serverTick()

    override fun submitLine(line: String): Boolean = delegate.submitLine(line)

    override fun reboot(): ProgramComputerState = delegate.reboot()

    override fun shutdown() = delegate.shutdown()

    override fun close() = delegate.close()
}

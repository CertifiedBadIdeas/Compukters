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

package ru.lazyhat.compukters.lang.runtime.vm

internal interface TerminalWireTransport : AutoCloseable {
    fun fullState(handle: Long): TerminalState

    fun changesSince(
        handle: Long,
        revision: Long,
    ): TerminalUpdate
}

internal class ByteArrayTerminalWireTransport(
    private val bridge: LowLevelVmBridge,
) : TerminalWireTransport {
    override fun fullState(handle: Long): TerminalState = TerminalWireDecoder(bridge.terminalFullState(handle)).fullState()

    override fun changesSince(
        handle: Long,
        revision: Long,
    ): TerminalUpdate = TerminalWireDecoder(bridge.terminalChangesSince(handle, revision)).update()

    override fun close() = Unit
}

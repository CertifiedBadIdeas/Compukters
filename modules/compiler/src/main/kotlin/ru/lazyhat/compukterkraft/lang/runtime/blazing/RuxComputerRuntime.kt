/*
 * The Compukter Kraft Developers
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

package ru.lazyhat.compukterkraft.lang.runtime.blazing

import java.io.ByteArrayOutputStream

interface RuxComputerRuntimeBindings {
    fun runUntilSignal(handle: Long): NativeLowImageVmSignal

    fun control(handle: Long): NativeRuxComputerControl

    fun pushSerialInput(
        handle: Long,
        bytes: ByteArray,
    )

    fun drainDebugOutput(handle: Long): ByteArray

    fun free(handle: Long)
}

object NativeRuxComputerRuntimeBindings : RuxComputerRuntimeBindings {
    override fun runUntilSignal(handle: Long): NativeLowImageVmSignal =
        NativeVmBindings.runRuxComputerUntilSignal(handle)

    override fun control(handle: Long): NativeRuxComputerControl =
        NativeVmBindings.ruxComputerControl(handle)

    override fun pushSerialInput(
        handle: Long,
        bytes: ByteArray,
    ) = NativeVmBindings.pushRuxComputerSerialInput(handle, bytes)

    override fun drainDebugOutput(handle: Long): ByteArray =
        NativeVmBindings.drainRuxComputerDebugOutput(handle)

    override fun free(handle: Long) =
        NativeVmBindings.freeRuxComputer(handle)
}

class RuxComputerRuntime(
    private val handle: Long,
    private val bindings: RuxComputerRuntimeBindings = NativeRuxComputerRuntimeBindings,
    private val defaultMaxTurnsPerTick: Int = 8,
) : AutoCloseable {
    private val terminalOutput = ByteArrayOutputStream()
    private var closed = false

    init {
        require(handle != 0L) { "Native Rux computer handle is zero" }
        require(defaultMaxTurnsPerTick >= 0) { "defaultMaxTurnsPerTick must be non-negative" }
    }

    fun pushInput(bytes: ByteArray) {
        ensureOpen()
        if (bytes.isNotEmpty()) {
            bindings.pushSerialInput(handle, bytes)
        }
    }

    fun pushInput(text: String) =
        pushInput(text.encodeToByteArray())

    fun tick(maxTurns: Int = defaultMaxTurnsPerTick): NativeRuxComputerControl {
        ensureOpen()
        require(maxTurns >= 0) { "maxTurns must be non-negative" }
        repeat(maxTurns) {
            val signal = bindings.runUntilSignal(handle)
            appendNativeOutput()
            if (signal != NativeLowImageVmSignal.Pause) {
                return bindings.control(handle)
            }
        }
        return bindings.control(handle)
    }

    fun control(): NativeRuxComputerControl {
        ensureOpen()
        return bindings.control(handle)
    }

    fun outputSnapshot(): ByteArray {
        ensureOpen()
        return terminalOutput.toByteArray()
    }

    fun clearOutput() {
        ensureOpen()
        terminalOutput.reset()
    }

    override fun close() {
        if (!closed) {
            closed = true
            bindings.free(handle)
        }
    }

    private fun appendNativeOutput() {
        val bytes = bindings.drainDebugOutput(handle)
        if (bytes.isNotEmpty()) {
            terminalOutput.write(bytes)
        }
    }

    private fun ensureOpen() {
        check(!closed) { "Rux computer runtime is closed" }
    }
}

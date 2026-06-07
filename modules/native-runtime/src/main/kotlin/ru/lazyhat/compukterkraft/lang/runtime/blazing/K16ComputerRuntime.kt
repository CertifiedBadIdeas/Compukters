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
import java.nio.file.Path

interface K16ComputerRuntimeBindings {
    fun advanceGameTick(handle: Long)

    fun runUntilSignal(handle: Long): NativeK16ComputerSignal

    fun control(handle: Long): NativeK16ComputerControl

    fun pushSerialInput(
        handle: Long,
        bytes: ByteArray,
    )

    fun pushKeyboardKeyDown(
        handle: Long,
        key: Int,
        repeat: Boolean,
        modifiers: Int,
    )

    fun pushKeyboardKeyUp(
        handle: Long,
        key: Int,
        modifiers: Int,
    )

    fun pushKeyboardChar(
        handle: Long,
        value: Byte,
    )

    fun pushKeyboardPasteBytes(
        handle: Long,
        bytes: ByteArray,
    )

    fun drainDebugOutput(handle: Long): ByteArray

    fun drainGpu0Frames(handle: Long): ByteArray

    fun storage0MediaSnapshot(handle: Long): ByteArray?

    fun machineSnapshot(handle: Long): ByteArray

    fun free(handle: Long)
}

object NativeK16ComputerRuntimeBindings : K16ComputerRuntimeBindings {
    override fun advanceGameTick(handle: Long) = NativeVmBindings.advanceK16ComputerGameTick(handle)

    override fun runUntilSignal(handle: Long): NativeK16ComputerSignal = NativeVmBindings.runK16ComputerUntilSignal(handle)

    override fun control(handle: Long): NativeK16ComputerControl = NativeVmBindings.k16ComputerControl(handle)

    override fun pushSerialInput(
        handle: Long,
        bytes: ByteArray,
    ) = NativeVmBindings.pushK16ComputerSerialInput(handle, bytes)

    override fun pushKeyboardKeyDown(
        handle: Long,
        key: Int,
        repeat: Boolean,
        modifiers: Int,
    ) = NativeVmBindings.pushK16ComputerKeyboardKeyDown(handle, key, repeat, modifiers)

    override fun pushKeyboardKeyUp(
        handle: Long,
        key: Int,
        modifiers: Int,
    ) = NativeVmBindings.pushK16ComputerKeyboardKeyUp(handle, key, modifiers)

    override fun pushKeyboardChar(
        handle: Long,
        value: Byte,
    ) = NativeVmBindings.pushK16ComputerKeyboardChar(handle, value)

    override fun pushKeyboardPasteBytes(
        handle: Long,
        bytes: ByteArray,
    ) = NativeVmBindings.pushK16ComputerKeyboardPasteBytes(handle, bytes)

    override fun drainDebugOutput(handle: Long): ByteArray = NativeVmBindings.drainK16ComputerDebugOutput(handle)

    override fun drainGpu0Frames(handle: Long): ByteArray = NativeVmBindings.drainK16ComputerGpu0Frames(handle)

    override fun storage0MediaSnapshot(handle: Long): ByteArray? = NativeVmBindings.k16ComputerStorage0MediaSnapshot(handle)

    override fun machineSnapshot(handle: Long): ByteArray = NativeVmBindings.k16ComputerMachineSnapshot(handle)

    override fun free(handle: Long) = NativeVmBindings.freeK16Computer(handle)
}

object K16ComputerRuntimeFactory {
    const val DEFAULT_MEMORY_SIZE: Int = 64 * 1024
    const val DEFAULT_SLICE_BUDGET_NANOS: Long = 1_000_000

    fun createFromBiosFlash(
        biosFlashPath: Path,
        storage0Path: Path,
        memorySize: Int = DEFAULT_MEMORY_SIZE,
        maxSteps: Long = DEFAULT_SLICE_BUDGET_NANOS,
    ): K16ComputerRuntime {
        val handle =
            NativeVmBindings.createK16ComputerFromBiosFlash(
                libraryPath = NativeLibraryLocator.requireLibraryPath(),
                biosFlashPath = biosFlashPath,
                memorySize = memorySize,
                maxSteps = maxSteps,
                storage0Path = storage0Path,
            )
        return K16ComputerRuntime(handle, bindings = NativeK16ComputerRuntimeBindings)
    }

    fun restoreFromBiosFlashSnapshot(
        biosFlashPath: Path,
        storage0Path: Path,
        snapshot: ByteArray,
        memorySize: Int = DEFAULT_MEMORY_SIZE,
    ): K16ComputerRuntime {
        val handle =
            NativeVmBindings.restoreK16ComputerFromBiosFlashSnapshot(
                libraryPath = NativeLibraryLocator.requireLibraryPath(),
                biosFlashPath = biosFlashPath,
                memorySize = memorySize,
                storage0Path = storage0Path,
                snapshot = snapshot,
            )
        return K16ComputerRuntime(handle, bindings = NativeK16ComputerRuntimeBindings)
    }
}

interface K16ComputerEndpoint : AutoCloseable {
    fun pushInput(bytes: ByteArray)

    fun pushKeyboardKeyDown(
        key: Int,
        repeat: Boolean,
        modifiers: Int = 0,
    )

    fun pushKeyboardKeyUp(
        key: Int,
        modifiers: Int = 0,
    )

    fun pushKeyboardChar(value: Byte)

    fun pushKeyboardPasteBytes(bytes: ByteArray)

    fun tick(maxTurns: Int = 8): NativeK16ComputerControl

    fun outputSnapshot(): ByteArray

    fun drainGpu0Frames(): ByteArray

    fun clearOutput()

    fun machineSnapshot(): ByteArray
}

class K16ComputerRuntime(
    private val handle: Long,
    private val bindings: K16ComputerRuntimeBindings = NativeK16ComputerRuntimeBindings,
    private val defaultMaxTurnsPerTick: Int = 8,
    private val storage0Sink: ((ByteArray) -> Unit)? = null,
) : K16ComputerEndpoint {
    private val terminalOutput = ByteArrayOutputStream()
    private var terminalControl: NativeK16ComputerControl? = null
    private var closed = false

    init {
        require(handle != 0L) { "Native K16 computer handle is zero" }
        require(defaultMaxTurnsPerTick >= 0) { "defaultMaxTurnsPerTick must be non-negative" }
    }

    override fun pushInput(bytes: ByteArray) {
        ensureOpen()
        if (bytes.isNotEmpty()) {
            bindings.pushSerialInput(handle, bytes)
        }
    }

    fun pushInput(text: String) = pushInput(text.encodeToByteArray())

    override fun pushKeyboardKeyDown(
        key: Int,
        repeat: Boolean,
        modifiers: Int,
    ) {
        ensureOpen()
        bindings.pushKeyboardKeyDown(handle, key, repeat, modifiers)
    }

    override fun pushKeyboardKeyUp(
        key: Int,
        modifiers: Int,
    ) {
        ensureOpen()
        bindings.pushKeyboardKeyUp(handle, key, modifiers)
    }

    override fun pushKeyboardChar(value: Byte) {
        ensureOpen()
        bindings.pushKeyboardChar(handle, value)
    }

    override fun pushKeyboardPasteBytes(bytes: ByteArray) {
        ensureOpen()
        if (bytes.isNotEmpty()) {
            bindings.pushKeyboardPasteBytes(handle, bytes)
        }
    }

    fun tick(): NativeK16ComputerControl = tick(defaultMaxTurnsPerTick)

    override fun tick(maxTurns: Int): NativeK16ComputerControl {
        ensureOpen()
        require(maxTurns >= 0) { "maxTurns must be non-negative" }
        terminalControl?.let { return it }
        bindings.advanceGameTick(handle)
        repeat(maxTurns) {
            val signal = bindings.runUntilSignal(handle)
            appendNativeOutput()
            val control = bindings.control(handle)
            if (signal != NativeK16ComputerSignal.Pause) {
                if (signal == NativeK16ComputerSignal.Halt) {
                    terminalControl = control
                }
                return control
            }
        }
        return bindings.control(handle)
    }

    fun control(): NativeK16ComputerControl {
        ensureOpen()
        return bindings.control(handle)
    }

    override fun outputSnapshot(): ByteArray {
        ensureOpen()
        return terminalOutput.toByteArray()
    }

    override fun drainGpu0Frames(): ByteArray {
        ensureOpen()
        return bindings.drainGpu0Frames(handle)
    }

    override fun clearOutput() {
        ensureOpen()
        terminalOutput.reset()
    }

    override fun machineSnapshot(): ByteArray {
        ensureOpen()
        return bindings.machineSnapshot(handle)
    }

    override fun close() {
        if (!closed) {
            try {
                storage0Sink?.let { sink ->
                    bindings.storage0MediaSnapshot(handle)?.let { snapshot ->
                        sink(snapshot)
                    }
                }
            } finally {
                closed = true
                bindings.free(handle)
            }
        }
    }

    private fun appendNativeOutput() {
        val bytes = bindings.drainDebugOutput(handle)
        if (bytes.isNotEmpty()) {
            terminalOutput.write(bytes)
        }
    }

    private fun ensureOpen() {
        check(!closed) { "K16 computer runtime is closed" }
    }
}

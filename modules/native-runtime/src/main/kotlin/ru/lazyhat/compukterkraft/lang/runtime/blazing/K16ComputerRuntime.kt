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
    fun runUntilSignal(handle: Long): NativeRuxComputerSignal

    fun control(handle: Long): NativeRuxComputerControl

    fun pushSerialInput(
        handle: Long,
        bytes: ByteArray,
    )

    fun drainDebugOutput(handle: Long): ByteArray

    fun display0Snapshot(handle: Long): NativeRuxComputerDisplaySnapshot?

    fun storage0MediaSnapshot(handle: Long): ByteArray?

    fun machineSnapshot(handle: Long): ByteArray

    fun free(handle: Long)
}

object NativeK16ComputerRuntimeBindings : K16ComputerRuntimeBindings {
    override fun runUntilSignal(handle: Long): NativeRuxComputerSignal =
        NativeVmBindings.runK16ComputerUntilSignal(handle)

    override fun control(handle: Long): NativeRuxComputerControl =
        NativeVmBindings.ruxComputerControl(handle)

    override fun pushSerialInput(
        handle: Long,
        bytes: ByteArray,
    ) = NativeVmBindings.pushRuxComputerSerialInput(handle, bytes)

    override fun drainDebugOutput(handle: Long): ByteArray =
        NativeVmBindings.drainRuxComputerDebugOutput(handle)

    override fun display0Snapshot(handle: Long): NativeRuxComputerDisplaySnapshot? =
        NativeVmBindings.ruxComputerDisplay0Snapshot(handle)

    override fun storage0MediaSnapshot(handle: Long): ByteArray? =
        NativeVmBindings.ruxComputerStorage0MediaSnapshot(handle)

    override fun machineSnapshot(handle: Long): ByteArray =
        NativeVmBindings.ruxComputerMachineSnapshot(handle)

    override fun free(handle: Long) =
        NativeVmBindings.freeRuxComputer(handle)
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

    fun tick(maxTurns: Int = 8): NativeRuxComputerControl

    fun outputSnapshot(): ByteArray

    fun display0Snapshot(): NativeRuxComputerDisplaySnapshot?

    fun pollDisplay0Snapshot(): NativeRuxComputerDisplaySnapshot?

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
    private var lastDisplay0Sequence: Long? = null
    private var closed = false

    init {
        require(handle != 0L) { "Native Rux computer handle is zero" }
        require(defaultMaxTurnsPerTick >= 0) { "defaultMaxTurnsPerTick must be non-negative" }
    }

    override fun pushInput(bytes: ByteArray) {
        ensureOpen()
        if (bytes.isNotEmpty()) {
            bindings.pushSerialInput(handle, bytes)
        }
    }

    fun pushInput(text: String) =
        pushInput(text.encodeToByteArray())

    fun tick(): NativeRuxComputerControl =
        tick(defaultMaxTurnsPerTick)

    override fun tick(maxTurns: Int): NativeRuxComputerControl {
        ensureOpen()
        require(maxTurns >= 0) { "maxTurns must be non-negative" }
        repeat(maxTurns) {
            val signal = bindings.runUntilSignal(handle)
            appendNativeOutput()
            if (signal != NativeRuxComputerSignal.Pause) {
                return bindings.control(handle)
            }
        }
        return bindings.control(handle)
    }

    fun control(): NativeRuxComputerControl {
        ensureOpen()
        return bindings.control(handle)
    }

    override fun outputSnapshot(): ByteArray {
        ensureOpen()
        return terminalOutput.toByteArray()
    }

    override fun display0Snapshot(): NativeRuxComputerDisplaySnapshot? {
        ensureOpen()
        return bindings.display0Snapshot(handle)
    }

    override fun pollDisplay0Snapshot(): NativeRuxComputerDisplaySnapshot? {
        ensureOpen()
        val snapshot = bindings.display0Snapshot(handle) ?: run {
            lastDisplay0Sequence = null
            return null
        }
        if (lastDisplay0Sequence == snapshot.sequence) {
            return null
        }
        lastDisplay0Sequence = snapshot.sequence
        return snapshot
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
        check(!closed) { "Rux computer runtime is closed" }
    }
}

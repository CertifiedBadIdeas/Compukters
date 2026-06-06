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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class K16ComputerRuntimeTest {
    @Test
    fun pushesSerialInputAndKeepsSharedOutputSnapshot() {
        val bindings = EchoBindings()
        val runtime = K16ComputerRuntime(handle = 7L, bindings = bindings, defaultMaxTurnsPerTick = 4)

        runtime.pushInput("K16!".encodeToByteArray())
        val control = runtime.tick()

        assertEquals(NativeK16ComputerControl(status = 1, exitCode = 0, panicCode = 0), control)
        assertEquals("K16!", runtime.outputSnapshot().decodeToString())
        assertEquals("K16!", runtime.outputSnapshot().decodeToString())
        assertEquals(listOf("K16!".encodeToByteArray().toList()), bindings.serialInputs.map { it.toList() })
    }

    @Test
    fun freesNativeHandleOnlyOnce() {
        val bindings = EchoBindings()
        val runtime = K16ComputerRuntime(handle = 9L, bindings = bindings)

        runtime.close()
        runtime.close()

        assertEquals(listOf(9L), bindings.freedHandles)
        assertFailsWith<IllegalStateException> {
            runtime.pushInput(byteArrayOf(1))
        }
    }

    @Test
    fun closesAfterPersistingStorage0Snapshot() {
        val bindings = EchoBindings()
        bindings.storage0Media = byteArrayOf(10, 20, 30)
        val persisted = mutableListOf<ByteArray>()
        val runtime =
            K16ComputerRuntime(
                handle = 13L,
                bindings = bindings,
                storage0Sink = { persisted += it.copyOf() },
            )

        runtime.close()

        assertEquals(1, persisted.size)
        assertContentEquals(byteArrayOf(10, 20, 30), persisted.single())
        assertEquals(listOf(13L), bindings.freedHandles)
    }

    @Test
    fun exposesNativeMachineSnapshot() {
        val bindings = EchoBindings()
        bindings.machineSnapshot = byteArrayOf(0x52, 0x55, 0x58)
        val runtime = K16ComputerRuntime(handle = 15L, bindings = bindings)

        assertContentEquals(byteArrayOf(0x52, 0x55, 0x58), runtime.machineSnapshot())
        assertEquals(listOf(15L), bindings.machineSnapshotHandles)
    }

    @Test
    fun skipsNativeExecutionAfterHaltSignal() {
        val bindings = EchoBindings()
        bindings.control = NativeK16ComputerControl(status = 3, exitCode = 0, panicCode = 2)
        bindings.signal = NativeK16ComputerSignal.Halt
        val runtime = K16ComputerRuntime(handle = 21L, bindings = bindings, defaultMaxTurnsPerTick = 8)

        assertEquals(NativeK16ComputerControl(status = 3, exitCode = 0, panicCode = 2), runtime.tick())
        assertEquals(NativeK16ComputerControl(status = 3, exitCode = 0, panicCode = 2), runtime.tick())

        assertEquals(1, bindings.runUntilSignalCalls)
        assertEquals(listOf(21L), bindings.advanceGameTickHandles)
    }

    @Test
    fun advancesGameTickOncePerRuntimeTickBeforeNativeTurns() {
        val bindings = EchoBindings()
        val runtime = K16ComputerRuntime(handle = 29L, bindings = bindings, defaultMaxTurnsPerTick = 3)

        runtime.tick()

        assertEquals(3, bindings.runUntilSignalCalls)
        assertEquals(listOf(29L), bindings.advanceGameTickHandles)
        assertEquals(listOf("advance", "run", "run", "run"), bindings.callOrder)
    }

    @Test
    fun returnsAfterYieldSignalWithoutConsumingTheNextTurn() {
        val bindings = EchoBindings()
        bindings.signals += NativeK16ComputerSignal.Yield
        bindings.signals += NativeK16ComputerSignal.Halt
        bindings.control = NativeK16ComputerControl(status = 1, exitCode = 0, panicCode = 0)
        val runtime = K16ComputerRuntime(handle = 25L, bindings = bindings, defaultMaxTurnsPerTick = 8)

        assertEquals(NativeK16ComputerControl(status = 1, exitCode = 0, panicCode = 0), runtime.tick())
        assertEquals(1, bindings.runUntilSignalCalls)
        assertEquals(listOf(25L), bindings.advanceGameTickHandles)

        bindings.control = NativeK16ComputerControl(status = 3, exitCode = 0, panicCode = 0)

        assertEquals(NativeK16ComputerControl(status = 3, exitCode = 0, panicCode = 0), runtime.tick())
        assertEquals(2, bindings.runUntilSignalCalls)
        assertEquals(listOf(25L, 25L), bindings.advanceGameTickHandles)
    }

    @Test
    fun exposesDisplaySnapshotAndPollsOnlyChangedSequences() {
        val bindings = EchoBindings()
        val runtime = K16ComputerRuntime(handle = 11L, bindings = bindings)
        val first =
            NativeK16ComputerDisplaySnapshot(
                columns = 80,
                rows = 25,
                cursorX = 1,
                cursorY = 0,
                sequence = 1,
                cells = byteArrayOf('A'.code.toByte()),
            )
        val second =
            NativeK16ComputerDisplaySnapshot(
                columns = 80,
                rows = 25,
                cursorX = 2,
                cursorY = 0,
                sequence = 2,
                cells = byteArrayOf('A'.code.toByte(), 'B'.code.toByte()),
            )

        bindings.displaySnapshot = first

        assertEquals(first, runtime.display0Snapshot())
        assertEquals(first, runtime.pollDisplay0Snapshot())
        assertEquals(null, runtime.pollDisplay0Snapshot())

        bindings.displaySnapshot = second

        assertEquals(second, runtime.pollDisplay0Snapshot())
        assertEquals(null, runtime.pollDisplay0Snapshot())
    }

    @Test
    fun rejectsZeroHandle() {
        assertFailsWith<IllegalArgumentException> {
            K16ComputerRuntime(handle = 0L, bindings = EchoBindings())
        }
    }

    private class EchoBindings : K16ComputerRuntimeBindings {
        val serialInputs = mutableListOf<ByteArray>()
        val freedHandles = mutableListOf<Long>()
        val machineSnapshotHandles = mutableListOf<Long>()
        val advanceGameTickHandles = mutableListOf<Long>()
        val callOrder = mutableListOf<String>()
        var displaySnapshot: NativeK16ComputerDisplaySnapshot? = null
        var framebufferFrames: ByteArray = ByteArray(0)
        var storage0Media: ByteArray? = null
        var machineSnapshot: ByteArray = ByteArray(0)
        var control: NativeK16ComputerControl = NativeK16ComputerControl(status = 1, exitCode = 0, panicCode = 0)
        var signal: NativeK16ComputerSignal = NativeK16ComputerSignal.Pause
        val signals = ArrayDeque<NativeK16ComputerSignal>()
        var runUntilSignalCalls = 0
            private set
        private val pendingOutput = ArrayDeque<ByteArray>()

        override fun runUntilSignal(handle: Long): NativeK16ComputerSignal {
            runUntilSignalCalls += 1
            callOrder += "run"
            if (signals.isNotEmpty()) {
                return signals.removeFirst()
            }
            return signal
        }

        override fun advanceGameTick(handle: Long) {
            advanceGameTickHandles += handle
            callOrder += "advance"
        }

        override fun control(handle: Long): NativeK16ComputerControl =
            control

        override fun pushSerialInput(
            handle: Long,
            bytes: ByteArray,
        ) {
            serialInputs += bytes.copyOf()
            pendingOutput += bytes.copyOf()
        }

        override fun drainDebugOutput(handle: Long): ByteArray =
            if (pendingOutput.isEmpty()) {
                ByteArray(0)
            } else {
                pendingOutput.removeFirst()
            }

        override fun display0Snapshot(handle: Long): NativeK16ComputerDisplaySnapshot? = displaySnapshot

        override fun drainFramebuffer0Frames(handle: Long): ByteArray = framebufferFrames.copyOf()

        override fun storage0MediaSnapshot(handle: Long): ByteArray? = storage0Media?.copyOf()

        override fun machineSnapshot(handle: Long): ByteArray {
            machineSnapshotHandles += handle
            return machineSnapshot.copyOf()
        }

        override fun free(handle: Long) {
            freedHandles += handle
        }
    }
}

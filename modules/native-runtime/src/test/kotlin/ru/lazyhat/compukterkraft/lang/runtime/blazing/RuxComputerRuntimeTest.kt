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

class RuxComputerRuntimeTest {
    @Test
    fun pushesSerialInputAndKeepsSharedOutputSnapshot() {
        val bindings = EchoBindings()
        val runtime = RuxComputerRuntime(handle = 7L, bindings = bindings, defaultMaxTurnsPerTick = 4)

        runtime.pushInput("Rux!".encodeToByteArray())
        val control = runtime.tick()

        assertEquals(NativeRuxComputerControl(status = 1, exitCode = 0, panicCode = 0), control)
        assertEquals("Rux!", runtime.outputSnapshot().decodeToString())
        assertEquals("Rux!", runtime.outputSnapshot().decodeToString())
        assertEquals(listOf("Rux!".encodeToByteArray().toList()), bindings.serialInputs.map { it.toList() })
    }

    @Test
    fun freesNativeHandleOnlyOnce() {
        val bindings = EchoBindings()
        val runtime = RuxComputerRuntime(handle = 9L, bindings = bindings)

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
            RuxComputerRuntime(
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
    fun exposesDisplaySnapshotAndPollsOnlyChangedSequences() {
        val bindings = EchoBindings()
        val runtime = RuxComputerRuntime(handle = 11L, bindings = bindings)
        val first =
            NativeRuxComputerDisplaySnapshot(
                columns = 80,
                rows = 25,
                cursorX = 1,
                cursorY = 0,
                sequence = 1,
                cells = byteArrayOf('A'.code.toByte()),
            )
        val second =
            NativeRuxComputerDisplaySnapshot(
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
            RuxComputerRuntime(handle = 0L, bindings = EchoBindings())
        }
    }

    private class EchoBindings : RuxComputerRuntimeBindings {
        val serialInputs = mutableListOf<ByteArray>()
        val freedHandles = mutableListOf<Long>()
        var displaySnapshot: NativeRuxComputerDisplaySnapshot? = null
        var storage0Media: ByteArray? = null
        private val pendingOutput = ArrayDeque<ByteArray>()

        override fun runUntilSignal(handle: Long): NativeLowImageVmSignal = NativeLowImageVmSignal.Pause

        override fun control(handle: Long): NativeRuxComputerControl =
            NativeRuxComputerControl(status = 1, exitCode = 0, panicCode = 0)

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

        override fun display0Snapshot(handle: Long): NativeRuxComputerDisplaySnapshot? = displaySnapshot

        override fun storage0MediaSnapshot(handle: Long): ByteArray? = storage0Media?.copyOf()

        override fun free(handle: Long) {
            freedHandles += handle
        }
    }
}

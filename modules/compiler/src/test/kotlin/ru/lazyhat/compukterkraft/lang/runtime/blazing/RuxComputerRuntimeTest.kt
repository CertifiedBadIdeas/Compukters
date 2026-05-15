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
    fun rejectsZeroHandle() {
        assertFailsWith<IllegalArgumentException> {
            RuxComputerRuntime(handle = 0L, bindings = EchoBindings())
        }
    }

    private class EchoBindings : RuxComputerRuntimeBindings {
        val serialInputs = mutableListOf<ByteArray>()
        val freedHandles = mutableListOf<Long>()
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

        override fun free(handle: Long) {
            freedHandles += handle
        }
    }
}

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
    fun pushesKeyboard0InputThroughDedicatedBindings() {
        val bindings = EchoBindings()
        val runtime = K16ComputerRuntime(handle = 8L, bindings = bindings)

        runtime.pushKeyboardKeyDown(key = 257, repeat = true, modifiers = 2)
        runtime.pushKeyboardKeyUp(key = 257, modifiers = 2)
        runtime.pushKeyboardChar('R'.code.toByte())
        runtime.pushKeyboardPasteBytes("K16".encodeToByteArray())
        runtime.pushKeyboardPasteBytes(ByteArray(0))

        assertEquals(listOf(KeyboardKeyDown(handle = 8L, key = 257, repeat = true, modifiers = 2)), bindings.keyboardKeyDowns)
        assertEquals(listOf(KeyboardKeyUp(handle = 8L, key = 257, modifiers = 2)), bindings.keyboardKeyUps)
        assertEquals(listOf(KeyboardChar(handle = 8L, value = 'R'.code.toByte())), bindings.keyboardChars)
        assertEquals(listOf("K16"), bindings.keyboardPasteBytes.map { it.bytes.decodeToString() })
        assertEquals(emptyList(), bindings.serialInputs.map { it.decodeToString() })
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
        assertEquals(emptyList(), bindings.advanceGameTickHandles)
    }

    @Test
    fun waitSignalRemainsNonTerminal() {
        val bindings = EchoBindings()
        bindings.control = NativeK16ComputerControl(status = 2, exitCode = 0, panicCode = 0)
        bindings.signal = NativeK16ComputerSignal.Wait
        val runtime = K16ComputerRuntime(handle = 23L, bindings = bindings, defaultMaxTurnsPerTick = 8)

        assertEquals(NativeK16ComputerControl(status = 2, exitCode = 0, panicCode = 0), runtime.tick())
        assertEquals(NativeK16ComputerControl(status = 2, exitCode = 0, panicCode = 0), runtime.tick())

        assertEquals(2, bindings.runUntilSignalCalls)
        assertEquals(emptyList(), bindings.advanceGameTickHandles)
    }

    @Test
    fun tickUntilSignalReportsWaitWithCurrentControl() {
        val bindings = EchoBindings()
        bindings.control = NativeK16ComputerControl(status = 2, exitCode = 0, panicCode = 0)
        bindings.signal = NativeK16ComputerSignal.Wait
        val runtime = K16ComputerRuntime(handle = 24L, bindings = bindings, defaultMaxTurnsPerTick = 8)

        assertEquals(
            K16ComputerTickResult(
                signal = NativeK16ComputerSignal.Wait,
                control = NativeK16ComputerControl(status = 2, exitCode = 0, panicCode = 0),
            ),
            runtime.tickUntilSignal(),
        )

        assertEquals(1, bindings.runUntilSignalCalls)
        assertEquals(emptyList(), bindings.advanceGameTickHandles)
    }

    @Test
    fun tickUntilSignalReportsYieldWithoutConsumingTheNextTurn() {
        val bindings = EchoBindings()
        bindings.signals += NativeK16ComputerSignal.Yield
        bindings.signals += NativeK16ComputerSignal.Halt
        bindings.control = NativeK16ComputerControl(status = 2, exitCode = 0, panicCode = 0)
        val runtime = K16ComputerRuntime(handle = 26L, bindings = bindings, defaultMaxTurnsPerTick = 8)

        assertEquals(
            K16ComputerTickResult(
                signal = NativeK16ComputerSignal.Yield,
                control = NativeK16ComputerControl(status = 2, exitCode = 0, panicCode = 0),
            ),
            runtime.tickUntilSignal(),
        )

        assertEquals(1, bindings.runUntilSignalCalls)
    }

    @Test
    fun advancesGameTicksWithoutRunningNativeTurns() {
        val bindings = EchoBindings()
        val runtime = K16ComputerRuntime(handle = 29L, bindings = bindings, defaultMaxTurnsPerTick = 3)

        runtime.advanceGameTicks(3)

        assertEquals(0, bindings.runUntilSignalCalls)
        assertEquals(listOf(29L, 29L, 29L), bindings.advanceGameTickHandles)
        assertEquals(listOf("advance", "advance", "advance"), bindings.callOrder)
    }

    @Test
    fun runtimeTickDoesNotAdvanceGameTicks() {
        val bindings = EchoBindings()
        val runtime = K16ComputerRuntime(handle = 29L, bindings = bindings, defaultMaxTurnsPerTick = 3)

        runtime.tick()

        assertEquals(3, bindings.runUntilSignalCalls)
        assertEquals(emptyList(), bindings.advanceGameTickHandles)
        assertEquals(listOf("run", "run", "run"), bindings.callOrder)
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
        assertEquals(emptyList(), bindings.advanceGameTickHandles)

        bindings.control = NativeK16ComputerControl(status = 3, exitCode = 0, panicCode = 0)

        assertEquals(NativeK16ComputerControl(status = 3, exitCode = 0, panicCode = 0), runtime.tick())
        assertEquals(2, bindings.runUntilSignalCalls)
        assertEquals(emptyList(), bindings.advanceGameTickHandles)
    }

    @Test
    fun rejectsZeroHandle() {
        assertFailsWith<IllegalArgumentException> {
            K16ComputerRuntime(handle = 0L, bindings = EchoBindings())
        }
    }

    private class EchoBindings : K16ComputerRuntimeBindings {
        val serialInputs = mutableListOf<ByteArray>()
        val keyboardKeyDowns = mutableListOf<KeyboardKeyDown>()
        val keyboardKeyUps = mutableListOf<KeyboardKeyUp>()
        val keyboardChars = mutableListOf<KeyboardChar>()
        val keyboardPasteBytes = mutableListOf<KeyboardPasteBytes>()
        val freedHandles = mutableListOf<Long>()
        val machineSnapshotHandles = mutableListOf<Long>()
        val advanceGameTickHandles = mutableListOf<Long>()
        val callOrder = mutableListOf<String>()
        var gpuFrames: ByteArray = ByteArray(0)
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

        override fun pushKeyboardKeyDown(
            handle: Long,
            key: Int,
            repeat: Boolean,
            modifiers: Int,
        ) {
            keyboardKeyDowns += KeyboardKeyDown(handle, key, repeat, modifiers)
        }

        override fun pushKeyboardKeyUp(
            handle: Long,
            key: Int,
            modifiers: Int,
        ) {
            keyboardKeyUps += KeyboardKeyUp(handle, key, modifiers)
        }

        override fun pushKeyboardChar(
            handle: Long,
            value: Byte,
        ) {
            keyboardChars += KeyboardChar(handle, value)
        }

        override fun pushKeyboardPasteBytes(
            handle: Long,
            bytes: ByteArray,
        ) {
            keyboardPasteBytes += KeyboardPasteBytes(handle, bytes.copyOf())
        }

        override fun drainDebugOutput(handle: Long): ByteArray =
            if (pendingOutput.isEmpty()) {
                ByteArray(0)
            } else {
                pendingOutput.removeFirst()
            }

        override fun drainGpu0Frames(handle: Long): ByteArray = gpuFrames.copyOf()

        override fun storage0MediaSnapshot(handle: Long): ByteArray? = storage0Media?.copyOf()

        override fun machineSnapshot(handle: Long): ByteArray {
            machineSnapshotHandles += handle
            return machineSnapshot.copyOf()
        }

        override fun free(handle: Long) {
            freedHandles += handle
        }
    }

    private data class KeyboardKeyDown(
        val handle: Long,
        val key: Int,
        val repeat: Boolean,
        val modifiers: Int,
    )

    private data class KeyboardKeyUp(
        val handle: Long,
        val key: Int,
        val modifiers: Int,
    )

    private data class KeyboardChar(
        val handle: Long,
        val value: Byte,
    )

    private data class KeyboardPasteBytes(
        val handle: Long,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is KeyboardPasteBytes &&
                        handle == other.handle &&
                        bytes.contentEquals(other.bytes)
                )

        override fun hashCode(): Int =
            31 * handle.hashCode() + bytes.contentHashCode()
    }
}

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

import ru.lazyhat.compukterkraft.lang.runtime.VmValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NativeVmSignalTest {
    @Test
    fun decodesHaltIntSignal() {
        assertEquals(
            NativeVmSignal.Halt(NativeVmValue.IntValue(42)),
            NativeVmSignal.decode(byteArrayOf(0, 3, 42, 0, 0, 0)),
        )
    }

    @Test
    fun decodesHaltStringSignal() {
        assertEquals(
            NativeVmSignal.Halt(NativeVmValue.StringValue("ok")),
            NativeVmSignal.decode(byteArrayOf(0, 5, 2, 0, 0, 0, 'o'.code.toByte(), 'k'.code.toByte())),
        )
    }

    @Test
    fun decodesPauseYieldAndSleepSignals() {
        assertEquals(NativeVmSignal.Pause, NativeVmSignal.decode(byteArrayOf(1)))
        assertEquals(NativeVmSignal.Yield, NativeVmSignal.decode(byteArrayOf(2)))
        assertEquals(NativeVmSignal.Sleep(9), NativeVmSignal.decode(byteArrayOf(3, 9, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun decodesErrorSignal() {
        assertEquals(
            NativeVmSignal.Error("bad bytecode"),
            NativeVmSignal.decode(byteArrayOf(255.toByte()) + stringBytes("bad bytecode")),
        )
    }

    @Test
    fun primitiveNativeValuesConvertToRuntimeValues() {
        assertEquals(VmValue.UnitValue, NativeVmValue.UnitValue.toVmValue("system", "log"))
        assertEquals(VmValue.NullValue, NativeVmValue.NullValue.toVmValue("system", "log"))
        assertEquals(VmValue.BoolValue(true), NativeVmValue.BoolValue(true).toVmValue("display", "isAttached"))
        assertEquals(VmValue.IntValue(7), NativeVmValue.IntValue(7).toVmValue("display", "primary"))
        assertEquals(VmValue.LongValue(9), NativeVmValue.LongValue(9).toVmValue("system", "currentTick"))
        assertEquals(VmValue.StringValue("ok"), NativeVmValue.StringValue("ok").toVmValue("system", "label"))
    }

    @Test
    fun primitiveRuntimeValuesEncodeForResume() {
        assertContentEquals(byteArrayOf(0), VmValue.UnitValue.toNativeBytes("", "yield"))
        assertContentEquals(byteArrayOf(1), VmValue.NullValue.toNativeBytes("test", "null"))
        assertContentEquals(byteArrayOf(2, 1), VmValue.BoolValue(true).toNativeBytes("display", "isAttached"))
        assertContentEquals(byteArrayOf(3, 7, 0, 0, 0), VmValue.IntValue(7).toNativeBytes("display", "primary"))
    }

    private fun stringBytes(value: String): ByteArray =
        intBytes(value.encodeToByteArray().size) + value.encodeToByteArray()

    private fun intBytes(value: Int): ByteArray =
        byteArrayOf(
            value.toByte(),
            (value ushr 8).toByte(),
            (value ushr 16).toByte(),
            (value ushr 24).toByte(),
        )
}

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

internal sealed interface NativeVmValue {
    data object UnitValue : NativeVmValue
    data object NullValue : NativeVmValue
    data class BoolValue(val value: Boolean) : NativeVmValue
    data class IntValue(val value: Int) : NativeVmValue
    data class LongValue(val value: Long) : NativeVmValue
    data class StringValue(val value: String) : NativeVmValue
}

internal sealed interface NativeVmSignal {
    data class Halt(val value: NativeVmValue) : NativeVmSignal
    data object Pause : NativeVmSignal
    data object Yield : NativeVmSignal
    data class Sleep(val ticks: Long) : NativeVmSignal
    data class HostCall(
        val moduleName: String,
        val functionName: String,
        val arguments: List<NativeVmValue>,
    ) : NativeVmSignal
    data class Error(val message: String) : NativeVmSignal

    companion object {
        fun decode(bytes: ByteArray): NativeVmSignal {
            val reader = Reader(bytes)
            return when (val tag = reader.u8()) {
                0 -> Halt(reader.value())
                1 -> Pause
                2 -> Yield
                3 -> Sleep(reader.i64())
                4 -> {
                    val moduleName = reader.string()
                    val functionName = reader.string()
                    val arguments = List(reader.i32()) { reader.value() }
                    HostCall(moduleName, functionName, arguments)
                }
                255 -> Error(reader.string())
                else -> error("Unknown native VM signal tag $tag")
            }
        }
    }
}

private class Reader(
    private val bytes: ByteArray,
) {
    private var offset = 0

    fun u8(): Int {
        require(offset < bytes.size) { "Unexpected end of native VM signal" }
        return bytes[offset++].toInt() and 0xff
    }

    fun i32(): Int {
        val b0 = u8()
        val b1 = u8()
        val b2 = u8()
        val b3 = u8()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun i64(): Long {
        var value = 0L
        repeat(8) { index ->
            value = value or ((u8().toLong() and 0xffL) shl (index * 8))
        }
        return value
    }

    fun string(): String {
        val length = i32()
        require(length >= 0) { "Negative native VM string length $length" }
        require(offset + length <= bytes.size) { "Unexpected end of native VM string" }
        val value = bytes.decodeToString(offset, offset + length)
        offset += length
        return value
    }

    fun value(): NativeVmValue =
        when (val tag = u8()) {
            0 -> NativeVmValue.UnitValue
            1 -> NativeVmValue.NullValue
            2 -> NativeVmValue.BoolValue(u8() != 0)
            3 -> NativeVmValue.IntValue(i32())
            4 -> NativeVmValue.LongValue(i64())
            5 -> NativeVmValue.StringValue(string())
            else -> error("Unknown native VM value tag $tag")
        }
}

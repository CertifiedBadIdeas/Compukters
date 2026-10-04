/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.lazyhat.compukters.lang.runtime.capability

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Caller-owned response bytes. Numeric payloads retain exact IEEE bits and strings retain UTF-16 code units. */
internal object HostResponseWire {
    const val MAXIMUM_BYTES: Int = 64 * 1024
    const val MAXIMUM_STRING_UNITS: Int = 4096

    fun encode(response: HostResponse): ByteArray {
        val size = 1 + encodedSize(response)
        require(size <= MAXIMUM_BYTES) { "host response exceeds byte limit" }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(1)
        write(buffer, response)
        return buffer.array()
    }

    private fun encodedSize(response: HostResponse): Int =
        when (response) {
            HostResponse.UnitSuccess -> {
                1
            }

            is HostResponse.IntSuccess, is HostResponse.FloatSuccess -> {
                5
            }

            is HostResponse.LongSuccess, is HostResponse.DoubleSuccess -> {
                9
            }

            is HostResponse.BoolSuccess -> {
                2
            }

            is HostResponse.CharSuccess -> {
                3
            }

            is HostResponse.StringSuccess -> {
                require(response.value.length <= MAXIMUM_STRING_UNITS) { "host response string exceeds limit" }
                3 + 2 * response.value.length
            }

            is HostResponse.RecordSuccess -> {
                val record = response.value
                4 + record.schema.typeName.length +
                    record.schema.fields.zip(record.values).sumOf { (field, value) ->
                        1 + field.name.length + encodedSize(value)
                    }
            }

            is HostResponse.Failure -> {
                error("failures use the host failure boundary")
            }
        }

    private fun write(
        buffer: ByteBuffer,
        response: HostResponse,
    ) {
        buffer.put(response.valueType().wireCode.toByte())
        when (response) {
            HostResponse.UnitSuccess -> {
                Unit
            }

            is HostResponse.IntSuccess -> {
                buffer.putInt(response.value)
            }

            is HostResponse.LongSuccess -> {
                buffer.putLong(response.value)
            }

            is HostResponse.FloatSuccess -> {
                buffer.putInt(response.value.toRawBits())
            }

            is HostResponse.DoubleSuccess -> {
                buffer.putLong(response.value.toRawBits())
            }

            is HostResponse.BoolSuccess -> {
                buffer.put(if (response.value) 1.toByte() else 0.toByte())
            }

            is HostResponse.CharSuccess -> {
                buffer.putChar(response.value)
            }

            is HostResponse.StringSuccess -> {
                buffer.putShort(response.value.length.toShort())
                response.value.forEach(buffer::putChar)
            }

            is HostResponse.RecordSuccess -> {
                val record = response.value
                buffer.putShort(
                    record.schema.typeName.length
                        .toShort(),
                )
                record.schema.typeName.forEach { buffer.put(it.code.toByte()) }
                buffer.put(record.values.size.toByte())
                record.schema.fields.zip(record.values).forEach { (field, value) ->
                    buffer.put(field.name.length.toByte())
                    field.name.forEach { buffer.put(it.code.toByte()) }
                    write(buffer, value)
                }
            }

            is HostResponse.Failure -> {
                error("failures use the host failure boundary")
            }
        }
    }
}

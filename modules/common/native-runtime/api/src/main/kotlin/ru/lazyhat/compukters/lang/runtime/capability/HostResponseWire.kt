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
        val size = checkedSize(response)
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(1)
        write(buffer, response)
        return buffer.array()
    }

    /** Conservative queue charge, not a JVM heap measurement; shared schemas are charged in full. */
    fun retainedBytes(response: HostResponse): Long {
        if (response !is HostResponse.Failure) checkedSize(response)
        return charge(response)
    }

    private fun checkedSize(response: HostResponse): Int =
        (1 + encodedSize(response)).also {
            require(it <= MAXIMUM_BYTES) { "host response exceeds byte limit" }
        }

    private fun charge(response: HostResponse): Long =
        64L +
            when (response) {
                is HostResponse.StringSuccess -> {
                    2L * response.value.length
                }

                is HostResponse.ByteArraySuccess -> {
                    response.byteSize.toLong()
                }

                is HostResponse.Failure -> {
                    2L * response.detail.length
                }

                is HostResponse.RecordSuccess -> {
                    val record = response.value
                    var bytes = 64L + 2L * record.schema.typeName.length
                    for (index in record.values.indices) {
                        val field = record.schema.fields[index]
                        bytes += 64L + 2L * field.name.length + charge(record.values[index])
                    }
                    bytes
                }

                else -> {
                    0L
                }
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

            is HostResponse.ByteArraySuccess -> {
                5 + response.byteSize
            }

            is HostResponse.RecordSuccess -> {
                val record = response.value
                var size = 4 + record.schema.typeName.length
                for (index in record.values.indices) {
                    val field = record.schema.fields[index]
                    size += 1 + field.name.length + encodedSize(record.values[index])
                }
                size
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

            is HostResponse.ByteArraySuccess -> {
                val bytes = response.value
                buffer.putInt(bytes.size)
                buffer.put(bytes)
            }

            is HostResponse.RecordSuccess -> {
                val record = response.value
                buffer.putShort(
                    record.schema.typeName.length
                        .toShort(),
                )
                record.schema.typeName.forEach { buffer.put(it.code.toByte()) }
                buffer.put(record.values.size.toByte())
                for (index in record.values.indices) {
                    val field = record.schema.fields[index]
                    buffer.put(field.name.length.toByte())
                    field.name.forEach { buffer.put(it.code.toByte()) }
                    write(buffer, record.values[index])
                }
            }

            is HostResponse.Failure -> {
                error("failures use the host failure boundary")
            }
        }
    }
}

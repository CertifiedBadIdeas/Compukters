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
        val size =
            when (response) {
                HostResponse.UnitSuccess -> {
                    0
                }

                is HostResponse.IntSuccess, is HostResponse.FloatSuccess -> {
                    4
                }

                is HostResponse.LongSuccess, is HostResponse.DoubleSuccess -> {
                    8
                }

                is HostResponse.BoolSuccess -> {
                    1
                }

                is HostResponse.CharSuccess -> {
                    2
                }

                is HostResponse.StringSuccess -> {
                    require(response.value.length <= MAXIMUM_STRING_UNITS) { "host response string exceeds limit" }
                    2 + 2 * response.value.length
                }

                is HostResponse.Failure -> {
                    error("failures use the host failure boundary")
                }
            }
        val buffer = ByteBuffer.allocate(2 + size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(1)
        when (response) {
            HostResponse.UnitSuccess -> {
                buffer.put(0)
            }

            is HostResponse.IntSuccess -> {
                buffer.put(1)
                buffer.putInt(response.value)
            }

            is HostResponse.LongSuccess -> {
                buffer.put(2)
                buffer.putLong(response.value)
            }

            is HostResponse.FloatSuccess -> {
                buffer.put(3)
                buffer.putInt(response.value.toRawBits())
            }

            is HostResponse.DoubleSuccess -> {
                buffer.put(4)
                buffer.putLong(response.value.toRawBits())
            }

            is HostResponse.BoolSuccess -> {
                buffer.put(5)
                buffer.put(if (response.value) 1.toByte() else 0.toByte())
            }

            is HostResponse.CharSuccess -> {
                buffer.put(6)
                buffer.putChar(response.value)
            }

            is HostResponse.StringSuccess -> {
                buffer.put(7)
                buffer.putShort(response.value.length.toShort())
                response.value.forEach(buffer::putChar)
            }

            is HostResponse.Failure -> {
                error("failures use the host failure boundary")
            }
        }
        return buffer.array()
    }
}

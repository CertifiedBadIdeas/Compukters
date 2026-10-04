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

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class HostResponseWireTest {
    @Test
    fun `response values retain signed long IEEE bits and isolated UTF16 surrogates`() {
        assertContentEquals(byteArrayOf(1, 2, 0, 0, 0, 0, 0, 0, 0, -128), HostResponseWire.encode(HostResponse.LongSuccess(Long.MIN_VALUE)))
        assertContentEquals(byteArrayOf(1, 4, 0, 0, 0, 0, 0, 0, 0, -128), HostResponseWire.encode(HostResponse.DoubleSuccess(-0.0)))
        assertContentEquals(
            byteArrayOf(1, 4, 66, 0, 0, 0, 0, 0, -16, 127),
            HostResponseWire.encode(HostResponse.DoubleSuccess(Double.fromBits(0x7ff0000000000042L))),
        )
        assertContentEquals(byteArrayOf(1, 6, 0, -40), HostResponseWire.encode(HostResponse.CharSuccess('\ud800')))
        assertContentEquals(byteArrayOf(1, 7, 2, 0, 0, -40, 65, 0), HostResponseWire.encode(HostResponse.StringSuccess("\ud800A")))
    }

    @Test
    fun `response string encoding rejects values outside the native boundary`() {
        assertFailsWith<IllegalArgumentException> { HostResponseWire.encode(HostResponse.StringSuccess("a".repeat(4097))) }
    }
}

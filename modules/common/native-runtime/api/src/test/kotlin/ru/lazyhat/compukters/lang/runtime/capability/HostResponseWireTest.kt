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
    fun `byte arrays preserve raw bits and snapshot ownership`() {
        val original = byteArrayOf(0, -128, -1)
        val response = HostResponse.ByteArraySuccess(original)
        original.fill(17)
        response.value.fill(42)
        assertContentEquals(byteArrayOf(1, 9, 3, 0, 0, 0, 0, -128, -1), HostResponseWire.encode(response))
        assertContentEquals(byteArrayOf(1, 9, 0, 0, 0, 0), HostResponseWire.encode(HostResponse.ByteArraySuccess(byteArrayOf())))
        assertFailsWith<IllegalArgumentException> { HostResponse.ByteArraySuccess(ByteArray(4097)) }
        kotlin.test.assertEquals(4102, HostResponseWire.encode(HostResponse.ByteArraySuccess(ByteArray(4096))).size)
    }

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

    @Test
    fun `record encoding copies values and retains nested identities and wide scalar bits`() {
        val vector = HostRecordSchema("fixture.Vector", listOf(HostRecordField("x", HostValueType.F64)))
        val fields = mutableListOf(HostRecordField("tick", HostValueType.I64), HostRecordField("position", HostValueType.RECORD, vector))
        val schema = HostRecordSchema("fixture.Snapshot", fields)
        fields.clear()
        val values =
            mutableListOf<HostResponse>(
                HostResponse.LongSuccess(Long.MIN_VALUE),
                HostResponse.RecordSuccess(HostRecordValue(vector, listOf(HostResponse.DoubleSuccess(-0.0)))),
            )
        val response = HostResponse.RecordSuccess(HostRecordValue(schema, values))
        values.clear()
        val encoded =
            java.nio.ByteBuffer
                .wrap(HostResponseWire.encode(response))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        kotlin.test.assertEquals(1, encoded.get().toInt())
        kotlin.test.assertEquals(8, encoded.get().toInt())

        fun text(length: Int): String = ByteArray(length).also { encoded.get(it) }.decodeToString()
        kotlin.test.assertEquals("fixture.Snapshot", text(encoded.short.toInt()))
        kotlin.test.assertEquals(2, encoded.get().toInt())
        kotlin.test.assertEquals("tick", text(encoded.get().toInt()))
        kotlin.test.assertEquals(2, encoded.get().toInt())
        kotlin.test.assertEquals(Long.MIN_VALUE, encoded.long)
        kotlin.test.assertEquals("position", text(encoded.get().toInt()))
        kotlin.test.assertEquals(8, encoded.get().toInt())
        kotlin.test.assertEquals("fixture.Vector", text(encoded.short.toInt()))
        kotlin.test.assertEquals(1, encoded.get().toInt())
        kotlin.test.assertEquals("x", text(encoded.get().toInt()))
        kotlin.test.assertEquals(4, encoded.get().toInt())
        kotlin.test.assertEquals(Long.MIN_VALUE, encoded.long)
        kotlin.test.assertEquals(0, encoded.remaining())
        assertFailsWith<UnsupportedOperationException> { (response.value.values as MutableList).clear() }
    }

    @Test
    fun `record values reject mismatched types nested identities and aggregate string overflow`() {
        val vector = HostRecordSchema("fixture.Vector", listOf(HostRecordField("x", HostValueType.F64)))
        assertFailsWith<IllegalArgumentException> { HostRecordValue(vector, listOf(HostResponse.LongSuccess(1))) }
        val nested = HostRecordSchema("fixture.Snapshot", listOf(HostRecordField("position", HostValueType.RECORD, vector)))
        val other = HostRecordSchema("fixture.Other", vector.fields)
        assertFailsWith<IllegalArgumentException> {
            HostRecordValue(nested, listOf(HostResponse.RecordSuccess(HostRecordValue(other, listOf(HostResponse.DoubleSuccess(1.0))))))
        }
        val strings =
            HostRecordSchema(
                "fixture.Strings",
                listOf(HostRecordField("a", HostValueType.STRING), HostRecordField("b", HostValueType.STRING)),
            )
        assertFailsWith<IllegalArgumentException> {
            HostRecordValue(strings, listOf(HostResponse.StringSuccess("a".repeat(2048)), HostResponse.StringSuccess("b".repeat(2049))))
        }
    }
}

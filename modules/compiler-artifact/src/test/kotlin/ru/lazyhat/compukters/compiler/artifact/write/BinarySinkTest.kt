/*
 * The Compukters Developers
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

package ru.lazyhat.compukters.compiler.artifact.write

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinarySinkTest {
    @Test
    fun `fixed integers are little endian`() {
        val sink = BinarySink(32)
        sink.writeU16(0x1234u)
        sink.writeU32(0x89ab_cdefu)
        sink.writeU64(0x0102_0304_0506_0708u)

        assertContentEquals(
            byteArrayOf(
                0x34,
                0x12,
                0xef.toByte(),
                0xcd.toByte(),
                0xab.toByte(),
                0x89.toByte(),
                0x08,
                0x07,
                0x06,
                0x05,
                0x04,
                0x03,
                0x02,
                0x01,
            ),
            sink.toByteArray(),
        )
    }

    @Test
    fun `ULEB128 is shortest for every u32 boundary`() {
        val sink = BinarySink(32)
        listOf(0u, 127u, 128u, 0x7fff_ffffu, UInt.MAX_VALUE).forEach(sink::writeUleb128)

        assertContentEquals(
            byteArrayOf(
                0x00,
                0x7f,
                0x80.toByte(),
                0x01,
                0xff.toByte(),
                0xff.toByte(),
                0xff.toByte(),
                0xff.toByte(),
                0x07,
                0xff.toByte(),
                0xff.toByte(),
                0xff.toByte(),
                0xff.toByte(),
                0x0f,
            ),
            sink.toByteArray(),
        )
    }

    @Test
    fun `indexed envelope is aligned and contains canonical offsets`() {
        val encoded = encodeIndexed(listOf(byteArrayOf(1, 2), byteArrayOf(3)), 64)

        assertEquals(35, encoded.size)
        assertContentEquals(byteArrayOf(2, 0, 0, 0), encoded.copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0), encoded.copyOfRange(16, 28))
        assertContentEquals(byteArrayOf(1, 2, 3), encoded.copyOfRange(32, 35))
        assertContentEquals(ByteArray(24), encodeIndexed(emptyList(), 24))
    }

    @Test
    fun `sink rejects growth before exceeding its bound`() {
        val sink = BinarySink(3)
        sink.writeU16(1u)

        val failure = assertFailsWith<ArtifactEncodingException> { sink.writeU16(2u) }
        assertEquals(ArtifactWriteErrorCode.LIMIT_EXCEEDED, failure.code)
        assertContentEquals(byteArrayOf(1, 0), sink.toByteArray())
    }
}

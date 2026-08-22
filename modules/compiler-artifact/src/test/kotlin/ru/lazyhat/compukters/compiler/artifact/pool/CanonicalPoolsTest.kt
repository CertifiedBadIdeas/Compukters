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

package ru.lazyhat.compukters.compiler.artifact.pool

import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Utf16Literal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalPoolsTest {
    @Test
    fun `metadata freeze is independent of insertion order and deduplicates`() {
        val firstBuilder = MetadataPoolBuilder()
        val entry = firstBuilder.intern(MetadataText.of("entry"))
        val app = firstBuilder.intern(MetadataText.of("app"))
        val duplicateEntry = firstBuilder.intern(MetadataText.of("entry"))

        val secondBuilder = MetadataPoolBuilder()
        secondBuilder.intern(MetadataText.of("app"))
        secondBuilder.intern(MetadataText.of("entry"))

        val first = firstBuilder.freeze()
        val second = secondBuilder.freeze()

        assertEquals(listOf("app", "entry"), first.records.map { it.toString() })
        assertEquals(first.records, second.records)
        assertEquals(1u, first.idOf(entry).value)
        assertEquals(0u, first.idOf(app).value)
        assertEquals(first.idOf(entry), first.idOf(duplicateEntry))
    }

    @Test
    fun `UTF-16 freeze sorts exact little-endian bytes`() {
        val builder = Utf16LiteralPoolBuilder()
        val one = builder.intern(Utf16Literal.of(0x0001))
        val byteFirst = builder.intern(Utf16Literal.of(0x0100))
        val empty = builder.intern(Utf16Literal.of())
        val surrogate = builder.intern(Utf16Literal.of(0xd800, 0x0000))
        val duplicate = builder.intern(Utf16Literal.of(0x0001))

        val frozen = builder.freeze()

        assertEquals(4, frozen.records.size)
        assertEquals(0u, frozen.idOf(empty).value)
        assertEquals(1u, frozen.idOf(byteFirst).value)
        assertEquals(2u, frozen.idOf(surrogate).value)
        assertEquals(3u, frozen.idOf(one).value)
        assertEquals(frozen.idOf(one), frozen.idOf(duplicate))
        assertContentEquals(
            byteArrayOf(0x00, 0xd8.toByte(), 0x00, 0x00),
            frozen.records[frozen.idOf(surrogate).value.toInt()].toLittleEndianByteArray(),
        )
    }

    @Test
    fun `key cannot be resolved by another builder`() {
        val first = MetadataPoolBuilder()
        val key = first.intern(MetadataText.of("app"))
        val second = MetadataPoolBuilder()
        second.intern(MetadataText.of("app"))

        assertFailsWith<IllegalArgumentException> { second.freeze().idOf(key) }
    }
}

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

package ru.lazyhat.compukters.compiler.artifact.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextTest {
    @Test
    fun `metadata rejects an isolated surrogate instead of replacing it`() {
        assertFailsWith<IllegalArgumentException> { MetadataText.of("\ud800") }
    }

    @Test
    fun `metadata exposes exact strict UTF-8 bytes`() {
        assertContentEquals(
            byteArrayOf(0x61, 0xc3.toByte(), 0xa9.toByte()),
            MetadataText.of("aé").toByteArray(),
        )
    }

    @Test
    fun `literal preserves every UTF-16 code unit pattern`() {
        val literal = Utf16Literal.of(0xd800, 0x0000, 0xdc00)

        assertEquals(3, literal.size)
        assertContentEquals(
            byteArrayOf(0x00, 0xd8.toByte(), 0x00, 0x00, 0x00, 0xdc.toByte()),
            literal.toLittleEndianByteArray(),
        )
    }

    @Test
    fun `literal copies Kotlin String code units without charset conversion`() {
        val source = "\ud800x"
        assertContentEquals(
            byteArrayOf(0x00, 0xd8.toByte(), 0x78, 0x00),
            Utf16Literal.fromString(source).toLittleEndianByteArray(),
        )
    }
}

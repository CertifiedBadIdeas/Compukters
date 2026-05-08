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

package ru.lazyhat.compukterkraft.lang.runtime.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CkVmImageAbiTest {
    @Test
    fun encodedImageStartsWithMagicAndVersion() {
        val bytes = CkVmImageAbi.encode(minimalImage())

        assertContentEquals(
            byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        assertEquals(1, bytes[4].toInt())
    }

    @Test
    fun encodedImageIsDeterministic() {
        val image = representativeImage()

        assertContentEquals(CkVmImageAbi.encode(image), CkVmImageAbi.encode(image))
    }

    @Test
    fun encodedImageContainsSkeletonSections() {
        val bytes = CkVmImageAbi.encode(representativeImage())
        val reader = TestReader(bytes)

        assertEquals("CKIM", reader.ascii(4))
        assertEquals(1, reader.u8())
        assertEquals("ckl-1", reader.string())
        assertEquals(1, reader.u16())
        assertEquals(listOf("host-import-ids"), reader.stringList())
        assertEquals(3, reader.i32())
        assertEquals(1, reader.u8())
        assertEquals("hello", reader.string())
        assertEquals(2, reader.u8())
        assertEquals(7, reader.i32())
        assertEquals(3, reader.u8())
        assertEquals(9L, reader.i64())
        assertEquals(1, reader.i32())
        assertEquals(42, reader.i32())
        assertEquals("display", reader.string())
        assertEquals("present", reader.string())
        assertEquals(listOf("Int"), reader.stringList())
        assertEquals("Unit", reader.string())
        assertEquals(0, reader.i32())
        assertEquals(1, reader.i32())
        assertEquals("main", reader.string())
        assertEquals(8, reader.i32())
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), reader.byteArray())
        assertEquals(bytes.size, reader.offset)
    }

    @Test
    fun negativeImportIdIsRejectedBeforeEncoding() {
        val image = minimalImage().copy(hostImports = listOf(CkVmHostImport(-1, "display", "present", listOf("Int"), "Unit")))

        assertFailsWith<IllegalArgumentException> {
            CkVmImageAbi.encode(image)
        }
    }

    @Test
    fun writesGoldenFixtureWhenPathIsProvided() {
        val path = System.getProperty("ckl.image.golden.path")?.takeIf(String::isNotBlank) ?: return

        java.nio.file.Files
            .createDirectories(
                java.nio.file.Path
                    .of(path)
                    .parent,
            )
        java.nio.file.Files
            .write(
                java.nio.file.Path
                    .of(path),
                CkVmImageAbi.encode(representativeImage()),
            )
    }

    private fun minimalImage(): CkVmImage =
        CkVmImage(
            languageVersion = "ckl-1",
            targetAbiVersion = 1,
            entryFunctionIndex = 0,
            functions = listOf(CkVmFunction("main", frameSize = 0, code = emptyList())),
        )

    private fun representativeImage(): CkVmImage =
        CkVmImage(
            languageVersion = "ckl-1",
            targetAbiVersion = 1,
            capabilities = listOf("host-import-ids"),
            constants =
                listOf(
                    CkVmConstant.StringConstant("hello"),
                    CkVmConstant.IntConstant(7),
                    CkVmConstant.LongConstant(9L),
                ),
            hostImports = listOf(CkVmHostImport(42, "display", "present", listOf("Int"), "Unit")),
            entryFunctionIndex = 0,
            functions = listOf(CkVmFunction("main", frameSize = 8, code = listOf(0x01, 0x02, 0x03))),
        )

    private class TestReader(
        private val bytes: ByteArray,
    ) {
        var offset: Int = 0
            private set

        fun ascii(count: Int): String = bytes.decodeToString(offset, offset + count).also { offset += count }

        fun u8(): Int = bytes[offset++].toInt() and 0xff

        fun u16(): Int = u8() or (u8() shl 8)

        fun i32(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)

        fun i64(): Long {
            var value = 0L
            repeat(8) { index -> value = value or ((u8().toLong() and 0xffL) shl (index * 8)) }
            return value
        }

        fun string(): String {
            val length = i32()
            val value = bytes.decodeToString(offset, offset + length)
            offset += length
            return value
        }

        fun stringList(): List<String> = List(i32()) { string() }

        fun byteArray(): ByteArray {
            val length = i32()
            val value = bytes.copyOfRange(offset, offset + length)
            offset += length
            return value
        }
    }
}

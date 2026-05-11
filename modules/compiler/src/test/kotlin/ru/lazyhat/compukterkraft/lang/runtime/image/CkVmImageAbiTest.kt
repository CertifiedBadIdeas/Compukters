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
    fun encodedImageStartsWithMagicAndRegisterAbiVersion() {
        val bytes = CkVmImageAbi.encode(minimalImage())

        assertContentEquals(
            byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte()),
            bytes.copyOfRange(0, 4),
        )
        assertEquals(3, bytes[4].toInt())
    }

    @Test
    fun encodedImageIsDeterministic() {
        val image = representativeImage()

        assertContentEquals(CkVmImageAbi.encode(image), CkVmImageAbi.encode(image))
    }

    @Test
    fun encodedImageContainsTypedRegisterSections() {
        val bytes = CkVmImageAbi.encode(representativeImage())
        val reader = TestReader(bytes)

        assertEquals("CKIM", reader.ascii(4))
        assertEquals(3, reader.u8())
        assertEquals("ckl-1", reader.string())
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
        assertEquals(3, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(1, reader.u16())
        assertEquals(listOf(CkVmTypedRegister.I32(0)), reader.typedRegisterList())
        assertEquals(4, reader.i32())
        assertEquals(CkVmImageAbi.InstructionTags.REF_CONST, reader.u8())
        assertEquals(0, reader.u16())
        assertEquals(0, reader.i32())
        assertEquals(CkVmImageAbi.InstructionTags.I32_ADD, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(1, reader.u16())
        assertEquals(CkVmImageAbi.InstructionTags.CALL_HOST, reader.u8())
        assertEquals(1, reader.u8())
        assertEquals(CkVmTypedRegister.Ref(0), reader.typedRegister())
        assertEquals(42, reader.i32())
        assertEquals(listOf(CkVmTypedRegister.I32(2)), reader.typedRegisterList())
        assertEquals(CkVmImageAbi.InstructionTags.RETURN_UNIT, reader.u8())
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
    fun outOfRangeRegisterIsRejectedBeforeEncoding() {
        val image =
            minimalImage().copy(
                functions =
                    listOf(
                        CkVmFunction(
                            name = "main",
                            i32RegisterCount = 1,
                            i64RegisterCount = 0,
                            boolRegisterCount = 0,
                            refRegisterCount = 0,
                            parameters = emptyList(),
                            instructions = listOf(CkVmInstruction.I32Const(1, 0)),
                        ),
                    ),
                constants = listOf(CkVmConstant.IntConstant(0)),
            )

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
            entryFunctionIndex = 0,
            functions =
                listOf(
                    CkVmFunction(
                        name = "main",
                        i32RegisterCount = 0,
                        i64RegisterCount = 0,
                        boolRegisterCount = 0,
                        refRegisterCount = 0,
                        parameters = emptyList(),
                        instructions = emptyList(),
                    ),
                ),
        )

    private fun representativeImage(): CkVmImage =
        CkVmImage(
            languageVersion = "ckl-1",
            constants =
                listOf(
                    CkVmConstant.StringConstant("hello"),
                    CkVmConstant.IntConstant(7),
                    CkVmConstant.LongConstant(9L),
                ),
            hostImports = listOf(CkVmHostImport(42, "display", "present", listOf("Int"), "Unit")),
            entryFunctionIndex = 0,
            functions =
                listOf(
                    CkVmFunction(
                        name = "main",
                        i32RegisterCount = 3,
                        i64RegisterCount = 0,
                        boolRegisterCount = 0,
                        refRegisterCount = 1,
                        parameters = listOf(CkVmTypedRegister.I32(0)),
                        instructions =
                            listOf(
                                CkVmInstruction.RefConst(0, 0),
                                CkVmInstruction.I32Add(2, 0, 1),
                                CkVmInstruction.CallHost(CkVmTypedRegister.Ref(0), 42, listOf(CkVmTypedRegister.I32(2))),
                                CkVmInstruction.ReturnUnit,
                            ),
                    ),
                ),
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

        fun typedRegister(): CkVmTypedRegister =
            when (val tag = u8()) {
                CkVmImageAbi.RegisterTags.I32 -> CkVmTypedRegister.I32(u16())
                CkVmImageAbi.RegisterTags.I64 -> CkVmTypedRegister.I64(u16())
                CkVmImageAbi.RegisterTags.BOOL -> CkVmTypedRegister.Bool(u16())
                CkVmImageAbi.RegisterTags.REF -> CkVmTypedRegister.Ref(u16())
                else -> error("Unexpected register tag $tag")
            }

        fun typedRegisterList(): List<CkVmTypedRegister> = List(i32()) { typedRegister() }
    }
}

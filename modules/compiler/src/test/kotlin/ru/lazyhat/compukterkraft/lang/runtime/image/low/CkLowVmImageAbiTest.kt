package ru.lazyhat.compukterkraft.lang.runtime.image.low

import kotlin.test.Test
import kotlin.test.assertEquals

class CkLowVmImageAbiTest {
    @Test
    fun lowImageModelDescribesLinearMemoryAndUnifiedPrimitiveRegisters() {
        val image = representativeImage()

        assertEquals(4096u, image.memorySize)
        assertEquals(3, image.functions.single().registerCount)
        assertEquals(
            1,
            (image.functions.single().instructions.last() as CkLowVmInstruction.ReturnI32).src,
        )
    }

    @Test
    fun lowImageAbiEncodesVersionFiveAndUnifiedRegisters() {
        val bytes = CkLowVmImageAbi.encode(representativeImage())
        val reader = LowTestReader(bytes)

        assertEquals("CKIM", reader.ascii(4))
        assertEquals(5, reader.u8())
        assertEquals("ckl-low-1", reader.string())
        assertEquals(4096u, reader.u32())
        assertEquals(listOf(1, 2, 3), reader.bytes().map { it.toInt() })
        assertEquals(listOf(4, 5), reader.bytes().map { it.toInt() })
        assertEquals(16u, reader.u32())
        assertEquals(0, reader.i32())
        assertEquals(1, reader.i32())
        assertEquals("main", reader.string())
        assertEquals(3, reader.u16())
        assertEquals(emptyList(), reader.registerList())
        assertEquals(5, reader.i32())
        assertEquals(CkLowVmImageAbi.InstructionTags.ADDR_CONST, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(128u, reader.u32())
        assertEquals(CkLowVmImageAbi.InstructionTags.I32_CONST, reader.u8())
        assertEquals(0, reader.u16())
        assertEquals(7, reader.i32())
        assertEquals(CkLowVmImageAbi.InstructionTags.STORE32, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(CkLowVmImageAbi.InstructionTags.LOAD32, reader.u8())
        assertEquals(1, reader.u16())
        assertEquals(2, reader.u16())
        assertEquals(CkLowVmImageAbi.InstructionTags.RETURN_I32, reader.u8())
        assertEquals(1, reader.u16())
        assertEquals(bytes.size, reader.offset)
    }

    @Test
    fun lowImageAbiEncodesI32EqualityInstruction() {
        val bytes =
            CkLowVmImageAbi.encode(
                CkLowVmImage(
                    languageVersion = "ckl-low-1",
                    memorySize = 1024u,
                    entryFunctionIndex = 0,
                    functions =
                        listOf(
                            CkLowVmFunction(
                                name = "main",
                                registerCount = 3,
                                parameters = emptyList(),
                                instructions =
                                    listOf(
                                        CkLowVmInstruction.I32Eq(dst = 2, lhs = 0, rhs = 1),
                                        CkLowVmInstruction.ReturnBool(src = 2),
                                    ),
                            ),
                        ),
                ),
            )
        val reader = LowTestReader(bytes)

        assertEquals("CKIM", reader.ascii(4))
        assertEquals(CkLowVmImageAbi.VERSION, reader.u8())
        assertEquals("ckl-low-1", reader.string())
        assertEquals(1024u, reader.u32())
        assertEquals(emptyList(), reader.bytes().toList())
        assertEquals(emptyList(), reader.bytes().toList())
        assertEquals(0u, reader.u32())
        assertEquals(0, reader.i32())
        assertEquals(1, reader.i32())
        assertEquals("main", reader.string())
        assertEquals(3, reader.u16())
        assertEquals(emptyList(), reader.registerList())
        assertEquals(2, reader.i32())
        assertEquals(CkLowVmImageAbi.InstructionTags.I32_EQ, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(1, reader.u16())
        assertEquals(CkLowVmImageAbi.InstructionTags.RETURN_BOOL, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(bytes.size, reader.offset)
    }

    @Test
    fun lowImageAbiEncodesI32ComparisonFamilyInstructions() {
        val bytes =
            CkLowVmImageAbi.encode(
                CkLowVmImage(
                    languageVersion = "ckl-low-1",
                    memorySize = 1024u,
                    entryFunctionIndex = 0,
                    functions =
                        listOf(
                            CkLowVmFunction(
                                name = "main",
                                registerCount = 3,
                                parameters = emptyList(),
                                instructions =
                                    listOf(
                                        CkLowVmInstruction.I32Ne(dst = 2, lhs = 0, rhs = 1),
                                        CkLowVmInstruction.I32Le(dst = 2, lhs = 0, rhs = 1),
                                        CkLowVmInstruction.I32Gt(dst = 2, lhs = 0, rhs = 1),
                                        CkLowVmInstruction.I32Ge(dst = 2, lhs = 0, rhs = 1),
                                        CkLowVmInstruction.ReturnBool(src = 2),
                                    ),
                            ),
                        ),
                ),
            )
        val reader = LowTestReader(bytes)

        assertEquals("CKIM", reader.ascii(4))
        assertEquals(CkLowVmImageAbi.VERSION, reader.u8())
        assertEquals("ckl-low-1", reader.string())
        assertEquals(1024u, reader.u32())
        assertEquals(emptyList(), reader.bytes().toList())
        assertEquals(emptyList(), reader.bytes().toList())
        assertEquals(0u, reader.u32())
        assertEquals(0, reader.i32())
        assertEquals(1, reader.i32())
        assertEquals("main", reader.string())
        assertEquals(3, reader.u16())
        assertEquals(emptyList(), reader.registerList())
        assertEquals(5, reader.i32())
        assertComparisonInstruction(reader, CkLowVmImageAbi.InstructionTags.I32_NE)
        assertComparisonInstruction(reader, CkLowVmImageAbi.InstructionTags.I32_LE)
        assertComparisonInstruction(reader, CkLowVmImageAbi.InstructionTags.I32_GT)
        assertComparisonInstruction(reader, CkLowVmImageAbi.InstructionTags.I32_GE)
        assertEquals(CkLowVmImageAbi.InstructionTags.RETURN_BOOL, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(bytes.size, reader.offset)
    }

    @Test
    fun writesLowGoldenFixtureWhenPathIsProvided() {
        val path = System.getProperty("ckl.low.image.golden.path")?.takeIf(String::isNotBlank) ?: return

        java.nio.file.Files.createDirectories(java.nio.file.Path.of(path).parent)
        java.nio.file.Files.write(java.nio.file.Path.of(path), CkLowVmImageAbi.encode(representativeImage()))
    }

    private fun assertComparisonInstruction(
        reader: LowTestReader,
        expectedTag: Int,
    ) {
        assertEquals(expectedTag, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(1, reader.u16())
    }

    private fun representativeImage(): CkLowVmImage =
        CkLowVmImage(
            languageVersion = "ckl-low-1",
            memorySize = 4096u,
            rodata = byteArrayOf(1, 2, 3),
            data = byteArrayOf(4, 5),
            bssSize = 16u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    CkLowVmFunction(
                        name = "main",
                        registerCount = 3,
                        parameters = emptyList(),
                        instructions =
                            listOf(
                                CkLowVmInstruction.AddrConst(2, 128u),
                                CkLowVmInstruction.I32Const(0, 7),
                                CkLowVmInstruction.Store32(2, 0),
                                CkLowVmInstruction.Load32(1, 2),
                                CkLowVmInstruction.ReturnI32(1),
                            ),
                    ),
                ),
        )

    private class LowTestReader(
        private val bytes: ByteArray,
    ) {
        var offset: Int = 0
            private set

        fun ascii(count: Int): String = bytes.decodeToString(offset, offset + count).also { offset += count }

        fun u8(): Int = bytes[offset++].toInt() and 0xff

        fun u16(): Int = u8() or (u8() shl 8)

        fun i32(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)

        fun u32(): UInt = i32().toUInt()

        fun string(): String {
            val length = i32()
            val value = bytes.decodeToString(offset, offset + length)
            offset += length
            return value
        }

        fun bytes(): ByteArray {
            val length = i32()
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun registerList(): List<Int> = List(i32()) { u16() }
    }
}

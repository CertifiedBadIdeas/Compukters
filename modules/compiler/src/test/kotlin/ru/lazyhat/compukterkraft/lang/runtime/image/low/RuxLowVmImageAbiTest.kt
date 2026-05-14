package ru.lazyhat.compukterkraft.lang.runtime.image.low

import kotlin.test.Test
import kotlin.test.assertEquals

class RuxLowVmImageAbiTest {
    @Test
    fun lowImageModelDescribesLinearMemoryAndUnifiedPrimitiveRegisters() {
        val image = representativeImage()

        assertEquals(4096u, image.memorySize)
        assertEquals(3, image.functions.single().registerCount)
        assertEquals(
            1,
            (image.functions.single().instructions.last() as RuxLowVmInstruction.ReturnI32).src,
        )
    }

    @Test
    fun lowImageAbiEncodesVersionFiveAndUnifiedRegisters() {
        val bytes = RuxLowVmImageAbi.encode(representativeImage())
        val reader = LowTestReader(bytes)

        assertEquals("CKIM", reader.ascii(4))
        assertEquals(5, reader.u8())
        assertEquals("rux-low-1", reader.string())
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
        assertEquals(RuxLowVmImageAbi.InstructionTags.ADDR_CONST, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(128u, reader.u32())
        assertEquals(RuxLowVmImageAbi.InstructionTags.I32_CONST, reader.u8())
        assertEquals(0, reader.u16())
        assertEquals(7, reader.i32())
        assertEquals(RuxLowVmImageAbi.InstructionTags.STORE32, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(RuxLowVmImageAbi.InstructionTags.LOAD32, reader.u8())
        assertEquals(1, reader.u16())
        assertEquals(2, reader.u16())
        assertEquals(RuxLowVmImageAbi.InstructionTags.RETURN_I32, reader.u8())
        assertEquals(1, reader.u16())
        assertEquals(bytes.size, reader.offset)
    }

    @Test
    fun lowImageAbiEncodesI32EqualityInstruction() {
        val bytes =
            RuxLowVmImageAbi.encode(
                RuxLowVmImage(
                    languageVersion = "rux-low-1",
                    memorySize = 1024u,
                    entryFunctionIndex = 0,
                    functions =
                        listOf(
                            RuxLowVmFunction(
                                name = "main",
                                registerCount = 3,
                                parameters = emptyList(),
                                instructions =
                                    listOf(
                                        RuxLowVmInstruction.I32Eq(dst = 2, lhs = 0, rhs = 1),
                                        RuxLowVmInstruction.ReturnBool(src = 2),
                                    ),
                            ),
                        ),
                ),
            )
        val reader = LowTestReader(bytes)

        assertEquals("CKIM", reader.ascii(4))
        assertEquals(RuxLowVmImageAbi.VERSION, reader.u8())
        assertEquals("rux-low-1", reader.string())
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
        assertEquals(RuxLowVmImageAbi.InstructionTags.I32_EQ, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(1, reader.u16())
        assertEquals(RuxLowVmImageAbi.InstructionTags.RETURN_BOOL, reader.u8())
        assertEquals(2, reader.u16())
        assertEquals(bytes.size, reader.offset)
    }

    @Test
    fun writesLowGoldenFixtureWhenPathIsProvided() {
        val path = System.getProperty("ckl.low.image.golden.path")?.takeIf(String::isNotBlank) ?: return

        java.nio.file.Files.createDirectories(java.nio.file.Path.of(path).parent)
        java.nio.file.Files.write(java.nio.file.Path.of(path), RuxLowVmImageAbi.encode(representativeImage()))
    }

    private fun representativeImage(): RuxLowVmImage =
        RuxLowVmImage(
            languageVersion = "rux-low-1",
            memorySize = 4096u,
            rodata = byteArrayOf(1, 2, 3),
            data = byteArrayOf(4, 5),
            bssSize = 16u,
            entryFunctionIndex = 0,
            functions =
                listOf(
                    RuxLowVmFunction(
                        name = "main",
                        registerCount = 3,
                        parameters = emptyList(),
                        instructions =
                            listOf(
                                RuxLowVmInstruction.AddrConst(2, 128u),
                                RuxLowVmInstruction.I32Const(0, 7),
                                RuxLowVmInstruction.Store32(2, 0),
                                RuxLowVmInstruction.Load32(1, 2),
                                RuxLowVmInstruction.ReturnI32(1),
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

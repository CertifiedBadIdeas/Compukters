package ru.lazyhat.compukterkraft.lang.runtime.image.low

import kotlin.test.Test
import kotlin.test.assertEquals

class CkLowVmImageAbiTest {
    @Test
    fun lowImageModelDescribesLinearMemoryAndPrimitiveRegisters() {
        val image =
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
                            i32RegisterCount = 2,
                            i64RegisterCount = 0,
                            addrRegisterCount = 1,
                            boolRegisterCount = 0,
                            parameters = emptyList(),
                            instructions =
                                listOf(
                                    CkLowVmInstruction.AddrConst(dst = 0, value = 128u),
                                    CkLowVmInstruction.I32Const(dst = 0, value = 7),
                                    CkLowVmInstruction.Store32(addr = 0, src = 0),
                                    CkLowVmInstruction.Load32(dst = 1, addr = 0),
                                    CkLowVmInstruction.Return(CkLowVmRegister.I32(1)),
                                ),
                        ),
                    ),
            )

        assertEquals(4096u, image.memorySize)
        assertEquals(1, image.functions.single().addrRegisterCount)
        assertEquals(
            CkLowVmRegister.I32(1),
            (image.functions.single().instructions.last() as CkLowVmInstruction.Return).src,
        )
    }

    @Test
    fun lowImageAbiEncodesVersionFourAndLinearMemoryLayout() {
        val bytes =
            CkLowVmImageAbi.encode(
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
                                i32RegisterCount = 2,
                                i64RegisterCount = 0,
                                addrRegisterCount = 1,
                                boolRegisterCount = 0,
                                parameters = emptyList(),
                                instructions =
                                    listOf(
                                        CkLowVmInstruction.AddrConst(0, 128u),
                                        CkLowVmInstruction.I32Const(0, 7),
                                        CkLowVmInstruction.Store32(0, 0),
                                        CkLowVmInstruction.Load32(1, 0),
                                        CkLowVmInstruction.Return(CkLowVmRegister.I32(1)),
                                    ),
                            ),
                        ),
                ),
            )
        val reader = LowTestReader(bytes)

        assertEquals("CKIM", reader.ascii(4))
        assertEquals(4, reader.u8())
        assertEquals("ckl-low-1", reader.string())
        assertEquals(4096u, reader.u32())
        assertEquals(listOf(1, 2, 3), reader.bytes().map { it.toInt() })
        assertEquals(listOf(4, 5), reader.bytes().map { it.toInt() })
        assertEquals(16u, reader.u32())
        assertEquals(0, reader.i32())
        assertEquals(1, reader.i32())
        assertEquals("main", reader.string())
        assertEquals(2, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(1, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(emptyList(), reader.registerList())
        assertEquals(5, reader.i32())
        assertEquals(CkLowVmImageAbi.InstructionTags.ADDR_CONST, reader.u8())
        assertEquals(0, reader.u16())
        assertEquals(128u, reader.u32())
        assertEquals(CkLowVmImageAbi.InstructionTags.I32_CONST, reader.u8())
        assertEquals(0, reader.u16())
        assertEquals(7, reader.i32())
        assertEquals(CkLowVmImageAbi.InstructionTags.STORE32, reader.u8())
        assertEquals(0, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(CkLowVmImageAbi.InstructionTags.LOAD32, reader.u8())
        assertEquals(1, reader.u16())
        assertEquals(0, reader.u16())
        assertEquals(CkLowVmImageAbi.InstructionTags.RETURN, reader.u8())
        assertEquals(CkLowVmRegister.I32(1), reader.register())
        assertEquals(bytes.size, reader.offset)
    }

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

        fun register(): CkLowVmRegister =
            when (val tag = u8()) {
                CkLowVmImageAbi.RegisterTags.I32 -> CkLowVmRegister.I32(u16())
                CkLowVmImageAbi.RegisterTags.I64 -> CkLowVmRegister.I64(u16())
                CkLowVmImageAbi.RegisterTags.ADDR -> CkLowVmRegister.Addr(u16())
                CkLowVmImageAbi.RegisterTags.BOOL -> CkLowVmRegister.Bool(u16())
                else -> error("Unexpected register tag $tag")
            }

        fun registerList(): List<CkLowVmRegister> = List(i32()) { register() }
    }
}

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
}

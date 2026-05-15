package ru.lazyhat.compukterkraft.lang.runtime.image.low

object RuxLowVmImageAbi {
    const val VERSION: Int = 1

    object InstructionTags {
        const val I32_CONST = 1
        const val I64_CONST = 2
        const val ADDR_CONST = 3
        const val I32_MOVE = 4
        const val ADDR_MOVE = 5
        const val I32_ADD = 6
        const val I32_SUB = 7
        const val I32_MUL = 8
        const val I32_DIV = 9
        const val I32_BIT_XOR = 10
        const val I32_SHL = 11
        const val I32_SHR = 12
        const val I32_LT = 13
        const val LOAD32 = 14
        const val STORE32 = 15
        const val ADDR_ADD = 16
        const val JUMP = 17
        const val JUMP_IF_FALSE = 18
        const val CALL_STATIC = 19
        const val RETURN_I32 = 20
        const val RETURN_UNIT = 21
        const val RETURN_I64 = 22
        const val RETURN_ADDR = 23
        const val RETURN_BOOL = 24
        const val I32_EQ = 25
        const val I32_BIT_AND = 26
        const val I32_BIT_OR = 27
        const val U32_LT = 28
        const val U32_SHL = 29
        const val U32_SHR = 30
        const val LOAD8 = 31
        const val STORE8 = 32
        const val I32_REM = 33
        const val U32_DIV = 34
        const val U32_REM = 35
    }

    fun encode(image: RuxLowVmImage): ByteArray {
        require(image.memorySize > 0u) { "low VM memorySize must be positive" }
        require(image.rodata.size.toUInt() + image.data.size.toUInt() + image.bssSize <= image.memorySize) {
            "low VM memory sections must fit inside memorySize"
        }
        require(image.entryFunctionIndex in image.functions.indices) {
            "entryFunctionIndex ${image.entryFunctionIndex} is outside function table"
        }
        val writer = Writer()
        writer.ascii("RUXI")
        writer.u8(VERSION)
        writer.u32(image.memorySize)
        writer.bytes(image.rodata)
        writer.bytes(image.data)
        writer.u32(image.bssSize)
        writer.i32(image.entryFunctionIndex)
        writer.list(image.functions) { function(it) }
        return writer.toByteArray()
    }

    private fun Writer.function(function: RuxLowVmFunction) {
        require(function.registerCount in 0..UShort.MAX_VALUE.toInt())
        string(function.name)
        u16(function.registerCount)
        list(function.parameters) { u16(it) }
        list(function.instructions) { instruction(it) }
    }

    private fun Writer.optionalRegister(register: Int?) {
        if (register == null) {
            u8(0)
        } else {
            u8(1)
            u16(register)
        }
    }

    private fun Writer.instruction(instruction: RuxLowVmInstruction) {
        when (instruction) {
            is RuxLowVmInstruction.I32Const -> {
                u8(InstructionTags.I32_CONST)
                u16(instruction.dst)
                i32(instruction.value)
            }
            is RuxLowVmInstruction.I64Const -> {
                u8(InstructionTags.I64_CONST)
                u16(instruction.dst)
                i64(instruction.value)
            }
            is RuxLowVmInstruction.AddrConst -> {
                u8(InstructionTags.ADDR_CONST)
                u16(instruction.dst)
                u32(instruction.value)
            }
            is RuxLowVmInstruction.I32Move -> typedMove(InstructionTags.I32_MOVE, instruction.dst, instruction.src)
            is RuxLowVmInstruction.AddrMove -> typedMove(InstructionTags.ADDR_MOVE, instruction.dst, instruction.src)
            is RuxLowVmInstruction.I32Add -> typedBinary(InstructionTags.I32_ADD, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.I32Sub -> typedBinary(InstructionTags.I32_SUB, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.I32Mul -> typedBinary(InstructionTags.I32_MUL, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.I32Div -> typedBinary(InstructionTags.I32_DIV, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.I32Rem -> typedBinary(InstructionTags.I32_REM, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.U32Div -> typedBinary(InstructionTags.U32_DIV, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.U32Rem -> typedBinary(InstructionTags.U32_REM, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.I32BitXor -> typedBinary(InstructionTags.I32_BIT_XOR, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.I32Shl -> typedBinary(InstructionTags.I32_SHL, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.I32Shr -> typedBinary(InstructionTags.I32_SHR, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.I32Lt -> typedBinary(InstructionTags.I32_LT, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.I32Eq -> typedBinary(InstructionTags.I32_EQ, instruction.dst, instruction.lhs, instruction.rhs)
            is RuxLowVmInstruction.Load32 -> typedMove(InstructionTags.LOAD32, instruction.dst, instruction.addr)
            is RuxLowVmInstruction.Store32 -> typedMove(InstructionTags.STORE32, instruction.addr, instruction.src)
            is RuxLowVmInstruction.AddrAdd -> typedBinary(InstructionTags.ADDR_ADD, instruction.dst, instruction.base, instruction.offset)
            is RuxLowVmInstruction.Jump -> {
                u8(InstructionTags.JUMP)
                i32(instruction.target)
            }
            is RuxLowVmInstruction.JumpIfFalse -> {
                u8(InstructionTags.JUMP_IF_FALSE)
                u16(instruction.cond)
                i32(instruction.target)
            }
            is RuxLowVmInstruction.CallStatic -> {
                u8(InstructionTags.CALL_STATIC)
                optionalRegister(instruction.returnRegister)
                i32(instruction.functionIndex)
                list(instruction.arguments) { u16(it) }
            }
            is RuxLowVmInstruction.ReturnI32 -> {
                u8(InstructionTags.RETURN_I32)
                u16(instruction.src)
            }
            is RuxLowVmInstruction.ReturnI64 -> {
                u8(InstructionTags.RETURN_I64)
                u16(instruction.src)
            }
            is RuxLowVmInstruction.ReturnAddr -> {
                u8(InstructionTags.RETURN_ADDR)
                u16(instruction.src)
            }
            is RuxLowVmInstruction.ReturnBool -> {
                u8(InstructionTags.RETURN_BOOL)
                u16(instruction.src)
            }
            RuxLowVmInstruction.ReturnUnit -> u8(InstructionTags.RETURN_UNIT)
        }
    }

    private fun Writer.typedMove(
        tag: Int,
        dst: Int,
        src: Int,
    ) {
        u8(tag)
        u16(dst)
        u16(src)
    }

    private fun Writer.typedBinary(
        tag: Int,
        dst: Int,
        lhs: Int,
        rhs: Int,
    ) {
        u8(tag)
        u16(dst)
        u16(lhs)
        u16(rhs)
    }

    private class Writer {
        private val bytes = mutableListOf<Byte>()

        fun toByteArray(): ByteArray = bytes.toByteArray()

        fun ascii(value: String) {
            bytes.addAll(value.encodeToByteArray().map { it })
        }

        fun u8(value: Int) {
            require(value in 0..0xff)
            bytes.add(value.toByte())
        }

        fun u16(value: Int) {
            require(value in 0..UShort.MAX_VALUE.toInt())
            bytes.add((value and 0xff).toByte())
            bytes.add(((value ushr 8) and 0xff).toByte())
        }

        fun i32(value: Int) {
            bytes.add((value and 0xff).toByte())
            bytes.add(((value ushr 8) and 0xff).toByte())
            bytes.add(((value ushr 16) and 0xff).toByte())
            bytes.add(((value ushr 24) and 0xff).toByte())
        }

        fun u32(value: UInt) = i32(value.toInt())

        fun i64(value: Long) {
            repeat(8) { index ->
                bytes.add(((value ushr (index * 8)) and 0xff).toByte())
            }
        }

        fun string(value: String) {
            val encoded = value.encodeToByteArray()
            i32(encoded.size)
            bytes.addAll(encoded.map { it })
        }

        fun bytes(value: ByteArray) {
            i32(value.size)
            bytes.addAll(value.map { it })
        }

        fun <T> list(
            values: List<T>,
            write: Writer.(T) -> Unit,
        ) {
            i32(values.size)
            values.forEach { write(it) }
        }
    }
}

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

import java.io.ByteArrayOutputStream

object CkVmImageAbi {
    const val VERSION: Int = 3

    object ConstantTags {
        const val STRING = 1
        const val INT = 2
        const val LONG = 3
    }

    object RegisterTags {
        const val I32 = 1
        const val I64 = 2
        const val BOOL = 3
        const val REF = 4
    }

    object InstructionTags {
        const val I32_CONST = 1
        const val I64_CONST = 2
        const val BOOL_CONST = 3
        const val REF_CONST = 4
        const val LOAD_UNIT = 5
        const val LOAD_NULL = 6
        const val I32_MOVE = 7
        const val I64_MOVE = 8
        const val BOOL_MOVE = 9
        const val REF_MOVE = 10
        const val I32_ADD = 11
        const val I32_SUB = 12
        const val I32_MUL = 13
        const val I32_DIV = 14
        const val I32_NEG = 15
        const val I32_BIT_AND = 16
        const val I32_BIT_OR = 17
        const val I32_BIT_XOR = 18
        const val I32_BIT_NOT = 19
        const val I32_SHL = 20
        const val I32_SHR = 21
        const val I32_EQ = 22
        const val I32_NE = 23
        const val I32_LT = 24
        const val I32_LE = 25
        const val I32_GT = 26
        const val I32_GE = 27
        const val BOOL_NOT = 28
        const val BOOL_AND = 29
        const val BOOL_OR = 30
        const val JUMP = 31
        const val JUMP_IF_FALSE = 32
        const val JUMP_IF_TRUE = 33
        const val CALL_STATIC = 34
        const val RETURN = 35
        const val RETURN_UNIT = 36
        const val CALL_HOST = 37
        const val YIELD = 38
        const val SLEEP = 39
        const val CONSTRUCT_RECORD = 40
        const val GET_FIELD = 41
    }

    fun encode(image: CkVmImage): ByteArray {
        validate(image)
        val out = Writer()
        out.bytes(byteArrayOf('C'.code.toByte(), 'K'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte()))
        out.u8(VERSION)
        out.string(image.languageVersion)
        out.list(image.constants, out::constant)
        out.list(image.hostImports, out::hostImport)
        out.i32(image.entryFunctionIndex)
        out.list(image.functions, out::function)
        return out.toByteArray()
    }

    private fun validate(image: CkVmImage) {
        require(image.functions.isNotEmpty()) { "Image must contain at least one function." }
        require(image.entryFunctionIndex in image.functions.indices) { "Entry function index is outside the function table." }
        image.hostImports.forEach { hostImport ->
            require(hostImport.id >= 0) { "Host import id must be non-negative." }
        }
        image.functions.forEach { function ->
            require(function.i32RegisterCount in 0..0xffff) { "Function i32 register count must fit u16." }
            require(function.i64RegisterCount in 0..0xffff) { "Function i64 register count must fit u16." }
            require(function.boolRegisterCount in 0..0xffff) { "Function bool register count must fit u16." }
            require(function.refRegisterCount in 0..0xffff) { "Function ref register count must fit u16." }
            function.parameters.forEach { register -> requireTypedRegister(function, register) }
            function.instructions.forEachIndexed { index, instruction ->
                validateInstruction(image, function, index, instruction)
            }
        }
    }

    private fun validateInstruction(
        image: CkVmImage,
        function: CkVmFunction,
        index: Int,
        instruction: CkVmInstruction,
    ) {
        fun requireI32(register: Int) {
            require(register in 0 until function.i32RegisterCount) {
                "I32 register $register at instruction $index is outside ${function.name} i32 register bank."
            }
        }

        fun requireI64(register: Int) {
            require(register in 0 until function.i64RegisterCount) {
                "I64 register $register at instruction $index is outside ${function.name} i64 register bank."
            }
        }

        fun requireBool(register: Int) {
            require(register in 0 until function.boolRegisterCount) {
                "Bool register $register at instruction $index is outside ${function.name} bool register bank."
            }
        }

        fun requireRef(register: Int) {
            require(register in 0 until function.refRegisterCount) {
                "Ref register $register at instruction $index is outside ${function.name} ref register bank."
            }
        }

        fun requireTarget(target: Int) {
            require(target in 0..function.instructions.size) {
                "Jump target $target at instruction $index is outside ${function.name}."
            }
        }

        fun requireTyped(register: CkVmTypedRegister) = requireTypedRegister(function, register)

        fun requireTypedArguments(arguments: List<CkVmTypedRegister>) = arguments.forEach(::requireTyped)

        when (instruction) {
            is CkVmInstruction.I32Const -> {
                requireI32(instruction.dst)
                require(instruction.constantIndex in image.constants.indices) {
                    "Constant index ${instruction.constantIndex} at instruction $index is outside constant pool."
                }
                require(image.constants[instruction.constantIndex] is CkVmConstant.IntConstant) {
                    "I32 constant at instruction $index must reference an Int constant."
                }
            }

            is CkVmInstruction.I64Const -> {
                requireI64(instruction.dst)
                require(instruction.constantIndex in image.constants.indices) {
                    "Constant index ${instruction.constantIndex} at instruction $index is outside constant pool."
                }
                require(image.constants[instruction.constantIndex] is CkVmConstant.LongConstant) {
                    "I64 constant at instruction $index must reference a Long constant."
                }
            }

            is CkVmInstruction.BoolConst -> requireBool(instruction.dst)
            is CkVmInstruction.RefConst -> {
                requireRef(instruction.dst)
                require(instruction.constantIndex in image.constants.indices) {
                    "Constant index ${instruction.constantIndex} at instruction $index is outside constant pool."
                }
            }

            is CkVmInstruction.LoadUnit -> requireRef(instruction.dst)
            is CkVmInstruction.LoadNull -> requireRef(instruction.dst)
            is CkVmInstruction.I32Move -> requirePairRegisters(instruction.dst, instruction.src, ::requireI32)
            is CkVmInstruction.I64Move -> requirePairRegisters(instruction.dst, instruction.src, ::requireI64)
            is CkVmInstruction.BoolMove -> requirePairRegisters(instruction.dst, instruction.src, ::requireBool)
            is CkVmInstruction.RefMove -> requirePairRegisters(instruction.dst, instruction.src, ::requireRef)
            is CkVmInstruction.I32Add -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireI32)
            is CkVmInstruction.I32Sub -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireI32)
            is CkVmInstruction.I32Mul -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireI32)
            is CkVmInstruction.I32Div -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireI32)
            is CkVmInstruction.I32Neg -> requirePairRegisters(instruction.dst, instruction.src, ::requireI32)
            is CkVmInstruction.I32BitAnd -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireI32)
            is CkVmInstruction.I32BitOr -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireI32)
            is CkVmInstruction.I32BitXor -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireI32)
            is CkVmInstruction.I32BitNot -> requirePairRegisters(instruction.dst, instruction.src, ::requireI32)
            is CkVmInstruction.I32Shl -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireI32)
            is CkVmInstruction.I32Shr -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireI32)
            is CkVmInstruction.I32Eq -> {
                requireBool(instruction.dst)
                requirePairRegisters(instruction.lhs, instruction.rhs, ::requireI32)
            }
            is CkVmInstruction.I32Ne -> {
                requireBool(instruction.dst)
                requirePairRegisters(instruction.lhs, instruction.rhs, ::requireI32)
            }
            is CkVmInstruction.I32Lt -> {
                requireBool(instruction.dst)
                requirePairRegisters(instruction.lhs, instruction.rhs, ::requireI32)
            }
            is CkVmInstruction.I32Le -> {
                requireBool(instruction.dst)
                requirePairRegisters(instruction.lhs, instruction.rhs, ::requireI32)
            }
            is CkVmInstruction.I32Gt -> {
                requireBool(instruction.dst)
                requirePairRegisters(instruction.lhs, instruction.rhs, ::requireI32)
            }
            is CkVmInstruction.I32Ge -> {
                requireBool(instruction.dst)
                requirePairRegisters(instruction.lhs, instruction.rhs, ::requireI32)
            }
            is CkVmInstruction.BoolNot -> requirePairRegisters(instruction.dst, instruction.src, ::requireBool)
            is CkVmInstruction.BoolAnd -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireBool)
            is CkVmInstruction.BoolOr -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireBool)
            is CkVmInstruction.Jump -> requireTarget(instruction.target)
            is CkVmInstruction.JumpIfFalse -> {
                requireBool(instruction.cond)
                requireTarget(instruction.target)
            }
            is CkVmInstruction.JumpIfTrue -> {
                requireBool(instruction.cond)
                requireTarget(instruction.target)
            }
            is CkVmInstruction.CallStatic -> {
                instruction.returnRegister?.let(::requireTyped)
                require(instruction.functionIndex in image.functions.indices) {
                    "Function index ${instruction.functionIndex} at instruction $index is outside function table."
                }
                requireTypedArguments(instruction.arguments)
            }
            is CkVmInstruction.Return -> requireTyped(instruction.src)
            CkVmInstruction.ReturnUnit -> Unit
            is CkVmInstruction.CallHost -> {
                instruction.returnRegister?.let(::requireTyped)
                require(image.hostImports.any { import -> import.id == instruction.importId }) {
                    "Host import id ${instruction.importId} at instruction $index is not declared."
                }
                requireTypedArguments(instruction.arguments)
            }
            is CkVmInstruction.Yield -> requireRef(instruction.dst)
            is CkVmInstruction.Sleep -> {
                requireRef(instruction.dst)
                require(instruction.ticks is CkVmTypedRegister.I32 || instruction.ticks is CkVmTypedRegister.I64) {
                    "Sleep ticks at instruction $index must be an I32 or I64 register."
                }
                requireTyped(instruction.ticks)
            }
            is CkVmInstruction.ConstructRecord -> {
                requireRef(instruction.dst)
                require(instruction.typeNameConstantIndex in image.constants.indices) {
                    "Record type-name constant index ${instruction.typeNameConstantIndex} at instruction $index is outside constant pool."
                }
                require(instruction.fieldNameConstantIndices.size == instruction.fieldValues.size) {
                    "Record field-name and value counts differ at instruction $index."
                }
                instruction.fieldNameConstantIndices.forEach { constantIndex ->
                    require(constantIndex in image.constants.indices) {
                        "Record field-name constant index $constantIndex at instruction $index is outside constant pool."
                    }
                }
                requireTypedArguments(instruction.fieldValues)
            }
            is CkVmInstruction.GetField -> {
                requireTyped(instruction.dst)
                requireRef(instruction.receiver)
                require(instruction.fieldNameConstantIndex in image.constants.indices) {
                    "Field-name constant index ${instruction.fieldNameConstantIndex} at instruction $index is outside constant pool."
                }
            }
        }
    }

    private fun requireTypedRegister(
        function: CkVmFunction,
        register: CkVmTypedRegister,
    ) {
        val inBounds =
            when (register) {
                is CkVmTypedRegister.I32 -> register.index in 0 until function.i32RegisterCount
                is CkVmTypedRegister.I64 -> register.index in 0 until function.i64RegisterCount
                is CkVmTypedRegister.Bool -> register.index in 0 until function.boolRegisterCount
                is CkVmTypedRegister.Ref -> register.index in 0 until function.refRegisterCount
            }
        require(inBounds) { "Typed register $register is outside ${function.name} register banks." }
    }

    private fun requirePairRegisters(
        first: Int,
        second: Int,
        requireRegister: (Int) -> Unit,
    ) {
        requireRegister(first)
        requireRegister(second)
    }

    private fun requireTripleRegisters(
        first: Int,
        second: Int,
        third: Int,
        requireRegister: (Int) -> Unit,
    ) {
        requireRegister(first)
        requireRegister(second)
        requireRegister(third)
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun toByteArray(): ByteArray = out.toByteArray()

        fun bytes(value: ByteArray) = out.write(value)

        fun u8(value: Int) = out.write(value and 0xff)

        fun u16(value: Int) {
            u8(value)
            u8(value ushr 8)
        }

        fun i32(value: Int) {
            u8(value)
            u8(value ushr 8)
            u8(value ushr 16)
            u8(value ushr 24)
        }

        fun i64(value: Long) {
            repeat(8) { index -> u8((value ushr (index * 8)).toInt()) }
        }

        fun string(value: String) {
            val bytes = value.encodeToByteArray()
            i32(bytes.size)
            bytes(bytes)
        }

        fun <T> list(
            values: List<T>,
            write: (T) -> Unit,
        ) {
            i32(values.size)
            values.forEach(write)
        }

        fun constant(constant: CkVmConstant) {
            when (constant) {
                is CkVmConstant.StringConstant -> {
                    u8(ConstantTags.STRING)
                    string(constant.value)
                }
                is CkVmConstant.IntConstant -> {
                    u8(ConstantTags.INT)
                    i32(constant.value)
                }
                is CkVmConstant.LongConstant -> {
                    u8(ConstantTags.LONG)
                    i64(constant.value)
                }
            }
        }

        fun hostImport(hostImport: CkVmHostImport) {
            i32(hostImport.id)
            string(hostImport.moduleName)
            string(hostImport.functionName)
            list(hostImport.parameterTypes, ::string)
            string(hostImport.returnType)
        }

        fun function(function: CkVmFunction) {
            string(function.name)
            u16(function.i32RegisterCount)
            u16(function.i64RegisterCount)
            u16(function.boolRegisterCount)
            u16(function.refRegisterCount)
            list(function.parameters, ::typedRegister)
            list(function.instructions, ::instruction)
        }

        fun instruction(instruction: CkVmInstruction) {
            when (instruction) {
                is CkVmInstruction.I32Const -> {
                    u8(InstructionTags.I32_CONST)
                    register(instruction.dst)
                    i32(instruction.constantIndex)
                }
                is CkVmInstruction.I64Const -> {
                    u8(InstructionTags.I64_CONST)
                    register(instruction.dst)
                    i32(instruction.constantIndex)
                }
                is CkVmInstruction.BoolConst -> {
                    u8(InstructionTags.BOOL_CONST)
                    register(instruction.dst)
                    u8(if (instruction.value) 1 else 0)
                }
                is CkVmInstruction.RefConst -> {
                    u8(InstructionTags.REF_CONST)
                    register(instruction.dst)
                    i32(instruction.constantIndex)
                }
                is CkVmInstruction.LoadUnit -> {
                    u8(InstructionTags.LOAD_UNIT)
                    register(instruction.dst)
                }
                is CkVmInstruction.LoadNull -> {
                    u8(InstructionTags.LOAD_NULL)
                    register(instruction.dst)
                }
                is CkVmInstruction.I32Move -> typedMove(InstructionTags.I32_MOVE, instruction.dst, instruction.src)
                is CkVmInstruction.I64Move -> typedMove(InstructionTags.I64_MOVE, instruction.dst, instruction.src)
                is CkVmInstruction.BoolMove -> typedMove(InstructionTags.BOOL_MOVE, instruction.dst, instruction.src)
                is CkVmInstruction.RefMove -> typedMove(InstructionTags.REF_MOVE, instruction.dst, instruction.src)
                is CkVmInstruction.I32Add -> binary(InstructionTags.I32_ADD, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32Sub -> binary(InstructionTags.I32_SUB, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32Mul -> binary(InstructionTags.I32_MUL, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32Div -> binary(InstructionTags.I32_DIV, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32Neg -> unary(InstructionTags.I32_NEG, instruction.dst, instruction.src)
                is CkVmInstruction.I32BitAnd -> binary(InstructionTags.I32_BIT_AND, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32BitOr -> binary(InstructionTags.I32_BIT_OR, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32BitXor -> binary(InstructionTags.I32_BIT_XOR, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32BitNot -> unary(InstructionTags.I32_BIT_NOT, instruction.dst, instruction.src)
                is CkVmInstruction.I32Shl -> binary(InstructionTags.I32_SHL, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32Shr -> binary(InstructionTags.I32_SHR, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32Eq -> binary(InstructionTags.I32_EQ, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32Ne -> binary(InstructionTags.I32_NE, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32Lt -> binary(InstructionTags.I32_LT, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32Le -> binary(InstructionTags.I32_LE, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32Gt -> binary(InstructionTags.I32_GT, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.I32Ge -> binary(InstructionTags.I32_GE, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.BoolNot -> unary(InstructionTags.BOOL_NOT, instruction.dst, instruction.src)
                is CkVmInstruction.BoolAnd -> binary(InstructionTags.BOOL_AND, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.BoolOr -> binary(InstructionTags.BOOL_OR, instruction.dst, instruction.lhs, instruction.rhs)
                is CkVmInstruction.Jump -> {
                    u8(InstructionTags.JUMP)
                    i32(instruction.target)
                }
                is CkVmInstruction.JumpIfFalse -> {
                    u8(InstructionTags.JUMP_IF_FALSE)
                    register(instruction.cond)
                    i32(instruction.target)
                }
                is CkVmInstruction.JumpIfTrue -> {
                    u8(InstructionTags.JUMP_IF_TRUE)
                    register(instruction.cond)
                    i32(instruction.target)
                }
                is CkVmInstruction.CallStatic -> {
                    u8(InstructionTags.CALL_STATIC)
                    optionalTypedRegister(instruction.returnRegister)
                    i32(instruction.functionIndex)
                    typedRegisterList(instruction.arguments)
                }
                is CkVmInstruction.Return -> {
                    u8(InstructionTags.RETURN)
                    typedRegister(instruction.src)
                }
                CkVmInstruction.ReturnUnit -> u8(InstructionTags.RETURN_UNIT)
                is CkVmInstruction.CallHost -> {
                    u8(InstructionTags.CALL_HOST)
                    optionalTypedRegister(instruction.returnRegister)
                    i32(instruction.importId)
                    typedRegisterList(instruction.arguments)
                }
                is CkVmInstruction.Yield -> {
                    u8(InstructionTags.YIELD)
                    register(instruction.dst)
                }
                is CkVmInstruction.Sleep -> {
                    u8(InstructionTags.SLEEP)
                    register(instruction.dst)
                    typedRegister(instruction.ticks)
                }
                is CkVmInstruction.ConstructRecord -> {
                    u8(InstructionTags.CONSTRUCT_RECORD)
                    register(instruction.dst)
                    i32(instruction.typeNameConstantIndex)
                    i32(instruction.fieldNameConstantIndices.size)
                    instruction.fieldNameConstantIndices.forEach(::i32)
                    typedRegisterList(instruction.fieldValues)
                }
                is CkVmInstruction.GetField -> {
                    u8(InstructionTags.GET_FIELD)
                    typedRegister(instruction.dst)
                    register(instruction.receiver)
                    i32(instruction.fieldNameConstantIndex)
                }
            }
        }

        private fun binary(
            tag: Int,
            dst: Int,
            lhs: Int,
            rhs: Int,
        ) {
            u8(tag)
            register(dst)
            register(lhs)
            register(rhs)
        }

        private fun unary(
            tag: Int,
            dst: Int,
            src: Int,
        ) {
            u8(tag)
            register(dst)
            register(src)
        }

        private fun typedMove(
            tag: Int,
            dst: Int,
            src: Int,
        ) {
            u8(tag)
            register(dst)
            register(src)
        }

        private fun register(register: Int) = u16(register)

        private fun typedRegister(register: CkVmTypedRegister) {
            when (register) {
                is CkVmTypedRegister.I32 -> {
                    u8(RegisterTags.I32)
                    register(register.index)
                }
                is CkVmTypedRegister.I64 -> {
                    u8(RegisterTags.I64)
                    register(register.index)
                }
                is CkVmTypedRegister.Bool -> {
                    u8(RegisterTags.BOOL)
                    register(register.index)
                }
                is CkVmTypedRegister.Ref -> {
                    u8(RegisterTags.REF)
                    register(register.index)
                }
            }
        }

        private fun optionalTypedRegister(register: CkVmTypedRegister?) {
            if (register == null) {
                u8(0)
            } else {
                u8(1)
                typedRegister(register)
            }
        }

        private fun typedRegisterList(registers: List<CkVmTypedRegister>) {
            i32(registers.size)
            registers.forEach(::typedRegister)
        }
    }
}

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
    const val VERSION: Int = 2

    object ConstantTags {
        const val STRING = 1
        const val INT = 2
        const val LONG = 3
    }

    object InstructionTags {
        const val LOAD_CONST = 1
        const val LOAD_UNIT = 2
        const val LOAD_NULL = 3
        const val LOAD_BOOL = 4
        const val MOVE = 5
        const val I32_ADD = 6
        const val I32_SUB = 7
        const val I32_MUL = 8
        const val I32_DIV = 9
        const val I32_NEG = 10
        const val I32_BIT_AND = 11
        const val I32_BIT_OR = 12
        const val I32_BIT_XOR = 13
        const val I32_BIT_NOT = 14
        const val I32_SHL = 15
        const val I32_SHR = 16
        const val I32_EQ = 17
        const val I32_NE = 18
        const val I32_LT = 19
        const val I32_LE = 20
        const val I32_GT = 21
        const val I32_GE = 22
        const val BOOL_NOT = 23
        const val BOOL_AND = 24
        const val BOOL_OR = 25
        const val JUMP = 26
        const val JUMP_IF_FALSE = 27
        const val JUMP_IF_TRUE = 28
        const val CALL_STATIC = 29
        const val RETURN = 30
        const val RETURN_UNIT = 31
        const val CALL_HOST = 32
        const val YIELD = 33
        const val SLEEP = 34
        const val CONSTRUCT_RECORD = 35
        const val GET_FIELD = 36
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
            require(function.registerCount in 0..0xffff) { "Function register count must fit u16." }
            require(function.parameterCount in 0..function.registerCount) {
                "Function parameter count must be within register count."
            }
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
        fun requireRegister(register: Int) {
            require(register in 0 until function.registerCount) {
                "Register $register at instruction $index is outside ${function.name} register file."
            }
        }

        fun requireOptionalRegister(register: Int?) {
            if (register != null) requireRegister(register)
        }

        fun requireTarget(target: Int) {
            require(target in 0..function.instructions.size) {
                "Jump target $target at instruction $index is outside ${function.name}."
            }
        }

        fun requireArguments(arguments: List<Int>) {
            arguments.forEach(::requireRegister)
        }

        when (instruction) {
            is CkVmInstruction.LoadConst -> {
                requireRegister(instruction.dst)
                require(instruction.constantIndex in image.constants.indices) {
                    "Constant index ${instruction.constantIndex} at instruction $index is outside constant pool."
                }
            }

            is CkVmInstruction.LoadUnit -> requireRegister(instruction.dst)
            is CkVmInstruction.LoadNull -> requireRegister(instruction.dst)
            is CkVmInstruction.LoadBool -> requireRegister(instruction.dst)
            is CkVmInstruction.Move -> {
                requireRegister(instruction.dst)
                requireRegister(instruction.src)
            }

            is CkVmInstruction.I32Add -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32Sub -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32Mul -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32Div -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32Neg -> requirePairRegisters(instruction.dst, instruction.src, ::requireRegister)
            is CkVmInstruction.I32BitAnd -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32BitOr -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32BitXor -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32BitNot -> requirePairRegisters(instruction.dst, instruction.src, ::requireRegister)
            is CkVmInstruction.I32Shl -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32Shr -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32Eq -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32Ne -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32Lt -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32Le -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32Gt -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.I32Ge -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.BoolNot -> requirePairRegisters(instruction.dst, instruction.src, ::requireRegister)
            is CkVmInstruction.BoolAnd -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.BoolOr -> requireTripleRegisters(instruction.dst, instruction.lhs, instruction.rhs, ::requireRegister)
            is CkVmInstruction.Jump -> requireTarget(instruction.target)
            is CkVmInstruction.JumpIfFalse -> {
                requireRegister(instruction.cond)
                requireTarget(instruction.target)
            }

            is CkVmInstruction.JumpIfTrue -> {
                requireRegister(instruction.cond)
                requireTarget(instruction.target)
            }

            is CkVmInstruction.CallStatic -> {
                requireOptionalRegister(instruction.returnRegister)
                require(instruction.functionIndex in image.functions.indices) {
                    "Function index ${instruction.functionIndex} at instruction $index is outside function table."
                }
                requireArguments(instruction.arguments)
            }

            is CkVmInstruction.Return -> requireRegister(instruction.src)
            CkVmInstruction.ReturnUnit -> Unit
            is CkVmInstruction.CallHost -> {
                requireOptionalRegister(instruction.returnRegister)
                require(image.hostImports.any { import -> import.id == instruction.importId }) {
                    "Host import id ${instruction.importId} at instruction $index is not declared."
                }
                requireArguments(instruction.arguments)
            }

            is CkVmInstruction.Yield -> requireRegister(instruction.dst)
            is CkVmInstruction.Sleep -> requirePairRegisters(instruction.dst, instruction.ticks, ::requireRegister)
            is CkVmInstruction.ConstructRecord -> {
                requireRegister(instruction.dst)
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
                requireArguments(instruction.fieldValues)
            }

            is CkVmInstruction.GetField -> {
                requirePairRegisters(instruction.dst, instruction.receiver, ::requireRegister)
                require(instruction.fieldNameConstantIndex in image.constants.indices) {
                    "Field-name constant index ${instruction.fieldNameConstantIndex} at instruction $index is outside constant pool."
                }
            }
        }
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
            u16(function.registerCount)
            u16(function.parameterCount)
            list(function.instructions, ::instruction)
        }

        fun instruction(instruction: CkVmInstruction) {
            when (instruction) {
                is CkVmInstruction.LoadConst -> {
                    u8(InstructionTags.LOAD_CONST)
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

                is CkVmInstruction.LoadBool -> {
                    u8(InstructionTags.LOAD_BOOL)
                    register(instruction.dst)
                    u8(if (instruction.value) 1 else 0)
                }

                is CkVmInstruction.Move -> {
                    u8(InstructionTags.MOVE)
                    register(instruction.dst)
                    register(instruction.src)
                }

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
                    optionalRegister(instruction.returnRegister)
                    i32(instruction.functionIndex)
                    registerList(instruction.arguments)
                }

                is CkVmInstruction.Return -> {
                    u8(InstructionTags.RETURN)
                    register(instruction.src)
                }

                CkVmInstruction.ReturnUnit -> {
                    u8(InstructionTags.RETURN_UNIT)
                }

                is CkVmInstruction.CallHost -> {
                    u8(InstructionTags.CALL_HOST)
                    optionalRegister(instruction.returnRegister)
                    i32(instruction.importId)
                    registerList(instruction.arguments)
                }

                is CkVmInstruction.Yield -> {
                    u8(InstructionTags.YIELD)
                    register(instruction.dst)
                }

                is CkVmInstruction.Sleep -> {
                    u8(InstructionTags.SLEEP)
                    register(instruction.dst)
                    register(instruction.ticks)
                }

                is CkVmInstruction.ConstructRecord -> {
                    u8(InstructionTags.CONSTRUCT_RECORD)
                    register(instruction.dst)
                    i32(instruction.typeNameConstantIndex)
                    i32(instruction.fieldNameConstantIndices.size)
                    instruction.fieldNameConstantIndices.forEach(::i32)
                    registerList(instruction.fieldValues)
                }

                is CkVmInstruction.GetField -> {
                    u8(InstructionTags.GET_FIELD)
                    register(instruction.dst)
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

        private fun register(register: Int) = u16(register)

        private fun optionalRegister(register: Int?) {
            if (register == null) {
                u8(0)
            } else {
                u8(1)
                register(register)
            }
        }

        private fun registerList(registers: List<Int>) {
            i32(registers.size)
            registers.forEach(::register)
        }
    }
}

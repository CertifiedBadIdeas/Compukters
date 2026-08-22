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

package ru.lazyhat.compukters.compiler.artifact.write

import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef

internal data class EncodedInstruction(
    val bytes: ByteArray,
    val fixedCost: UInt,
)

internal fun encodeInstruction(
    instruction: Instruction,
    maximumBytes: Int,
): EncodedInstruction {
    val operands = BinarySink(maximumBytes)
    val opcode: UInt
    val cost = instructionFixedCost(instruction)

    when (instruction) {
        is Instruction.Const -> {
            opcode = 0x02u
            operands.writeRegister(instruction.destination)
            operands.writeUleb128(instruction.constant.value)
        }

        is Instruction.Null -> {
            opcode = 0x03u
            operands.writeRegister(instruction.destination)
        }

        is Instruction.NewObject -> {
            opcode = 0x30u
            operands.writeRegister(instruction.destination)
            operands.writeUleb128(encodeTypeRef(instruction.type))
        }

        is Instruction.NewArray -> {
            opcode = 0x31u
            operands.writeRegister(instruction.destination)
            operands.writeUleb128(encodeTypeRef(instruction.type))
            operands.writeRegister(instruction.length)
        }

        is Instruction.ArrayLoad -> {
            opcode = 0x33u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.array)
            operands.writeRegister(instruction.index)
        }

        is Instruction.ArrayStore -> {
            opcode = 0x34u
            operands.writeRegister(instruction.array)
            operands.writeRegister(instruction.index)
            operands.writeRegister(instruction.value)
        }

        is Instruction.IsType -> {
            opcode = 0x39u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.value)
            operands.writeUleb128(encodeTypeRef(instruction.type))
        }

        is Instruction.Call -> {
            opcode = 0x40u
            operands.writeDestination(instruction.destination)
            operands.writeUleb128(encodeFunctionRef(instruction.function))
            operands.writeArguments(instruction.arguments)
        }

        is Instruction.CallSuspend -> {
            opcode = 0xe5u
            operands.writeDestination(instruction.destination)
            operands.writeUleb128(encodeFunctionRef(instruction.function))
            operands.writeArguments(instruction.arguments)
            operands.writeUleb128(instruction.resumeBlock.value)
        }

        is Instruction.StringConcat -> {
            opcode = 0x65u
            operands.writeRegister(instruction.destination)
            operands.writeRegister(instruction.left)
            operands.writeRegister(instruction.right)
        }

        is Instruction.CapabilityCallAsync -> {
            opcode = 0xe9u
            operands.writeDestination(instruction.destination)
            operands.writeUleb128(instruction.capability.value)
            operands.writeUleb128(instruction.operation)
            operands.writeArguments(instruction.arguments)
            operands.writeUleb128(instruction.resumeBlock.value)
        }

        is Instruction.Jump -> {
            opcode = 0xe0u
            operands.writeUleb128(instruction.target.value)
        }

        is Instruction.Branch -> {
            opcode = 0xe1u
            operands.writeRegister(instruction.condition)
            operands.writeUleb128(instruction.trueTarget.value)
            operands.writeUleb128(instruction.falseTarget.value)
        }

        is Instruction.Return -> {
            opcode = 0xe3u
            operands.writeDestination(instruction.value)
        }

        is Instruction.Throw -> {
            opcode = 0xe4u
            operands.writeRegister(instruction.exception)
        }
    }

    val operandBytes = operands.toByteArray()
    val length = 4L + operandBytes.size
    if (length > UShort.MAX_VALUE.toLong()) {
        throw ArtifactEncodingException(ArtifactWriteErrorCode.OVERFLOW, "instruction frame exceeds u16")
    }
    val frame = BinarySink(maximumBytes)
    frame.writeU8(opcode)
    frame.writeU8(0u)
    frame.writeU16(length.toUInt())
    frame.writeBytes(operandBytes)
    return EncodedInstruction(frame.toByteArray(), cost)
}

private fun BinarySink.writeRegister(register: RegisterId) {
    writeU16(register.value.toUInt())
}

private fun BinarySink.writeDestination(destination: Destination) {
    when (destination) {
        is Destination.Register -> writeRegister(destination.id)
        Destination.Unit -> writeU16(UShort.MAX_VALUE.toUInt())
    }
}

private fun BinarySink.writeArguments(arguments: List<RegisterId>) {
    writeUleb128(arguments.size.toUInt())
    arguments.forEach(::writeRegister)
}

private fun variableCost(
    base: UInt,
    argumentCount: Int,
): UInt {
    val cost = base.toULong() + argumentCount.toULong()
    if (cost > UInt.MAX_VALUE.toULong()) {
        throw ArtifactEncodingException(ArtifactWriteErrorCode.OVERFLOW, "instruction fixed cost exceeds u32")
    }
    return cost.toUInt()
}

internal fun instructionFixedCost(instruction: Instruction): UInt =
    when (instruction) {
        is Instruction.Const,
        is Instruction.Null,
        is Instruction.StringConcat,
        is Instruction.Jump,
        is Instruction.Branch,
        is Instruction.Return,
        -> 1u

        is Instruction.ArrayLoad,
        is Instruction.ArrayStore,
        is Instruction.IsType,
        is Instruction.Throw,
        -> 2u

        is Instruction.NewObject,
        is Instruction.NewArray,
        -> 4u

        is Instruction.Call -> variableCost(4u, instruction.arguments.size)

        is Instruction.CallSuspend -> variableCost(5u, instruction.arguments.size)

        is Instruction.CapabilityCallAsync -> variableCost(6u, instruction.arguments.size)
    }

internal fun encodeTypeRef(reference: TypeRef): UInt =
    when (reference) {
        is TypeRef.Local -> reference.id.value
        is TypeRef.Imported -> reference.id.value or 0x8000_0000u
    }

internal fun encodeFunctionRef(reference: FunctionRef): UInt =
    when (reference) {
        is FunctionRef.Local -> reference.id.value
        is FunctionRef.Imported -> reference.id.value or 0x8000_0000u
    }

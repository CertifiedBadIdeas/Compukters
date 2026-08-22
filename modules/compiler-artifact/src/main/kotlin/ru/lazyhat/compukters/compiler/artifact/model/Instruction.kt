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

package ru.lazyhat.compukters.compiler.artifact.model

sealed interface Instruction {
    data class Const(
        val destination: RegisterId,
        val constant: ConstantId,
    ) : Instruction

    data class Null(
        val destination: RegisterId,
    ) : Instruction

    data class NewObject(
        val destination: RegisterId,
        val type: TypeRef,
    ) : Instruction

    data class NewArray(
        val destination: RegisterId,
        val type: TypeRef,
        val length: RegisterId,
    ) : Instruction

    data class ArrayLoad(
        val destination: RegisterId,
        val array: RegisterId,
        val index: RegisterId,
    ) : Instruction

    data class ArrayStore(
        val array: RegisterId,
        val index: RegisterId,
        val value: RegisterId,
    ) : Instruction

    data class IsType(
        val destination: RegisterId,
        val value: RegisterId,
        val type: TypeRef,
    ) : Instruction

    class Call(
        val destination: Destination,
        val function: FunctionRef,
        arguments: List<RegisterId>,
    ) : Instruction {
        val arguments: List<RegisterId> = arguments.toList()

        override fun equals(other: Any?): Boolean =
            other is Call && destination == other.destination && function == other.function && arguments == other.arguments

        override fun hashCode(): Int = 31 * (31 * destination.hashCode() + function.hashCode()) + arguments.hashCode()

        override fun toString(): String = "Call(destination=$destination, function=$function, arguments=$arguments)"
    }

    class CallSuspend(
        val destination: Destination,
        val function: FunctionRef,
        arguments: List<RegisterId>,
        val resumeBlock: BlockId,
    ) : Instruction {
        val arguments: List<RegisterId> = arguments.toList()

        override fun equals(other: Any?): Boolean =
            other is CallSuspend &&
                destination == other.destination &&
                function == other.function &&
                arguments == other.arguments &&
                resumeBlock == other.resumeBlock

        override fun hashCode(): Int =
            31 * (31 * (31 * destination.hashCode() + function.hashCode()) + arguments.hashCode()) + resumeBlock.hashCode()

        override fun toString(): String =
            "CallSuspend(destination=$destination, function=$function, arguments=$arguments, resumeBlock=$resumeBlock)"
    }

    data class StringConcat(
        val destination: RegisterId,
        val left: RegisterId,
        val right: RegisterId,
    ) : Instruction

    class CapabilityCallAsync(
        val destination: Destination,
        val capability: CapabilityId,
        val operation: UInt,
        arguments: List<RegisterId>,
        val resumeBlock: BlockId,
    ) : Instruction {
        val arguments: List<RegisterId> = arguments.toList()

        override fun equals(other: Any?): Boolean =
            other is CapabilityCallAsync &&
                destination == other.destination &&
                capability == other.capability &&
                operation == other.operation &&
                arguments == other.arguments &&
                resumeBlock == other.resumeBlock

        override fun hashCode(): Int =
            31 *
                (
                    31 *
                        (31 * (31 * destination.hashCode() + capability.hashCode()) + operation.hashCode()) +
                        arguments.hashCode()
                ) +
                resumeBlock.hashCode()

        override fun toString(): String =
            "CapabilityCallAsync(destination=$destination, capability=$capability, operation=$operation, " +
                "arguments=$arguments, resumeBlock=$resumeBlock)"
    }

    data class Jump(
        val target: BlockId,
    ) : Instruction

    data class Branch(
        val condition: RegisterId,
        val trueTarget: BlockId,
        val falseTarget: BlockId,
    ) : Instruction

    data class Return(
        val value: Destination,
    ) : Instruction

    data class Throw(
        val exception: RegisterId,
    ) : Instruction
}

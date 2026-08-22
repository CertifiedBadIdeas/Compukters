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

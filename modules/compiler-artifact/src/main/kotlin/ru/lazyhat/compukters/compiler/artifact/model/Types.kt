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

sealed interface ValueType {
    data object Unit : ValueType

    data object I32 : ValueType

    data object I64 : ValueType

    data object F32 : ValueType

    data object F64 : ValueType

    data object Bool : ValueType

    data object Char : ValueType

    data class Ref(
        val nullable: Boolean,
        val type: TypeRef,
    ) : ValueType
}

sealed interface NominalType {
    val name: StringId

    data class Class(
        override val name: StringId,
        val abstract: Boolean = false,
        val final: Boolean = false,
        val genericArity: UShort = 0u,
        val superType: TypeRef? = null,
        val interfaces: List<TypeRef> = emptyList(),
        val fieldStart: UInt = 0u,
        val fieldCount: UInt = 0u,
        val methodStart: UInt = 0u,
        val methodCount: UInt = 0u,
    ) : NominalType

    data class Interface(
        override val name: StringId,
        val sealed: Boolean = false,
        val genericArity: UShort = 0u,
        val superType: TypeRef? = null,
        val interfaces: List<TypeRef> = emptyList(),
        val methodStart: UInt = 0u,
        val methodCount: UInt = 0u,
    ) : NominalType

    data class Array(
        override val name: StringId,
        val element: ValueType,
    ) : NominalType

    data class Function(
        override val name: StringId,
        val suspending: Boolean,
        val result: ValueType,
        val parameters: List<ValueType>,
    ) : NominalType
}

sealed interface Constant {
    val tag: Int

    data class I32(val value: Int) : Constant {
        override val tag: Int = 0
    }

    data class I64(val value: Long) : Constant {
        override val tag: Int = 1
    }

    data class F32(val bits: UInt) : Constant {
        override val tag: Int = 2
    }

    data class F64(val bits: ULong) : Constant {
        override val tag: Int = 3
    }

    data class Bool(val value: Boolean) : Constant {
        override val tag: Int = 4
    }

    data class Char(val codeUnit: UShort) : Constant {
        override val tag: Int = 5
    }

    data class StringLiteral(val literal: Utf16LiteralId) : Constant {
        override val tag: Int = 6
    }

    data object Null : Constant {
        override val tag: Int = 7
    }
}

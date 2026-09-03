/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

/** Scalar kinds accepted by Artifact v1 comparison forms. */
enum class ScalarValueType(
    internal val artifactForm: UInt,
    internal val valueType: ValueType,
) {
    I32(1u, ValueType.I32),
    I64(2u, ValueType.I64),
    F32(3u, ValueType.F32),
    F64(4u, ValueType.F64),
    BOOL(5u, ValueType.Bool),
    CHAR(6u, ValueType.Char),
}

/** Scalar kinds accepted by Artifact v1 ordered-comparison forms. */
enum class OrderedScalarValueType(
    internal val artifactForm: UInt,
    internal val valueType: ValueType,
) {
    I32(1u, ValueType.I32),
    I64(2u, ValueType.I64),
    F32(3u, ValueType.F32),
    F64(4u, ValueType.F64),
    CHAR(6u, ValueType.Char),
}

/** Scalar kinds accepted by Artifact v1 string-value conversion forms. */
enum class StringValueType(
    internal val artifactForm: UInt,
    internal val valueType: ValueType,
) {
    I32(1u, ValueType.I32),
    BOOL(5u, ValueType.Bool),
    CHAR(6u, ValueType.Char),
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
        val initializer: FunctionId? = null,
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

    data class I32(
        val value: Int,
    ) : Constant {
        override val tag: Int = 0
    }

    data class I64(
        val value: Long,
    ) : Constant {
        override val tag: Int = 1
    }

    data class F32(
        val bits: UInt,
    ) : Constant {
        override val tag: Int = 2
    }

    data class F64(
        val bits: ULong,
    ) : Constant {
        override val tag: Int = 3
    }

    data class Bool(
        val value: Boolean,
    ) : Constant {
        override val tag: Int = 4
    }

    data class Char(
        val codeUnit: UShort,
    ) : Constant {
        override val tag: Int = 5
    }

    data class StringLiteral(
        val literal: Utf16LiteralId,
    ) : Constant {
        override val tag: Int = 6
    }

    data object Null : Constant {
        override val tag: Int = 7
    }
}

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

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import ru.lazyhat.compukters.compiler.artifact.model.ArrayStorage
import ru.lazyhat.compukters.compiler.artifact.model.OrderedScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.StringValueType
import ru.lazyhat.compukters.compiler.artifact.model.ValueType

/** Canonical primitive identity shared by scalar, box, array and collection lowering. */
internal enum class GuestPrimitive(
    val sourceName: String,
    val scalar: ValueType,
    val boxType: UInt,
    val boxField: UInt,
    val arrayType: UInt,
    val arrayStorage: ArrayStorage = ArrayStorage.NATURAL,
) {
    INT("Int", ValueType.I32, 6u, 0u, 4u),
    BOOLEAN("Boolean", ValueType.Bool, 17u, 3u, 30u),
    LONG("Long", ValueType.I64, 18u, 4u, 33u),
    FLOAT("Float", ValueType.F32, 19u, 5u, 34u),
    DOUBLE("Double", ValueType.F64, 20u, 6u, 23u),
    CHAR("Char", ValueType.Char, 21u, 7u, 0u),
    BYTE("Byte", ValueType.I32, 24u, 9u, 31u, ArrayStorage.I8),
    SHORT("Short", ValueType.I32, 25u, 10u, 32u, ArrayStorage.I16),
    UBYTE("UByte", ValueType.I32, 26u, 11u, 35u, ArrayStorage.U8),
    USHORT("UShort", ValueType.I32, 27u, 12u, 36u, ArrayStorage.U16),
    UINT("UInt", ValueType.I32, 28u, 13u, 37u, ArrayStorage.U32),
    ULONG("ULong", ValueType.I64, 29u, 14u, 38u, ArrayStorage.U64),
    ;

    val qualifiedName: String get() = "kotlin.$sourceName"
    val arrayName: String get() = "kotlin.${sourceName}Array"
    val arrayFactory: String get() = "kotlin.${sourceName.lowercase()}ArrayOf"
    val boxFieldName: String get() = "$qualifiedName.<boxed-value>"
    val boxFieldImport: UInt get() = RUNTIME_TYPE_COUNT + boxField + if (boxField >= 9u) 3u else 0u
    val unsigned: Boolean get() = this == UBYTE || this == USHORT || this == UINT || this == ULONG
    val numeric: Boolean get() = this != BOOLEAN && this != CHAR
    val narrowBits: Int? get() =
        when (this) {
            BYTE, UBYTE -> 8
            SHORT, USHORT -> 16
            else -> null
        }

    val scalarForm: ScalarValueType get() =
        when (scalar) {
            ValueType.I32 -> if (unsigned) ScalarValueType.U32 else ScalarValueType.I32
            ValueType.I64 -> if (unsigned) ScalarValueType.U64 else ScalarValueType.I64
            ValueType.F32 -> ScalarValueType.F32
            ValueType.F64 -> ScalarValueType.F64
            ValueType.Bool -> ScalarValueType.BOOL
            ValueType.Char -> ScalarValueType.CHAR
            else -> error("unsupported primitive scalar")
        }
    val orderedForm: OrderedScalarValueType get() =
        OrderedScalarValueType
            .valueOf(scalarForm.name)
    val stringForm: StringValueType get() =
        StringValueType
            .valueOf(scalarForm.name)

    companion object {
        const val RUNTIME_TYPE_COUNT: UInt = 39u
        private val scalars = entries.associateBy { it.qualifiedName }
        private val arrays = entries.associateBy { it.arrayName }

        fun promote(primitives: List<GuestPrimitive>): GuestPrimitive =
            when {
                DOUBLE in primitives -> DOUBLE
                FLOAT in primitives -> FLOAT
                ULONG in primitives -> ULONG
                LONG in primitives -> LONG
                primitives.any { it.unsigned } -> UINT
                else -> INT
            }

        @OptIn(UnsafeDuringIrConstructionAPI::class)
        fun scalar(type: IrType): GuestPrimitive? = scalars[qualifiedName(type)]

        @OptIn(UnsafeDuringIrConstructionAPI::class)
        fun array(type: IrType): GuestPrimitive? = arrays[qualifiedName(type)]

        @OptIn(UnsafeDuringIrConstructionAPI::class)
        private fun qualifiedName(type: IrType): String? =
            ((type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.fqNameWhenAvailable?.asString()
    }
}

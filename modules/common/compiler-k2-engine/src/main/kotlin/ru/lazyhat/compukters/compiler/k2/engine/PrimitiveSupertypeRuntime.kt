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
import ru.lazyhat.compukters.compiler.artifact.analysis.TemporaryRegisters
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.Export
import ru.lazyhat.compukters.compiler.artifact.model.ExportVisibility
import ru.lazyhat.compukters.compiler.artifact.model.FieldId
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.ImportId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.MetadataText
import ru.lazyhat.compukters.compiler.artifact.model.Module
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.OrderedScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.SymbolKind
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType
import ru.lazyhat.compukters.compiler.artifact.pool.ConstantPoolBuilder

internal const val NUMBER_NAME = "kotlin.Number"
internal const val COMPARABLE_NAME = "kotlin.Comparable"
internal const val NUMBER_IMPORT = 64u
internal const val COMPARABLE_IMPORT = 65u
internal const val NUMBER_METHOD_IMPORT = 66u
internal const val COMPARABLE_METHOD_IMPORT = 72u

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun primitiveSupertypeImport(type: IrType): UInt? =
    when (((type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.fqNameWhenAvailable?.asString()) {
        NUMBER_NAME -> NUMBER_IMPORT
        COMPARABLE_NAME -> COMPARABLE_IMPORT
        else -> null
    }

internal val numberPrimitives = GuestPrimitive.entries.filter { it.numeric && !it.unsigned }
internal val numberMethods = numberPrimitives.map { "to${it.sourceName}" }
internal val primitiveSupertypeNames =
    listOf(NUMBER_NAME, COMPARABLE_NAME) +
        numberMethods.map { "$NUMBER_NAME.$it" } + "$COMPARABLE_NAME.compareTo"

internal fun primitiveSupertypeSignatures(ids: Map<String, StringId>): List<NominalType.Function> =
    numberPrimitives.map { primitive ->
        NominalType.Function(
            ids.getValue("$NUMBER_NAME.to${primitive.sourceName}"),
            false,
            primitive.scalar,
            listOf(ValueType.Ref(false, TypeRef.Imported(ImportId.of(NUMBER_IMPORT)))),
        )
    } +
        NominalType.Function(
            ids.getValue("$COMPARABLE_NAME.compareTo"),
            false,
            ValueType.I32,
            listOf(
                ValueType.Ref(false, TypeRef.Imported(ImportId.of(COMPARABLE_IMPORT))),
                ValueType.Ref(true, TypeRef.Imported(ImportId.of(5u))),
            ),
        )

/** Extend the compiler-owned Kotlin runtime with nominal numeric and erased comparison dispatch.
 * Source typing retains Comparable's argument; only its managed invocation boundary is erased.
 */
internal fun primitiveSupertypeRuntime(base: Module): Module {
    val strings = base.strings.toMutableList()

    fun name(value: String): StringId {
        val existing = strings.indexOfFirst { it.toString() == value }
        if (existing >= 0) return StringId.of(existing.toUInt())
        strings += MetadataText.of(value)
        return StringId.of(strings.lastIndex.toUInt())
    }
    val types = base.types.toMutableList()
    val functions = base.functions.toMutableList()
    val blocks = base.blocks.toMutableList()
    val constants = base.constants.toMutableList()
    val exports = base.exports.toMutableList()
    val any = TypeRef.Local(TypeId.of(5u))
    val number = TypeRef.Local(TypeId.of(types.size.toUInt()))
    types += NominalType.Class(name(NUMBER_NAME), abstract = true, superType = any)
    val comparable = TypeRef.Local(TypeId.of(types.size.toUInt()))
    types += NominalType.Interface(name(COMPARABLE_NAME))

    fun add(
        owner: TypeRef.Local,
        method: String,
        result: ValueType,
        argument: ValueType? = null,
        abstract: Boolean = false,
        body: Body.() -> Unit = {},
    ): UInt {
        val parameters = listOf(ValueType.Ref(false, owner)) + listOfNotNull(argument)
        val signature = TypeRef.Local(TypeId.of(types.size.toUInt()))
        types += NominalType.Function(name(method), false, result, parameters)
        val id = functions.size.toUInt()
        val builder = Body(FunctionId.of(id), blocks.size.toUInt(), parameters, constants)
        if (!abstract) builder.body()
        blocks += builder.finish()
        functions +=
            Function(
                owner,
                name(method),
                signature,
                if (abstract) setOf(FunctionFlag.ABSTRACT, FunctionFlag.VIRTUAL) else setOf(FunctionFlag.VIRTUAL),
                builder.values.map(FunctionValue::scalar),
                parameters.size.toUInt(),
                BlockId.of(if (abstract) 0u else builder.start),
                if (abstract) 0u else builder.count,
                0u,
                0u,
            )
        return id
    }
    for (primitive in numberPrimitives) {
        val method = "to${primitive.sourceName}"
        val id = add(number, method, primitive.scalar, abstract = true)
        exports +=
            Export(
                SymbolKind.FUNCTION,
                ExportVisibility.PUBLIC_LIBRARY,
                name("$NUMBER_NAME.$method"),
                id,
                functions[id.toInt()].signature,
            )
    }
    val comparison = add(comparable, "compareTo", ValueType.I32, ValueType.Ref(true, any), abstract = true)
    exports +=
        Export(
            SymbolKind.FUNCTION,
            ExportVisibility.PUBLIC_LIBRARY,
            name("$COMPARABLE_NAME.compareTo"),
            comparison,
            functions[comparison.toInt()].signature,
        )
    exports += Export(SymbolKind.TYPE, ExportVisibility.PUBLIC_LIBRARY, name(NUMBER_NAME), number.id.value, number)
    exports += Export(SymbolKind.TYPE, ExportVisibility.PUBLIC_LIBRARY, name(COMPARABLE_NAME), comparable.id.value, comparable)

    for (primitive in GuestPrimitive.entries) {
        val owner = TypeRef.Local(TypeId.of(primitive.boxType))
        if (primitive in numberPrimitives) {
            for (output in numberPrimitives) {
                add(owner, "to${output.sourceName}", output.scalar) {
                    val value = register(primitive.scalar)
                    emit(Instruction.FieldGet(value, register(0), FieldRef.Local(FieldId.of(primitive.boxField))))
                    ret(convert(value, primitive, output))
                }
            }
        }
        add(owner, "compareTo", ValueType.I32, ValueType.Ref(true, any)) {
            val other = register(ValueType.Ref(false, owner))
            emit(Instruction.CheckedCast(other, register(1), owner))
            val left = register(primitive.scalar)
            val right = register(primitive.scalar)
            val field = FieldRef.Local(FieldId.of(primitive.boxField))
            emit(Instruction.FieldGet(left, register(0), field))
            emit(Instruction.FieldGet(right, other, field))
            compare(primitive, left, right)
        }
        val klass = types[primitive.boxType.toInt()] as NominalType.Class
        types[primitive.boxType.toInt()] =
            klass.copy(
                superType = if (primitive in numberPrimitives) number else any,
                interfaces = klass.interfaces + comparable,
            )
    }
    val string = TypeRef.Local(TypeId.of(1u))
    add(string, "compareTo", ValueType.I32, ValueType.Ref(true, any)) {
        val other = register(ValueType.Ref(false, string))
        emit(Instruction.CheckedCast(other, register(1), string))
        val leftLength = register(ValueType.I32)
        val rightLength = register(ValueType.I32)
        emit(Instruction.StringLength(leftLength, register(0)))
        emit(Instruction.StringLength(rightLength, other))
        val index = int(0)
        val one = int(1)
        val header = next(loop = true)
        val availableLeft = register(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.I32, availableLeft, index, leftLength))
        returnUnless(availableLeft) { subtract(leftLength, rightLength) }
        val availableRight = register(ValueType.Bool)
        emit(Instruction.Less(OrderedScalarValueType.I32, availableRight, index, rightLength))
        returnUnless(availableRight) { subtract(leftLength, rightLength) }
        val left = register(ValueType.Char)
        val right = register(ValueType.Char)
        emit(Instruction.StringGet(left, register(0), index))
        emit(Instruction.StringGet(right, other, index))
        val same = register(ValueType.Bool)
        emit(Instruction.Equal(ScalarValueType.CHAR, same, left, right))
        returnUnless(same) {
            subtract(
                convert(left, GuestPrimitive.CHAR, GuestPrimitive.INT),
                convert(right, GuestPrimitive.CHAR, GuestPrimitive.INT),
            )
        }
        emit(Instruction.Add(index, index, one))
        emit(Instruction.Jump(header))
    }
    // Enum's concrete owner remains checked by its existing source-level generic contract.
    val enum = TypeRef.Local(TypeId.of(40u))
    add(enum, "compareTo", ValueType.I32, ValueType.Ref(true, any)) {
        val other = register(ValueType.Ref(false, enum))
        emit(Instruction.CheckedCast(other, register(1), enum))
        val left = register(ValueType.I32)
        val right = register(ValueType.I32)
        val field = FieldRef.Local(FieldId.of(16u))
        emit(Instruction.FieldGet(left, register(0), field))
        emit(Instruction.FieldGet(right, other, field))
        ret(subtract(left, right))
    }
    for (id in listOf(1, 40)) {
        val klass = types[id] as NominalType.Class
        types[id] = klass.copy(interfaces = klass.interfaces + comparable)
    }

    // A nominal owner's methods form a contiguous range. Reorder once, preserving every
    // existing signature/type/field/constant identity and remapping function ownership.
    val order =
        functions.indices
            .groupBy { functions[it].owner }
            .values
            .flatten()
    val remap = order.withIndex().associate { (new, old) -> old.toUInt() to new.toUInt() }
    val reordered = order.map { functions[it] }
    val ranges = reordered.withIndex().groupBy { it.value.owner }
    val finalTypes =
        types.mapIndexed { index, type ->
            val members = ranges[TypeRef.Local(TypeId.of(index.toUInt()))].orEmpty()
            val first = members.firstOrNull()?.index?.toUInt() ?: 0u
            when (type) {
                is NominalType.Class -> {
                    type.copy(
                        methodStart = first,
                        methodCount = members.size.toUInt(),
                        initializer = type.initializer?.let { FunctionId.of(remap.getValue(it.value)) },
                    )
                }

                is NominalType.Interface -> {
                    type.copy(methodStart = first, methodCount = members.size.toUInt())
                }

                else -> {
                    type
                }
            }
        }
    val pool = ConstantPoolBuilder()
    constants.forEach(pool::intern)
    val canonicalConstants = pool.freeze().records
    val constantIds = canonicalConstants.withIndex().associate { (index, value) -> value to ConstantId.of(index.toUInt()) }
    return TemporaryRegisters.compact(
        base.copy(
            strings = strings,
            types = finalTypes,
            functions = reordered,
            blocks =
                blocks.map { block ->
                    block.copy(
                        owner = FunctionId.of(remap.getValue(block.owner.value)),
                        instructions =
                            block.instructions.map { instruction ->
                                if (instruction is Instruction.Const) {
                                    instruction.copy(
                                        constant = constantIds.getValue(constants[instruction.constant.value.toInt()]),
                                    )
                                } else {
                                    instruction
                                }
                            },
                    )
                },
            constants = canonicalConstants,
            exports =
                exports
                    .map {
                        if (it.kind == SymbolKind.FUNCTION) it.copy(localSymbol = remap.getValue(it.localSymbol)) else it
                    }.sortedWith(compareBy({ it.kind.ordinal }, { strings[it.name.value.toInt()].toString() })),
        ),
    )
}

private class Body(
    val function: FunctionId,
    val start: UInt,
    parameters: List<ValueType>,
    val constants: MutableList<Constant>,
) {
    val values = parameters.toMutableList()
    private val blocks = mutableListOf<Pair<Boolean, MutableList<Instruction>>>()
    private var current = -1
    val count: UInt get() = blocks.size.toUInt()

    fun register(type: ValueType): RegisterId {
        values += type
        return RegisterId.of(values.lastIndex.toUInt())
    }

    fun register(index: Int): RegisterId = RegisterId.of(index.toUInt())

    fun emit(instruction: Instruction) {
        if (current < 0) {
            blocks += false to mutableListOf()
            current = 0
        }
        blocks[current].second += instruction
    }

    fun next(loop: Boolean = false): BlockId {
        val id = BlockId.of(start + blocks.size.toUInt())
        emit(Instruction.Jump(id))
        blocks += loop to mutableListOf()
        current = blocks.lastIndex
        return id
    }

    fun finish(): List<Block> = blocks.map { (loop, code) -> Block(function, loop, code) }

    fun ret(value: RegisterId) = emit(Instruction.Return(Destination.Register(value)))

    fun constant(
        value: Constant,
        type: ValueType,
    ): RegisterId {
        var index = constants.indexOf(value)
        if (index < 0) {
            constants += value
            index = constants.lastIndex
        }
        return register(type).also { emit(Instruction.Const(it, ConstantId.of(index.toUInt()))) }
    }

    fun int(value: Int): RegisterId = constant(Constant.I32(value), ValueType.I32)

    fun subtract(
        left: RegisterId,
        right: RegisterId,
    ): RegisterId = register(ValueType.I32).also { emit(Instruction.Subtract(it, left, right)) }

    fun convert(
        value: RegisterId,
        source: GuestPrimitive,
        destination: GuestPrimitive,
    ): RegisterId {
        val converted =
            if (values[value.value.toInt()] == destination.scalar) {
                value
            } else {
                register(destination.scalar).also {
                    emit(Instruction.Convert(it, value, unsignedSource = source.unsigned, unsignedDestination = destination.unsigned))
                }
            }
        val bits = destination.narrowBits ?: return converted
        val shift = int(32 - bits)
        val temporary = register(ValueType.I32)
        emit(Instruction.ShiftLeft(temporary, converted, shift))
        return register(ValueType.I32).also { emit(Instruction.ShiftRight(it, temporary, shift)) }
    }

    fun returnUnless(
        condition: RegisterId,
        result: Body.() -> RegisterId,
    ) {
        val origin = current
        val yes = BlockId.of(start + blocks.size.toUInt())
        blocks += false to mutableListOf()
        current = blocks.lastIndex
        ret(result())
        val no = BlockId.of(start + blocks.size.toUInt())
        blocks += false to mutableListOf()
        current = origin
        emit(Instruction.Branch(condition, no, yes))
        current = blocks.lastIndex
    }

    fun compare(
        primitive: GuestPrimitive,
        left: RegisterId,
        right: RegisterId,
    ) {
        if (primitive == GuestPrimitive.CHAR) {
            ret(subtract(convert(left, primitive, GuestPrimitive.INT), convert(right, primitive, GuestPrimitive.INT)))
            return
        }
        val zero = int(0)
        val negative = int(-1)
        val positive = int(1)
        if (primitive == GuestPrimitive.BOOLEAN) {
            val same = register(ValueType.Bool)
            emit(Instruction.Equal(ScalarValueType.BOOL, same, left, right))
            returnUnless(same) {
                returnUnless(left) { negative }
                positive
            }
            ret(zero)
            return
        }
        if (primitive == GuestPrimitive.FLOAT || primitive == GuestPrimitive.DOUBLE) {
            val leftNumeric = register(ValueType.Bool)
            val rightNumeric = register(ValueType.Bool)
            emit(Instruction.Equal(primitive.scalarForm, leftNumeric, left, left))
            emit(Instruction.Equal(primitive.scalarForm, rightNumeric, right, right))
            returnUnless(leftNumeric) {
                returnUnless(rightNumeric) { zero }
                positive
            }
            returnUnless(rightNumeric) { negative }
        }
        val less = register(ValueType.Bool)
        val greater = register(ValueType.Bool)
        emit(Instruction.Less(primitive.orderedForm, less, left, right))
        emit(Instruction.Greater(primitive.orderedForm, greater, left, right))
        returnUnless(less) {
            returnUnless(greater) {
                if (primitive == GuestPrimitive.FLOAT || primitive == GuestPrimitive.DOUBLE) {
                    val one =
                        if (primitive == GuestPrimitive.FLOAT) {
                            constant(Constant.F32(1.0f.toBits().toUInt()), ValueType.F32)
                        } else {
                            constant(Constant.F64(1.0.toBits().toULong()), ValueType.F64)
                        }
                    val a = register(primitive.scalar)
                    val b = register(primitive.scalar)
                    emit(Instruction.Divide(primitive.scalarForm, a, one, left))
                    emit(Instruction.Divide(primitive.scalarForm, b, one, right))
                    val sameZero = register(ValueType.Bool)
                    emit(Instruction.Equal(primitive.scalarForm, sameZero, a, b))
                    returnUnless(sameZero) {
                        val smaller = register(ValueType.Bool)
                        emit(Instruction.Less(primitive.orderedForm, smaller, a, b))
                        returnUnless(smaller) { positive }
                        negative
                    }
                }
                zero
            }
            positive
        }
        ret(negative)
    }
}

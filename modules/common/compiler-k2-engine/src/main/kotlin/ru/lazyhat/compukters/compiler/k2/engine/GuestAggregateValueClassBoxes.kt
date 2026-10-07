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

import org.jetbrains.kotlin.ir.util.isNullable
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.Constant
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.Field
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.HashValueType
import ru.lazyhat.compukters.compiler.artifact.model.ImportId
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.StringValueType
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType

/** Managed boundaries share the nominal flattened layout used by direct calls. */
internal fun lowerAggregateValueClassBox(
    box: GuestValueClassBox,
    functionBase: UInt,
    blockBase: UInt,
    strings: Map<String, StringId>,
    stringConstants: Map<String, ConstantId>,
    any: TypeRef,
    string: ValueType.Ref,
    guestTypes: GuestTypeRegistry,
    constants: Map<Constant, ConstantId>,
): GuestValueClassBoxArtifacts {
    val owner = box.type as TypeRef.Local
    val receiver = ValueType.Ref(false, owner)
    val types =
        mutableListOf<NominalType>(
            NominalType.Class(
                requireNotNull(strings[box.name]),
                final = true,
                superType = any,
                interfaces = box.interfaces,
                fieldStart = (box.field as FieldRef.Local).id.value,
                fieldCount = box.componentTypes.size.toUInt(),
                methodStart = functionBase,
                methodCount = (3 + box.bridges.size).toUInt(),
            ),
        )
    val fields = box.componentTypes.map { Field(box.type, requireNotNull(strings["<boxed-value>"]), it, mutable = true, static = false) }
    val functions = mutableListOf<Function>()
    val blocks = mutableListOf<Block>()
    val anyRef = ValueType.Ref(false, any)
    val nullableAny = ValueType.Ref(true, any)

    fun imported(id: UInt) = FunctionRef.Imported(ImportId.of(id))
    val methods = listOf("equals", "hashCode", "toString") + box.bridges.map { it.name }
    for ((methodIndex, name) in methods.withIndex()) {
        val bridge = box.bridges.getOrNull(methodIndex - 3)
        val parameters = listOf(receiver) + (bridge?.parameters ?: if (name == "equals") listOf(nullableAny) else emptyList())
        val result =
            bridge?.result ?: when (name) {
                "equals" -> ValueType.Bool
                "hashCode" -> ValueType.I32
                else -> string
            }
        val signature = TypeRef.Local(TypeId.of(owner.id.value + 1u + methodIndex.toUInt()))
        types += NominalType.Function(requireNotNull(strings[name]), bridge?.suspending == true, result, parameters)
        val id = FunctionId.of(functionBase + functions.size.toUInt())
        val start = blockBase + blocks.size.toUInt()
        val builder = AggregateBoxMethodBuilder(start, parameters)

        fun register(type: ValueType) = builder.register(type)

        fun emit(instruction: Instruction) = builder.emit(instruction)

        fun read(
            index: Int,
            from: RegisterId = RegisterId.of(0u),
        ) = register(box.componentTypes[index]).also {
            emit(Instruction.FieldGet(it, from, box.fieldAt(index)))
        }

        fun constant(
            value: Constant,
            type: ValueType,
        ) = register(type).also {
            emit(Instruction.Const(it, requireNotNull(constants[value])))
        }

        fun cast(
            value: RegisterId,
            target: ValueType.Ref,
        ) = register(target).also {
            emit(Instruction.CheckedCast(it, value, target.type))
        }

        fun readInline(): RegisterId {
            val components = box.componentTypes.indices.map { read(it) }
            return register(box.scalar).also { emit(Instruction.InlineConstruct(it, components)) }
        }

        fun nullCheck(
            value: RegisterId,
            type: ValueType.Ref,
        ): RegisterId {
            val absent = register(type.copy(nullable = true)).also { emit(Instruction.Null(it)) }
            return register(ValueType.Bool).also { emit(Instruction.RefEqual(it, value, absent)) }
        }

        fun referenceHash(
            value: RegisterId,
            type: ValueType.Ref,
        ): RegisterId {
            val hash = register(ValueType.I32)
            val join = builder.newBlock()
            if (type.nullable) {
                val absent = builder.newBlock()
                val present = builder.newBlock()
                builder.end(Instruction.Branch(nullCheck(value, type), builder.id(absent), builder.id(present)))
                builder.select(absent)
                emit(Instruction.Const(hash, requireNotNull(constants[Constant.I32(0)])))
                builder.end(Instruction.Jump(builder.id(join)))
                builder.select(present)
            }
            emit(Instruction.CallVirtual(Destination.Register(hash), imported(49u), listOf(cast(value, anyRef))))
            builder.end(Instruction.Jump(builder.id(join)))
            builder.select(join)
            return hash
        }

        fun propertyValue(
            property: Int,
            offset: Int,
        ): Triple<RegisterId, ValueType, Int> {
            val sourceType = box.propertyTypes[property]
            val nested = guestTypes.valueClassBox(sourceType)?.takeUnless { sourceType.isNullable() }
            if (nested == null) return Triple(read(offset), box.componentTypes[offset], 1)
            val type = ValueType.Ref(false, nested.type)
            val value = register(type)
            emit(Instruction.NewObject(value, nested.type))
            nested.componentTypes.indices.forEach { leaf ->
                emit(Instruction.FieldSet(value, nested.fieldAt(leaf), read(offset + leaf)))
            }
            return Triple(value, type, nested.componentTypes.size)
        }
        when {
            bridge != null -> {
                val direct = readInline()
                if (bridge.target != null) {
                    val destination = if (result == ValueType.Unit) Destination.Unit else Destination.Register(register(result))
                    emit(
                        Instruction.Call(
                            destination,
                            bridge.target,
                            listOf(direct) + bridge.parameters.indices.map { RegisterId.of(it.toUInt() + 1u) },
                        ),
                    )
                    builder.end(Instruction.Return(destination))
                } else {
                    val property = requireNotNull(bridge.propertyIndex)
                    val offset =
                        box.propertyTypes.take(property).sumOf { type ->
                            guestTypes
                                .valueClassBox(type)
                                ?.takeUnless { type.isNullable() }
                                ?.componentTypes
                                ?.size ?: 1
                        }
                    val count = if (result is ValueType.Inline) guestTypes.inlineComponents(result).size else 1
                    val components =
                        (offset until offset + count).map { index ->
                            register(box.componentTypes[index]).also {
                                emit(Instruction.InlineComponent(it, direct, index.toUShort()))
                            }
                        }
                    val output =
                        if (result is ValueType.Inline) {
                            register(result).also {
                                emit(Instruction.InlineConstruct(it, components))
                            }
                        } else {
                            components.single()
                        }
                    builder.end(Instruction.Return(Destination.Register(output)))
                }
            }

            name == "equals" -> {
                val output = constant(Constant.Bool(false), ValueType.Bool)
                val failed = builder.newBlock()
                val compare = builder.newBlock()
                val sameType = register(ValueType.Bool).also { emit(Instruction.IsType(it, RegisterId.of(1u), box.type)) }
                builder.end(Instruction.Branch(sameType, builder.id(compare), builder.id(failed)))
                builder.select(compare)
                val other = cast(RegisterId.of(1u), receiver)

                fun requireTrue(equal: RegisterId) {
                    val next = builder.newBlock()
                    builder.end(Instruction.Branch(equal, builder.id(next), builder.id(failed)))
                    builder.select(next)
                }
                for ((index, type) in box.componentTypes.withIndex()) {
                    val left = read(index)
                    val right = read(index, other)
                    val equal = register(ValueType.Bool)
                    when (type) {
                        is ValueType.Ref -> {
                            emit(Instruction.RefEqual(equal, left, right))
                            val done = builder.newBlock()
                            val different = builder.newBlock()
                            builder.end(Instruction.Branch(equal, builder.id(done), builder.id(different)))
                            builder.select(different)
                            if (type.nullable) {
                                val present = builder.newBlock()
                                builder.end(Instruction.Branch(nullCheck(left, type), builder.id(failed), builder.id(present)))
                                builder.select(present)
                            }
                            emit(
                                Instruction.CallVirtual(
                                    Destination.Register(equal),
                                    imported(50u),
                                    listOf(cast(left, anyRef), cast(right, nullableAny)),
                                ),
                            )
                            requireTrue(equal)
                            builder.end(Instruction.Jump(builder.id(done)))
                            builder.select(done)
                        }

                        ValueType.F32, ValueType.F64 -> {
                            val hashForm = if (type == ValueType.F32) HashValueType.F32 else HashValueType.F64
                            val leftHash = register(ValueType.I32).also { emit(Instruction.ValueHash(hashForm, it, left)) }
                            val rightHash = register(ValueType.I32).also { emit(Instruction.ValueHash(hashForm, it, right)) }
                            emit(Instruction.Equal(ScalarValueType.I32, equal, leftHash, rightHash))
                            requireTrue(equal)
                            if (type == ValueType.F64) {
                                emit(Instruction.Equal(ScalarValueType.F64, equal, left, right))
                                val done = builder.newBlock()
                                val nan = builder.newBlock()
                                builder.end(Instruction.Branch(equal, builder.id(done), builder.id(nan)))
                                builder.select(nan)
                                val no = constant(Constant.Bool(false), ValueType.Bool)
                                for (value in listOf(left, right)) {
                                    emit(Instruction.Equal(ScalarValueType.F64, equal, value, value))
                                    emit(Instruction.Equal(ScalarValueType.BOOL, equal, equal, no))
                                    requireTrue(equal)
                                }
                                builder.end(Instruction.Jump(builder.id(done)))
                                builder.select(done)
                            }
                        }

                        else -> {
                            val form =
                                when (type) {
                                    ValueType.I32 -> ScalarValueType.I32
                                    ValueType.I64 -> ScalarValueType.I64
                                    ValueType.Bool -> ScalarValueType.BOOL
                                    ValueType.Char -> ScalarValueType.CHAR
                                    else -> error("invalid flattened value-class leaf")
                                }
                            emit(Instruction.Equal(form, equal, left, right))
                            requireTrue(equal)
                        }
                    }
                }
                emit(Instruction.Const(output, requireNotNull(constants[Constant.Bool(true)])))
                builder.end(Instruction.Return(Destination.Register(output)))
                builder.select(failed)
                builder.end(Instruction.Return(Destination.Register(output)))
            }

            name == "hashCode" -> {
                val hash = constant(Constant.I32(0), ValueType.I32)
                val multiplier = constant(Constant.I32(31), ValueType.I32)
                var offset = 0
                box.propertyTypes.indices.forEach { index ->
                    val (value, type, count) = propertyValue(index, offset)
                    offset += count
                    val part =
                        if (type is ValueType.Ref) {
                            referenceHash(value, type)
                        } else {
                            register(ValueType.I32).also {
                                val form =
                                    when (type) {
                                        ValueType.I32 -> HashValueType.I32
                                        ValueType.I64 -> HashValueType.I64
                                        ValueType.F32 -> HashValueType.F32
                                        ValueType.F64 -> HashValueType.F64
                                        ValueType.Bool -> HashValueType.BOOL
                                        ValueType.Char -> HashValueType.CHAR
                                        else -> error("invalid flattened value-class leaf")
                                    }
                                emit(Instruction.ValueHash(form, it, value))
                            }
                        }
                    emit(Instruction.Multiply(ScalarValueType.I32, hash, hash, multiplier))
                    emit(Instruction.Add(ScalarValueType.I32, hash, hash, part))
                }
                builder.end(Instruction.Return(Destination.Register(hash)))
            }

            else -> {
                val custom = box.toStringTarget
                val output = register(string)
                if (custom != null) {
                    emit(Instruction.Call(Destination.Register(output), custom, listOf(readInline())))
                } else {
                    var offset = 0
                    box.propertyTypes.forEachIndexed { index, sourceType ->
                        val prefix =
                            register(
                                string,
                            ).also { emit(Instruction.Const(it, requireNotNull(stringConstants[box.stringParts[index]]))) }
                        if (index == 0) emit(Instruction.Move(output, prefix)) else emit(Instruction.StringConcat(output, output, prefix))
                        val (value, type, count) = propertyValue(index, offset)
                        offset += count
                        val text = register(string)
                        if (type is ValueType.Ref) {
                            if (type.nullable) {
                                val absent = builder.newBlock()
                                val present = builder.newBlock()
                                val join = builder.newBlock()
                                builder.end(Instruction.Branch(nullCheck(value, type), builder.id(absent), builder.id(present)))
                                builder.select(absent)
                                emit(Instruction.StringValueOf(StringValueType.REFERENCE, text, value))
                                builder.end(Instruction.Jump(builder.id(join)))
                                builder.select(present)
                                emit(Instruction.CallVirtual(Destination.Register(text), imported(48u), listOf(cast(value, anyRef))))
                                builder.end(Instruction.Jump(builder.id(join)))
                                builder.select(join)
                            } else {
                                emit(Instruction.CallVirtual(Destination.Register(text), imported(48u), listOf(cast(value, anyRef))))
                            }
                        } else {
                            val form =
                                GuestPrimitive.scalar(sourceType)?.stringForm ?: when (type) {
                                    ValueType.I32 -> StringValueType.I32
                                    ValueType.I64 -> StringValueType.I64
                                    ValueType.F32 -> StringValueType.F32
                                    ValueType.F64 -> StringValueType.F64
                                    ValueType.Bool -> StringValueType.BOOL
                                    ValueType.Char -> StringValueType.CHAR
                                    else -> error("invalid flattened value-class leaf")
                                }
                            emit(Instruction.StringValueOf(form, text, value))
                        }
                        emit(Instruction.StringConcat(output, output, text))
                    }
                    val suffix = register(string).also { emit(Instruction.Const(it, requireNotNull(stringConstants[")"]))) }
                    emit(Instruction.StringConcat(output, output, suffix))
                }
                builder.end(Instruction.Return(Destination.Register(output)))
            }
        }
        blocks += builder.orderedBlocks().map { Block(id, false, it) }
        functions +=
            Function(
                box.type,
                requireNotNull(strings[name]),
                signature,
                setOf(FunctionFlag.VIRTUAL) + if (bridge?.suspending == true) setOf(FunctionFlag.SUSPENDING) else emptySet(),
                builder.values.map(guestTypes::functionValue),
                parameters.size.toUInt(),
                BlockId.of(start),
                builder.blocks.size.toUInt(),
                0u,
                0u,
            )
    }
    return GuestValueClassBoxArtifacts(types, fields, functions, blocks)
}

/** Keep allocation/call safepoints explicit and each generated block bounded independently. */
private class AggregateBoxMethodBuilder(
    private val start: UInt,
    parameters: List<ValueType>,
) {
    val values = parameters.toMutableList()
    val blocks = mutableListOf(mutableListOf<Instruction>())
    private var current = 0

    fun register(type: ValueType): RegisterId = RegisterId.of(values.size.toUInt()).also { values += type }

    fun newBlock(): Int = blocks.size.also { blocks += mutableListOf<Instruction>() }

    fun id(block: Int): BlockId = BlockId.of(start + block.toUInt())

    fun select(block: Int) {
        current = block
    }

    fun end(instruction: Instruction) {
        blocks[current] += instruction
    }

    fun orderedBlocks(): List<List<Instruction>> {
        val visited = mutableSetOf<Int>()
        val active = mutableSetOf<Int>()
        val order = mutableListOf<Int>()

        fun visit(index: Int) {
            check(index !in active) { "generated value-class bridge must be acyclic" }
            if (!visited.add(index)) return
            active += index
            when (val last = blocks[index].last()) {
                is Instruction.Jump -> {
                    visit((last.target.value - start).toInt())
                }

                is Instruction.Branch -> {
                    visit((last.trueTarget.value - start).toInt())
                    visit((last.falseTarget.value - start).toInt())
                }

                else -> {
                    Unit
                }
            }
            active -= index
            order += index
        }
        visit(0)
        check(order.size == blocks.size) { "generated value-class bridge contains unreachable blocks" }
        order.reverse()
        val relocated = order.withIndex().associate { (index, old) -> id(old) to id(index) }
        return order.map { old ->
            blocks[old].map { instruction ->
                when (instruction) {
                    is Instruction.Jump -> {
                        instruction.copy(target = requireNotNull(relocated[instruction.target]))
                    }

                    is Instruction.Branch -> {
                        instruction.copy(
                            trueTarget = requireNotNull(relocated[instruction.trueTarget]),
                            falseTarget = requireNotNull(relocated[instruction.falseTarget]),
                        )
                    }

                    else -> {
                        instruction
                    }
                }
            }
        }
    }

    fun emit(instruction: Instruction) {
        blocks[current] += instruction
        val next = newBlock()
        end(Instruction.Jump(id(next)))
        current = next
    }
}

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

import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import ru.lazyhat.compukters.compiler.artifact.model.Block
import ru.lazyhat.compukters.compiler.artifact.model.BlockId
import ru.lazyhat.compukters.compiler.artifact.model.ConstantId
import ru.lazyhat.compukters.compiler.artifact.model.Destination
import ru.lazyhat.compukters.compiler.artifact.model.Field
import ru.lazyhat.compukters.compiler.artifact.model.FieldId
import ru.lazyhat.compukters.compiler.artifact.model.FieldRef
import ru.lazyhat.compukters.compiler.artifact.model.Function
import ru.lazyhat.compukters.compiler.artifact.model.FunctionFlag
import ru.lazyhat.compukters.compiler.artifact.model.FunctionId
import ru.lazyhat.compukters.compiler.artifact.model.FunctionRef
import ru.lazyhat.compukters.compiler.artifact.model.FunctionValue
import ru.lazyhat.compukters.compiler.artifact.model.HashValueType
import ru.lazyhat.compukters.compiler.artifact.model.Instruction
import ru.lazyhat.compukters.compiler.artifact.model.NominalType
import ru.lazyhat.compukters.compiler.artifact.model.RegisterId
import ru.lazyhat.compukters.compiler.artifact.model.ScalarValueType
import ru.lazyhat.compukters.compiler.artifact.model.StringId
import ru.lazyhat.compukters.compiler.artifact.model.StringValueType
import ru.lazyhat.compukters.compiler.artifact.model.TypeId
import ru.lazyhat.compukters.compiler.artifact.model.TypeRef
import ru.lazyhat.compukters.compiler.artifact.model.ValueType

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun valueClassInterfaceMethods(symbol: IrClassSymbol): List<IrSimpleFunction> =
    symbol.owner.declarations
        .flatMap { declaration ->
            when (declaration) {
                is IrSimpleFunction -> listOf(declaration)
                is IrProperty -> listOfNotNull(declaration.getter, declaration.setter)
                else -> emptyList()
            }
        }.filter { it.overriddenSymbols.isNotEmpty() && it.origin != IrDeclarationOrigin.FAKE_OVERRIDE }
        .filterNot { method ->
            val parameters = method.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }
            when (method.name.asString()) {
                "hashCode", "toString" -> {
                    parameters.isEmpty()
                }

                "equals" -> {
                    parameters.size == 1 &&
                        ((parameters.single().type as? IrSimpleType)?.classifier as? IrClassSymbol)
                            ?.owner
                            ?.fqNameWhenAvailable
                            ?.asString() == "kotlin.Any"
                }

                else -> {
                    false
                }
            }
        }

internal data class GuestValueClassBridge(
    val name: String,
    val target: FunctionRef?,
    val parameters: List<ValueType>,
    val result: ValueType,
    val suspending: Boolean,
    val propertyIndex: Int? = null,
)

/** A nominal managed wrapper; the underlying scalar remains the direct-call representation. */
internal class GuestValueClassBox(
    val name: String,
    val symbol: IrClassSymbol,
    val displayName: String,
    val propertyName: String,
    var scalar: ValueType,
) {
    lateinit var sourceType: IrType
    var propertyTypes: List<IrType> = emptyList()
    var propertyNames: List<String> = listOf(propertyName)
    var inlineType: ValueType.Inline? = null
    var componentTypes: List<ValueType> = emptyList()
    var componentSourceTypes: List<IrType> = emptyList()
    var payloadFields: List<FieldRef> = emptyList()

    fun payloadExportName(index: Int): String = if (index == 0) "$name.<boxed-value>" else "$name.<boxed-value-$index>"

    fun fieldAt(index: Int): FieldRef =
        if (payloadFields.isNotEmpty()) {
            payloadFields[index]
        } else if (index ==
            0
        ) {
            field
        } else {
            FieldRef.Local(FieldId.of((field as FieldRef.Local).id.value + index.toUInt()))
        }

    lateinit var type: TypeRef
    lateinit var field: FieldRef
    var toStringTarget: FunctionRef? = null
    var interfaces: List<TypeRef> = emptyList()
    var bridges: List<GuestValueClassBridge> = emptyList()
    val stringPrefix: String get() = "$displayName($propertyName="
    val stringParts: List<String> get() =
        propertyNames.mapIndexed { index, property ->
            if (index == 0) "$displayName($property=" else ", $property="
        } + ")"
}

internal data class GuestValueClassBoxArtifacts(
    val types: List<NominalType>,
    val fields: List<Field>,
    val functions: List<Function>,
    val blocks: List<Block>,
)

internal fun lowerValueClassBoxes(
    boxes: List<GuestValueClassBox>,
    functionBase: UInt,
    blockBase: UInt,
    strings: Map<String, StringId>,
    stringConstants: Map<String, ConstantId>,
    any: TypeRef,
    string: ValueType.Ref,
    guestTypes: GuestTypeRegistry,
    constants: Map<ru.lazyhat.compukters.compiler.artifact.model.Constant, ConstantId>,
): GuestValueClassBoxArtifacts {
    val types = mutableListOf<NominalType>()
    val fields = mutableListOf<Field>()
    val functions = mutableListOf<Function>()
    val blocks = mutableListOf<Block>()

    fun register(index: UInt) = RegisterId.of(index)
    for (box in boxes) {
        if (box.inlineType != null) {
            val lowered =
                lowerAggregateValueClassBox(
                    box,
                    functionBase + functions.size.toUInt(),
                    blockBase + blocks.size.toUInt(),
                    strings,
                    stringConstants,
                    any,
                    string,
                    guestTypes,
                    constants,
                )
            types += lowered.types
            fields += lowered.fields
            functions += lowered.functions
            blocks += lowered.blocks
            continue
        }
        val owner = box.type as TypeRef.Local
        val field = box.field as FieldRef.Local
        val receiver = ValueType.Ref(false, owner)
        val firstFunction = functionBase + functions.size.toUInt()
        types +=
            NominalType.Class(
                name = requireNotNull(strings[box.name]),
                final = true,
                superType = any,
                interfaces = box.interfaces,
                fieldStart = field.id.value,
                fieldCount = 1u,
                methodStart = firstFunction,
                methodCount = (3 + box.bridges.size).toUInt(),
            )
        fields += Field(box.type, requireNotNull(strings["<boxed-value>"]), box.scalar, mutable = true, static = false)
        for ((methodIndex, name) in listOf("equals", "hashCode", "toString").withIndex()) {
            val parameters = listOf(receiver) + if (name == "equals") listOf(ValueType.Ref(true, any)) else emptyList()
            val result =
                when (name) {
                    "equals" -> ValueType.Bool
                    "hashCode" -> ValueType.I32
                    else -> string
                }
            val signature = TypeRef.Local(TypeId.of(owner.id.value + 1u + methodIndex.toUInt()))
            types += NominalType.Function(requireNotNull(strings[name]), false, result, parameters)
            val id = FunctionId.of(functionBase + functions.size.toUInt())
            val start = blockBase + blocks.size.toUInt()
            val locals: List<ValueType>

            fun block(vararg instructions: Instruction) {
                blocks += Block(id, false, instructions.toList())
            }
            when (name) {
                "equals" -> {
                    locals = listOf(ValueType.Bool, receiver, box.scalar, box.scalar)
                    block(
                        Instruction.IsType(register(2u), register(1u), box.type),
                        Instruction.Branch(register(2u), BlockId.of(start + 1u), BlockId.of(start + 2u)),
                    )
                    block(
                        Instruction.CheckedCast(register(3u), register(1u), box.type),
                        Instruction.FieldGet(register(4u), register(0u), box.field),
                        Instruction.FieldGet(register(5u), register(3u), box.field),
                        Instruction.Equal(
                            when (box.scalar) {
                                ValueType.I32 -> ScalarValueType.I32
                                ValueType.Bool -> ScalarValueType.BOOL
                                ValueType.Char -> ScalarValueType.CHAR
                                else -> error("unsupported value class scalar")
                            },
                            register(2u),
                            register(4u),
                            register(5u),
                        ),
                        Instruction.Jump(BlockId.of(start + 2u)),
                    )
                    block(Instruction.Return(Destination.Register(register(2u))))
                }

                "hashCode" -> {
                    locals = listOf(box.scalar, ValueType.I32)
                    block(
                        Instruction.FieldGet(register(1u), register(0u), box.field),
                        Instruction.ValueHash(
                            when (box.scalar) {
                                ValueType.I32 -> HashValueType.I32
                                ValueType.Bool -> HashValueType.BOOL
                                ValueType.Char -> HashValueType.CHAR
                                else -> error("unsupported value class scalar")
                            },
                            register(2u),
                            register(1u),
                        ),
                        Instruction.Return(Destination.Register(register(2u))),
                    )
                }

                else -> {
                    val custom = box.toStringTarget
                    if (custom != null) {
                        locals = listOf(box.scalar, string)
                        block(
                            Instruction.FieldGet(register(1u), register(0u), box.field),
                            Instruction.Call(Destination.Register(register(2u)), custom, listOf(register(1u))),
                            Instruction.Return(Destination.Register(register(2u))),
                        )
                    } else {
                        locals = listOf(box.scalar, string, string, string, string, string)
                        block(Instruction.FieldGet(register(1u), register(0u), box.field), Instruction.Jump(BlockId.of(start + 1u)))
                        block(
                            Instruction.StringValueOf(
                                when (box.scalar) {
                                    ValueType.I32 -> StringValueType.I32
                                    ValueType.Bool -> StringValueType.BOOL
                                    ValueType.Char -> StringValueType.CHAR
                                    else -> error("unsupported value class scalar")
                                },
                                register(2u),
                                register(1u),
                            ),
                            Instruction.Jump(BlockId.of(start + 2u)),
                        )
                        block(
                            Instruction.Const(register(3u), requireNotNull(stringConstants[box.stringPrefix])),
                            Instruction.Jump(
                                BlockId.of(start + 3u),
                            ),
                        )
                        block(Instruction.StringConcat(register(4u), register(3u), register(2u)), Instruction.Jump(BlockId.of(start + 4u)))
                        block(
                            Instruction.Const(register(5u), requireNotNull(stringConstants[")"])),
                            Instruction.Jump(BlockId.of(start + 5u)),
                        )
                        block(
                            Instruction.StringConcat(register(6u), register(4u), register(5u)),
                            Instruction.Return(Destination.Register(register(6u))),
                        )
                    }
                }
            }
            functions +=
                Function(
                    box.type,
                    requireNotNull(strings[name]),
                    signature,
                    setOf(FunctionFlag.VIRTUAL),
                    (parameters + locals).map(FunctionValue::scalar),
                    parameters.size.toUInt(),
                    BlockId.of(start),
                    blockBase + blocks.size.toUInt() - start,
                    0u,
                    0u,
                )
        }
        box.bridges.forEachIndexed { index, bridge ->
            val parameters = listOf(receiver) + bridge.parameters
            val signature = TypeRef.Local(TypeId.of(owner.id.value + 4u + index.toUInt()))
            types += NominalType.Function(requireNotNull(strings[bridge.name]), bridge.suspending, bridge.result, parameters)
            val id = FunctionId.of(functionBase + functions.size.toUInt())
            val start = blockBase + blocks.size.toUInt()
            val scalarReceiver = register(parameters.size.toUInt())
            val resultRegister = register(parameters.size.toUInt() + 1u)
            val destination = if (bridge.result == ValueType.Unit) Destination.Unit else Destination.Register(resultRegister)
            val instructions = mutableListOf<Instruction>(Instruction.FieldGet(scalarReceiver, register(0u), box.field))
            if (bridge.target == null) {
                instructions += Instruction.Return(Destination.Register(scalarReceiver))
            } else {
                instructions +=
                    Instruction.Call(
                        destination,
                        bridge.target,
                        listOf(scalarReceiver) + bridge.parameters.indices.map { register(it.toUInt() + 1u) },
                    )
                instructions += Instruction.Return(destination)
            }
            blocks += Block(id, false, instructions)
            functions +=
                Function(
                    box.type,
                    requireNotNull(strings[bridge.name]),
                    signature,
                    setOf(FunctionFlag.VIRTUAL) + if (bridge.suspending) setOf(FunctionFlag.SUSPENDING) else emptySet(),
                    (
                        parameters + box.scalar +
                            listOfNotNull(
                                bridge.result.takeUnless {
                                    it == ValueType.Unit || bridge.target == null
                                },
                            )
                    ).map(FunctionValue::scalar),
                    parameters.size.toUInt(),
                    BlockId.of(start),
                    1u,
                    0u,
                    0u,
                )
        }
    }
    return GuestValueClassBoxArtifacts(types, fields, functions, blocks)
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addBackingField
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.Name

/** Express generated enum entries through ordinary verified Guest objects, arrays and static initialization. */
@OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)
internal object GuestEnumEntries {
    private val holderOrigin = IrDeclarationOriginImpl("COMPUKTERS_ENUM_ENTRIES_HOLDER")

    fun isHolder(declaration: IrClass): Boolean = declaration.origin == holderOrigin

    data class Generated(
        val classes: List<IrClass>,
        val functions: List<IrSimpleFunction>,
    )

    fun lower(
        classes: List<IrClass>,
        context: IrPluginContext,
        enums: List<IrClass> = classes,
    ): Generated {
        val implementation =
            classes.firstOrNull { it.fqNameWhenAvailable?.asString() == "kotlin.enums.EnumEntriesList" }
                ?: return Generated(emptyList(), emptyList())
        val holders = mutableListOf<IrClass>()
        val getters = mutableListOf<IrSimpleFunction>()
        for (enum in enums.filter { it.kind == ClassKind.ENUM_CLASS }) {
            val getter =
                enum.declarations
                    .filterIsInstance<IrProperty>()
                    .firstOrNull { it.name.asString() == "entries" }
                    ?.getter
                    ?: continue
            if (getter.body is org.jetbrains.kotlin.ir.expressions.IrBlockBody) continue
            val enumType = enum.defaultType
            val holder =
                context.irFactory
                    .buildClass {
                        startOffset = enum.startOffset
                        endOffset = enum.endOffset
                        origin = holderOrigin
                        name =
                            Name.identifier(
                                generateSequence("EnumEntriesCache") { "${it}_" }
                                    .first { candidate -> enum.declarations.none { (it as? IrClass)?.name?.asString() == candidate } },
                            )
                        kind = ClassKind.OBJECT
                        visibility = DescriptorVisibilities.PRIVATE
                        modality = Modality.FINAL
                    }.apply {
                        parent = enum
                        superTypes = listOf(context.irBuiltIns.anyType)
                    }
            holder.thisReceiver =
                holder
                    .buildReceiverParameter {
                        startOffset = enum.startOffset
                        endOffset = enum.endOffset
                        name = Name.special("<this>")
                        type = holder.symbol.typeWith()
                        kind = IrParameterKind.DispatchReceiver
                    }.apply { parent = holder }
            val arrayType = context.irBuiltIns.arrayClass.typeWith(enumType)
            val constructor =
                holder.addConstructor {
                    startOffset = enum.startOffset
                    endOffset = enum.endOffset
                    isPrimary = true
                    returnType = holder.symbol.typeWith()
                    visibility = DescriptorVisibilities.PRIVATE
                }
            val builder = DeclarationIrBuilder(context, constructor.symbol, enum.startOffset, enum.endOffset)
            val array =
                builder.irCall(context.irBuiltIns.arrayOf).apply {
                    type = arrayType
                    typeArguments[0] = enumType
                    arguments[0] =
                        IrVarargImpl(
                            enum.startOffset,
                            enum.endOffset,
                            arrayType,
                            enumType,
                            enum.declarations.filterIsInstance<IrEnumEntry>().map {
                                IrGetEnumValueImpl(enum.startOffset, enum.endOffset, enumType, it.symbol)
                            },
                        )
                }
            val value =
                builder.irCallConstructor(implementation.constructors.single { it.isPrimary }.symbol, listOf(enumType)).apply {
                    type = implementation.symbol.typeWith(enumType)
                    arguments[0] = array
                }
            val property =
                holder.addProperty {
                    startOffset = enum.startOffset
                    endOffset = enum.endOffset
                    name = Name.identifier("entries")
                    visibility = DescriptorVisibilities.PRIVATE
                    isVar = false
                }
            val field =
                property
                    .addBackingField {
                        type = getter.returnType
                        visibility = DescriptorVisibilities.PRIVATE
                        isFinal = true
                    }.apply { initializer = context.irFactory.createExpressionBody(enum.startOffset, enum.endOffset, value) }
            constructor.body =
                builder.irBlockBody {
                    +IrInstanceInitializerCallImpl(enum.startOffset, enum.endOffset, holder.symbol, context.irBuiltIns.unitType)
                }
            getter.body =
                DeclarationIrBuilder(context, getter.symbol, enum.startOffset, enum.endOffset).irBlockBody {
                    +irReturn(irGetField(irGetObject(holder.symbol), field))
                }
            enum.declarations += holder
            holders += holder
            getters += getter
        }
        return Generated(holders, getters)
    }
}

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
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildReceiverParameter
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOriginImpl
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Hidden, read-only nominal projections. Each wrapper retains the concrete receiver and boxes only reads. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal object CollectionValueBridges {
    private val origin = IrDeclarationOriginImpl("COMPUKTERS_COLLECTION_VALUE_BRIDGE")
    private val protocols = setOf("ListRead", "SetRead", "MapRead", "EntryRead", "CollectionIteratorRead")

    val protocolNames: Set<String> = protocols.mapTo(linkedSetOf()) { "kotlin.collections.$it" }

    fun protocolClasses(
        context: IrPluginContext,
        classes: List<IrClass>,
    ): List<IrClass> {
        val protocols = linkedMapOf<String, IrClass>()
        val pending = ArrayDeque<IrClass>()

        fun consider(declaration: IrClass) {
            val name = declaration.fqNameWhenAvailable?.asString() ?: return
            if (name in protocolNames && protocols.putIfAbsent(name, declaration) == null) pending.addLast(declaration)
        }

        fun consider(type: IrType) {
            ((type as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.let(::consider)
        }

        // Reused dependency IR retains its original internal protocol declarations. They may
        // be unavailable through referenceClass in the next library's plugin context.
        classes.forEach { owner ->
            consider(owner)
            owner.superTypes.forEach(::consider)
            owner.declarations
                .filterIsInstance<IrSimpleFunction>()
                .filter(::isBridge)
                .forEach { consider(it.returnType) }
        }
        while (pending.isNotEmpty()) {
            val protocol = pending.removeFirst()
            protocol.superTypes.forEach(::consider)
            protocol.declarations.filterIsInstance<IrSimpleFunction>().forEach { consider(it.returnType) }
        }
        return protocolNames.mapNotNull { name -> protocols[name] ?: context.referenceClass(ClassId.topLevel(FqName(name)))?.owner }
    }

    fun isBridge(function: IrSimpleFunction): Boolean = function.origin == origin

    fun isProtocolMethod(function: IrSimpleFunction): Boolean =
        function.name.asString().startsWith("read") &&
            (function.parent as? IrClass)?.fqNameWhenAvailable?.asString() in protocolNames

    fun methodName(function: IrSimpleFunction): String? =
        if (isBridge(function) || isProtocolMethod(function)) {
            "<collection-${function.name.asString()}>"
        } else {
            null
        }

    fun lower(
        classes: List<IrClass>,
        functions: List<IrSimpleFunction>,
        context: IrPluginContext,
    ): List<IrSimpleFunction> {
        val byName = classes.associateBy { it.fqNameWhenAvailable?.asString() }
        val helpers = functions.associateBy { it.fqNameWhenAvailable?.asString() }
        // FIR/IR dependencies can be reused while compiling consecutive platform libraries.
        // Collect existing synthetic wrappers too: declaration collectors skip their undefined offsets.
        val result =
            classes.flatMap { owner -> owner.declarations.filterIsInstance<IrSimpleFunction>().filter(::isBridge) }.toMutableList()

        fun supertype(
            type: IrType,
            name: String,
            visited: Set<IrClassSymbol> = emptySet(),
        ): IrSimpleType? {
            val simple = type as? IrSimpleType ?: return null
            val declaration = (simple.classifier as? IrClassSymbol)?.owner ?: return null
            if (declaration.fqNameWhenAvailable?.asString() == "kotlin.collections.$name") return simple
            if (declaration.symbol in visited) return null
            // Local declarations may carry enclosing generic arguments in defaultType. They
            // do not form a concrete collection specialization for this bridge probe.
            if (simple.arguments.size != declaration.typeParameters.size) return null
            val substitution = IrTypeSubstitutor(declaration.typeParameters.map { it.symbol }, simple.arguments, true)
            return declaration.superTypes.firstNotNullOfOrNull {
                supertype(
                    substitution.substitute(it),
                    name,
                    visited + declaration.symbol,
                )
            }
        }
        for (owner in classes.filter { it.kind != ClassKind.INTERFACE && !it.isValue }) {
            fun add(
                protocolName: String,
                baseName: String,
                bindings: Map<String, String>,
            ) {
                val base = supertype(owner.defaultType, baseName) ?: return
                val protocol = byName["kotlin.collections.$protocolName"] ?: return
                if (protocol.defaultType in owner.superTypes) return
                owner.superTypes += protocol.defaultType
                for (method in protocol.declarations.filterIsInstance<IrSimpleFunction>().filter { it.name.asString() in bindings }) {
                    val existing =
                        owner.declarations.filterIsInstance<IrSimpleFunction>().singleOrNull {
                            isBridge(it) && it.name == method.name && it.returnType == method.returnType &&
                                it.parameters
                                    .filter { parameter ->
                                        parameter.kind == IrParameterKind.Regular
                                    }.map { parameter -> parameter.type } ==
                                method.parameters
                                    .filter { parameter ->
                                        parameter.kind == IrParameterKind.Regular
                                    }.map { parameter -> parameter.type }
                        }
                    if (existing != null) {
                        existing.overriddenSymbols += method.symbol
                        continue
                    }
                    val helperName = bindings.getValue(method.name.asString())
                    val direct = helperName.startsWith(".")
                    val interfaceCall = helperName.startsWith("@")
                    val typedBase =
                        if (interfaceCall) {
                            requireNotNull(
                                supertype(owner.defaultType, helperName.drop(1).substringBefore(':')),
                            )
                        } else {
                            base
                        }
                    val helper =
                        if (interfaceCall) {
                            val declaration = (typedBase.classifier as IrClassSymbol).owner
                            val targetName = helperName.substringAfter(':')
                            declaration.declarations
                                .flatMap {
                                    when (it) {
                                        is IrSimpleFunction -> listOf(it)
                                        is IrProperty -> listOfNotNull(it.getter)
                                        else -> emptyList()
                                    }
                                }.single { it.name.asString() == targetName }
                        } else if (direct) {
                            owner.declarations.filterIsInstance<IrSimpleFunction>().single { it.name.asString() == helperName.drop(1) }
                        } else {
                            requireNotNull(helpers["kotlin.collections.$helperName"]) { "Missing collection read helper $helperName" }
                        }
                    val bridge =
                        context.irFactory
                            .buildFun {
                                this.origin = CollectionValueBridges.origin
                                name = method.name
                                returnType = method.returnType
                                visibility = DescriptorVisibilities.PRIVATE
                                modality = if (owner.modality == Modality.FINAL) Modality.FINAL else Modality.OPEN
                            }.apply {
                                parent = owner
                                overriddenSymbols = listOf(method.symbol)
                            }
                    val receiver =
                        bridge
                            .buildReceiverParameter {
                                name = Name.special("<this>")
                                type = owner.defaultType
                                kind = IrParameterKind.DispatchReceiver
                            }.apply { parent = bridge }
                    bridge.parameters += receiver
                    method.parameters.filter { it.kind == IrParameterKind.Regular }.forEach { parameter ->
                        bridge.addValueParameter(parameter.name.asString(), parameter.type)
                    }
                    val builder = DeclarationIrBuilder(context, bridge.symbol)
                    val call =
                        builder.irCall(helper.symbol).apply {
                            type =
                                if (interfaceCall) {
                                    val declaration = (typedBase.classifier as IrClassSymbol).owner
                                    IrTypeSubstitutor(
                                        declaration.typeParameters.map { it.symbol },
                                        typedBase.arguments,
                                        true,
                                    ).substitute(helper.returnType)
                                } else {
                                    bridge.returnType
                                }
                            if (!direct && !interfaceCall) {
                                base.arguments.forEachIndexed { index, argument ->
                                    typeArguments[index] = (argument as org.jetbrains.kotlin.ir.types.IrTypeProjection).type
                                }
                            }
                            arguments[0] = builder.irGet(receiver)
                            bridge.parameters.filter { it.kind == IrParameterKind.Regular }.forEachIndexed { index, parameter ->
                                arguments[index + 1] = builder.irGet(parameter)
                            }
                        }
                    val value =
                        if (interfaceCall && method.name.asString() == "readIterator") {
                            builder.irAs(call, bridge.returnType)
                        } else if (interfaceCall && call.type != bridge.returnType) {
                            builder.irImplicitCast(call, bridge.returnType)
                        } else {
                            call
                        }
                    bridge.body = builder.irBlockBody { +irReturn(value) }
                    owner.declarations += bridge
                    result += bridge
                }
            }
            add("ListRead", "List", mapOf("readSize" to "@Collection:<get-size>", "readIterator" to "@Iterable:iterator"))
            val optimizedSet =
                owner.fqNameWhenAvailable?.asString() in
                    setOf("kotlin.collections.HashSet", "kotlin.collections.HashMapKeys", "kotlin.collections.HashMapEntries")
            add(
                "SetRead",
                "Set",
                mapOf(
                    "readSize" to "@Collection:<get-size>",
                    "readContains" to if (optimizedSet) ".containsAnyElement" else "collectionReadContains",
                ),
            )
            val optimizedMap = owner.fqNameWhenAvailable?.asString() == "kotlin.collections.HashMap"
            add(
                "MapRead",
                "Map",
                mapOf(
                    "readSize" to "@Map:<get-size>",
                    "readContainsKey" to if (optimizedMap) ".containsAnyKey" else "collectionMapReadContainsKey",
                    "readGet" to if (optimizedMap) ".getAnyKey" else "collectionMapReadGet",
                ),
            )
            add("EntryRead", "Map.Entry", mapOf("readKey" to "@Map.Entry:<get-key>", "readValue" to "@Map.Entry:<get-value>"))
            add("CollectionIteratorRead", "Iterator", mapOf("readHasNext" to "@Iterator:hasNext", "readNext" to "@Iterator:next"))
        }
        return result
    }
}

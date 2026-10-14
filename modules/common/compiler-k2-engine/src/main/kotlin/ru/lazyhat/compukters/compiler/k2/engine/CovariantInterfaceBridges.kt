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

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.types.Variance

/** Canonical nominal read views, independent of consumer demand. Storage and the source object's identity never change. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class CovariantInterfaceBridges private constructor(
    val interfaces: Map<GuestClassInstance, List<GuestClassInstance>>,
    val methods: List<GuestFunctionInstance>,
) {
    companion object {
        private fun methods(declaration: IrClass): List<IrSimpleFunction> =
            declaration.declarations.flatMap { member ->
                when (member) {
                    is IrSimpleFunction -> listOf(member)
                    is IrProperty -> listOfNotNull(member.getter, member.setter)
                    else -> emptyList()
                }
            }

        fun validate(
            sourceType: IrType,
            targetType: IrType,
            location: org.jetbrains.kotlin.ir.IrElement,
            context: IrPluginContext,
        ) {
            val target = targetType as? IrSimpleType ?: return
            val declaration = (target.classifier as? IrClassSymbol)?.owner ?: return
            if (declaration.kind != ClassKind.INTERFACE || declaration.typeParameters.none { it.variance == Variance.OUT_VARIANCE }) return
            // These trusted collection views retain their established explicit bridge contract.
            if (declaration.fqNameWhenAvailable?.asString() in setOf("kotlin.collections.List", "kotlin.collections.Collection")) return
            val targetArguments = target.arguments.map { (it as? IrTypeProjection)?.type ?: return }
            val targetInstance = GuestClassInstance(declaration, targetArguments)
            val visited = mutableSetOf<GuestClassInstance>()
            val typeSystem = IrTypeSystemContextImpl(context.irBuiltIns)

            fun visit(type: IrType) {
                val simple = type as? IrSimpleType ?: return
                val owner = (simple.classifier as? IrClassSymbol)?.owner ?: return
                val arguments = simple.arguments.map { (it as? IrTypeProjection)?.type ?: return }
                val source = GuestClassInstance(owner, arguments)
                if (!visited.add(source)) return
                // Declaration receiver types can retain the caller's substitution during a method call.
                // Only widening conversions belong to this check; receiver normalization is handled by dispatch.
                if (owner == declaration && !owner.symbol.typeWith(arguments).isSubtypeOf(targetType, typeSystem)) {
                    return
                }
                if (owner == declaration && source != targetInstance && !stableParameters(source, targetInstance)) {
                    throw UnsupportedKotlinIr(location, "covariant interface views requiring parameter adaptation are not supported")
                }
                if (owner == declaration && source != targetInstance && targetInstance !in views(source, context)) {
                    throw UnsupportedKotlinIr(
                        location,
                        "covariant interface view ${source.name} to ${targetInstance.name} is outside the supported result representations",
                    )
                }
                owner.superTypes.forEach { visit(source.substitute(it)) }
            }
            visit(sourceType)
        }

        private fun stableParameters(
            source: GuestClassInstance,
            target: GuestClassInstance,
        ): Boolean =
            methods(source.declaration).all { function ->
                function.typeParameters.isEmpty() &&
                    function.parameters.filter { it.kind != IrParameterKind.DispatchReceiver }.all {
                        source.substitute(it.type).specializationTypeIdentity() == target.substitute(it.type).specializationTypeIdentity()
                    }
            }

        private fun compatibleResults(
            source: GuestClassInstance,
            target: GuestClassInstance,
            context: IrPluginContext,
        ): Boolean {
            val typeSystem = IrTypeSystemContextImpl(context.irBuiltIns)
            val visited = mutableSetOf<Pair<String, String>>()

            fun admit(
                from: GuestClassInstance,
                to: GuestClassInstance,
                depth: Int,
            ): Boolean {
                if (depth > 64) throw UnsupportedKotlinIr(from.declaration, "covariant result adaptation exceeds depth 64")
                if (!visited.add(from.name to to.name)) return true
                if (!stableParameters(from, to)) return false
                return methods(from.declaration).all { method ->
                    val actual = from.substitute(method.returnType)
                    val expected = to.substitute(method.returnType)
                    val actualShape = actual.guestFunctionShape()
                    val expectedShape = expected.guestFunctionShape()
                    if (actualShape != expectedShape && (actualShape != null || expectedShape != null)) return@all false
                    if (!actual.isSubtypeOf(expected, typeSystem)) return@all false
                    val a = actual as? IrSimpleType
                    val e = expected as? IrSimpleType
                    val declaration = (a?.classifier as? IrClassSymbol)?.owner
                    if (a != null && e != null && a.classifier == e.classifier && declaration?.kind == ClassKind.INTERFACE) {
                        val aa = a.arguments.map { (it as? IrTypeProjection)?.type ?: return@all false }
                        val ea = e.arguments.map { (it as? IrTypeProjection)?.type ?: return@all false }
                        admit(GuestClassInstance(declaration, aa), GuestClassInstance(declaration, ea), depth + 1)
                    } else {
                        true
                    }
                }
            }
            return admit(source, target, 0)
        }

        fun views(
            source: GuestClassInstance,
            context: IrPluginContext,
            depth: Int = 0,
        ): List<GuestClassInstance> {
            val declaration = source.declaration
            if (depth > 64) throw UnsupportedKotlinIr(declaration, "covariant interface view expansion exceeds depth 64")
            if (declaration.kind != ClassKind.INTERFACE ||
                declaration.typeParameters.none { it.variance == Variance.OUT_VARIANCE }
            ) {
                return emptyList()
            }
            val typeSystem = IrTypeSystemContextImpl(context.irBuiltIns)
            val anyType = context.irBuiltIns.anyType

            fun widen(
                argument: IrType,
                depth: Int,
            ): List<IrType> {
                if (depth > 64) throw UnsupportedKotlinIr(declaration, "covariant interface view expansion exceeds depth 64")
                val result = linkedMapOf<String, IrType>()

                fun add(type: IrType) {
                    result.putIfAbsent(type.specializationTypeIdentity(), type)
                }
                add(argument)
                add(argument.makeNullable())
                add(anyType.makeNullable())
                if (!argument.isNullable()) add(anyType)
                val simple = argument.makeNotNull() as? IrSimpleType
                val owner = (simple?.classifier as? IrClassSymbol)?.owner
                if (owner != null && GuestPrimitive.scalar(argument.makeNotNull()) == null && argument.guestFunctionShape() == null) {
                    val arguments = simple.arguments.map { (it as? IrTypeProjection)?.type ?: return result.values.toList() }
                    val current = GuestClassInstance(owner, arguments)
                    for (wide in views(current, context, depth + 1)) {
                        add(
                            if (argument.isNullable()) {
                                wide.declaration.symbol
                                    .typeWith(
                                        wide.arguments,
                                    ).makeNullable()
                            } else {
                                wide.declaration.symbol.typeWith(wide.arguments)
                            },
                        )
                    }
                    // Compiler-only read adapters are not source-level covariance targets.
                    for (parent in owner.superTypes.filterNot {
                        ((it as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner?.fqNameWhenAvailable?.asString() in
                            (setOf("kotlin.Comparable", "kotlin.Number") + CollectionValueBridges.protocolNames)
                    }) {
                        for (wide in widen(current.substitute(parent), depth + 1)) {
                            add(if (argument.isNullable()) wide.makeNullable() else wide)
                        }
                    }
                }
                return result.values.toList()
            }
            var combinations = listOf(emptyList<IrType>())
            for ((index, parameter) in declaration.typeParameters.withIndex()) {
                val options =
                    if (parameter.variance ==
                        Variance.OUT_VARIANCE
                    ) {
                        widen(source.arguments[index], depth + 1)
                    } else {
                        listOf(source.arguments[index])
                    }
                if (combinations.size.toLong() * options.size >
                    1024
                ) {
                    throw UnsupportedKotlinIr(declaration, "covariant interface view expansion exceeds 1024 variants")
                }
                combinations = combinations.flatMap { prefix -> options.map { prefix + it } }
            }
            return combinations
                .map { GuestClassInstance(declaration, it) }
                .filter { target ->
                    target != source && stableParameters(source, target) && compatibleResults(source, target, context) &&
                        target.arguments.withIndex().all { (index, argument) ->
                            declaration.typeParameters[index].superTypes.all { bound ->
                                argument.isSubtypeOf(target.substitute(bound), typeSystem)
                            }
                        }
                }.sortedBy { it.name }
        }

        fun discover(
            classes: List<GuestClassInstance>,
            context: IrPluginContext,
        ): CovariantInterfaceBridges {
            val bySymbol = classes.map { it.declaration }.associateBy { it.symbol }

            fun type(instance: GuestClassInstance): IrType = instance.declaration.symbol.typeWith(instance.arguments)
            val views = classes.associateWith { source -> views(source, context) }.filterValues { it.isNotEmpty() }

            fun parents(instance: GuestClassInstance): List<GuestClassInstance> =
                instance.declaration.superTypes.mapNotNull { parent ->
                    val simple = instance.substitute(parent) as? IrSimpleType ?: return@mapNotNull null
                    val declaration = bySymbol[simple.classifier as? IrClassSymbol] ?: return@mapNotNull null
                    val arguments = simple.arguments.map { (it as? IrTypeProjection)?.type ?: return@mapNotNull null }
                    GuestClassInstance(declaration, arguments)
                }
            val generated = mutableListOf<GuestFunctionInstance>()
            for (owner in classes.filter { it.declaration.kind != ClassKind.INTERFACE }) {
                val ancestors = linkedSetOf<GuestClassInstance>()

                fun visit(current: GuestClassInstance) {
                    if (ancestors.add(current)) parents(current).forEach(::visit)
                }
                visit(owner)
                val signatures = mutableSetOf<String>()
                for (source in ancestors) {
                    for (target in views[source].orEmpty()) {
                        for (method in methods(source.declaration)) {
                            val actual = source.substitute(method.returnType)
                            val expected = target.substitute(method.returnType)
                            // Nominal reference covariance handles declared parents. Interface
                            // result types need a forwarding bridge to Any, since artifact interfaces have no class parent.
                            val actualDeclaration = ((actual as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                            val expectedDeclaration = ((expected as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                            val referenceErasure =
                                actualDeclaration?.kind == ClassKind.INTERFACE &&
                                    expectedDeclaration?.fqNameWhenAvailable?.asString() == "kotlin.Any"
                            val scalarResult =
                                GuestPrimitive.scalar(actual) != null || actual == context.irBuiltIns.unitType ||
                                    actualDeclaration?.isValue == true
                            if ((!referenceErasure && (actual.isNullable() || !scalarResult)) || actual == expected ||
                                (!expected.isNullable() && GuestPrimitive.scalar(expected) != null)
                            ) {
                                continue
                            }
                            val signature =
                                method.name.asString() + ":" + expected.specializationTypeIdentity() + ":" +
                                    method.parameters
                                        .filter {
                                            it.kind != IrParameterKind.DispatchReceiver
                                        }.joinToString { source.substitute(it.type).specializationTypeIdentity() }
                            if (!signatures.add(signature)) continue
                            // Existing trusted collection bridges remain their canonical implementation.
                            if (methods(owner.declaration).any { candidate ->
                                    CollectionReadBridges.methodName(candidate) == method.name.asString() &&
                                        owner.substitute(candidate.returnType).specializationTypeIdentity() ==
                                        expected.specializationTypeIdentity()
                                }
                            ) {
                                continue
                            }
                            val bridge = copyGuestFunction(method, emptyList(), source.declaration.typeParameters, source.arguments)
                            bridge.parent = owner.declaration
                            bridge.returnType = expected
                            // A value-class managed bridge must retain its existing box as the receiver,
                            // including when dispatch selects a default interface body that observes this.
                            bridge.parameters.first { it.kind == IrParameterKind.DispatchReceiver }.type =
                                type(if (owner.declaration.isValue) source else owner)
                            if (owner.declaration.isValue) bridge.overriddenSymbols = listOf(method.symbol)
                            bridge.body =
                                DeclarationIrBuilder(context, bridge.symbol, method.startOffset, method.endOffset).irBlockBody {
                                    val delegate =
                                        irCall(method.symbol, actual).apply {
                                            for ((index, parameter) in bridge.parameters.withIndex()) arguments[index] = irGet(parameter)
                                        }
                                    +irReturn(delegate)
                                }
                            bridge.patchDeclarationParents(owner.declaration)
                            if (generated.size >=
                                4096
                            ) {
                                throw UnsupportedKotlinIr(method, "covariant interface bridging exceeds 4096 methods")
                            }
                            generated += GuestFunctionInstance(bridge, emptyList(), owner)
                        }
                    }
                }
            }
            return CovariantInterfaceBridges(views, generated)
        }
    }
}

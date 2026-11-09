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
import org.jetbrains.kotlin.ir.builders.irAs
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
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.isSubtypeOf
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.types.Variance

/** Nominal read views between discovered concrete types. Storage and the source object's identity never change. */
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
                if (owner == declaration && source != targetInstance && !supportsView(source, targetInstance, context)) {
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
                // These results use the established collection argument/read bridges.
                if (from.declaration.fqNameWhenAvailable?.asString() in
                    setOf("kotlin.collections.List", "kotlin.collections.Collection")
                ) {
                    return true
                }
                if (!stableParameters(from, to)) return false
                return methods(from.declaration).all { method ->
                    val actual = from.substitute(method.returnType)
                    val expected = to.substitute(method.returnType)
                    val actualShape = actual.guestFunctionShape()
                    val expectedShape = expected.guestFunctionShape()
                    if (actualShape != expectedShape && (actualShape != null || expectedShape != null)) return@all false
                    if (!actual.isSubtypeOf(expected, typeSystem)) return@all false
                    if (actual == expected || (
                            primitiveSupertypeImport(actual) != null &&
                                primitiveSupertypeImport(actual) == primitiveSupertypeImport(expected)
                        )
                    ) {
                        return@all true
                    }
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

        private fun supportsView(
            source: GuestClassInstance,
            target: GuestClassInstance,
            context: IrPluginContext,
        ): Boolean {
            val declaration = source.declaration
            if (target.declaration != declaration || source == target || declaration.kind != ClassKind.INTERFACE ||
                declaration.typeParameters.none { it.variance == Variance.OUT_VARIANCE }
            ) {
                return false
            }
            val typeSystem = IrTypeSystemContextImpl(context.irBuiltIns)
            return declaration.symbol.typeWith(source.arguments).isSubtypeOf(declaration.symbol.typeWith(target.arguments), typeSystem) &&
                stableParameters(source, target) && compatibleResults(source, target, context) &&
                target.arguments.withIndex().all { (index, argument) ->
                    declaration.typeParameters[index].superTypes.all { bound ->
                        argument.isSubtypeOf(target.substitute(bound), typeSystem)
                    }
                }
        }

        fun discover(
            classes: List<GuestClassInstance>,
            context: IrPluginContext,
        ): CovariantInterfaceBridges {
            val bySymbol = classes.map { it.declaration }.associateBy { it.symbol }

            fun type(instance: GuestClassInstance): IrType = instance.declaration.symbol.typeWith(instance.arguments)
            val byDeclaration = classes.groupBy { it.declaration }
            val views =
                classes
                    .associateWith { source ->
                        byDeclaration
                            .getValue(source.declaration)
                            .filter { target ->
                                supportsView(source, target, context)
                            }.sortedBy { it.name }
                    }.filterValues { it.isNotEmpty() }

            fun parents(instance: GuestClassInstance): List<GuestClassInstance> =
                instance.declaration.superTypes.mapNotNull { parent ->
                    val simple = instance.substitute(parent) as? IrSimpleType ?: return@mapNotNull null
                    val declaration = bySymbol[simple.classifier as? IrClassSymbol] ?: return@mapNotNull null
                    val arguments = simple.arguments.map { (it as? IrTypeProjection)?.type ?: return@mapNotNull null }
                    GuestClassInstance(declaration, arguments)
                }
            val generated = mutableListOf<GuestFunctionInstance>()
            for (owner in classes.filter { it.declaration.kind != ClassKind.INTERFACE }) {
                // Comparable keeps Kotlin's source parameter type and uses one erased managed
                // invocation signature, including for custom and generic implementations.
                fun comparableArgument(
                    current: GuestClassInstance,
                    seen: MutableSet<GuestClassInstance>,
                ): IrType? {
                    if (!seen.add(current)) return null
                    for (parent in current.declaration.superTypes) {
                        val resolved = current.substitute(parent) as? IrSimpleType ?: continue
                        val declaration = (resolved.classifier as? IrClassSymbol)?.owner ?: continue
                        val arguments = resolved.arguments.mapNotNull { (it as? IrTypeProjection)?.type }
                        if (declaration.fqNameWhenAvailable?.asString() == COMPARABLE_NAME) return arguments.singleOrNull()
                        if (arguments.size == declaration.typeParameters.size) {
                            comparableArgument(GuestClassInstance(declaration, arguments), seen)?.let { return it }
                        }
                    }
                    return null
                }
                val comparedType = comparableArgument(owner, mutableSetOf())
                if (comparedType != null) {
                    val method =
                        methods(owner.declaration).firstOrNull { function ->
                            function.name.asString() == "compareTo" && function.parameters.count {
                                it.kind == IrParameterKind.Regular
                            } == 1
                        }
                    if (method != null) {
                        val bridge = copyGuestFunction(method, emptyList(), owner.declaration.typeParameters, owner.arguments)
                        bridge.parent = owner.declaration
                        bridge.parameters.first { it.kind == IrParameterKind.DispatchReceiver }.type = type(owner)
                        val parameter = bridge.parameters.single { it.kind == IrParameterKind.Regular }
                        parameter.type = context.irBuiltIns.anyNType
                        bridge.body =
                            DeclarationIrBuilder(context, bridge.symbol, method.startOffset, method.endOffset).irBlockBody {
                                val delegate =
                                    irCall(method.symbol, context.irBuiltIns.intType).apply {
                                        for ((index, argument) in bridge.parameters.withIndex()) {
                                            arguments[index] =
                                                if (argument == parameter) irAs(irGet(argument), comparedType) else irGet(argument)
                                        }
                                    }
                                +irReturn(delegate)
                            }
                        bridge.patchDeclarationParents(owner.declaration)
                        generated += GuestFunctionInstance(bridge, emptyList(), owner)
                    }
                }
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
                            // Interface results may use explicit collection views rather than nominal
                            // parent edges. Forward the original reference through the normal conversion.
                            val actualDeclaration = ((actual as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                            val expectedDeclaration = ((expected as? IrSimpleType)?.classifier as? IrClassSymbol)?.owner
                            val referenceForwarding =
                                actualDeclaration?.kind == ClassKind.INTERFACE && (
                                    expectedDeclaration?.fqNameWhenAvailable?.asString() == "kotlin.Any" ||
                                        (
                                            actualDeclaration == expectedDeclaration && actualDeclaration.fqNameWhenAvailable?.asString() in
                                                setOf("kotlin.collections.List", "kotlin.collections.Collection")
                                        )
                                )
                            val scalarResult =
                                GuestPrimitive.scalar(actual) != null || actual == context.irBuiltIns.unitType ||
                                    actualDeclaration?.isValue == true
                            if ((!referenceForwarding && (actual.isNullable() || !scalarResult)) || actual == expected ||
                                (!expected.isNullable() && GuestPrimitive.scalar(expected) != null)
                            ) {
                                continue
                            }
                            val signature =
                                method.name.asString() + ":" +
                                    (
                                        primitiveSupertypeImport(expected)?.let { "runtime:$it:${expected.isNullable()}" }
                                            ?: expected.specializationTypeIdentity()
                                    ) +
                                    ":" +
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

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

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrDeclarationReference
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid

/** The indivisible library owner: classes retain their complete fields, initialization and dispatch surface. */
internal fun IrDeclaration.topLevelOwner(): IrDeclaration? {
    var current = this
    while (current.parent is IrDeclaration) current = current.parent as IrDeclaration
    if (current.parent !is IrFile) return null
    return when (current) {
        is IrSimpleFunction -> current.correspondingPropertySymbol?.owner ?: current
        is IrField -> current.correspondingPropertySymbol?.owner ?: current
        else -> current
    }
}

/** Typed declaration dependencies shared by library partitioning and final source demand. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class GuestDeclarationGraph(
    module: IrModuleFragment,
) {
    val roots = module.files.flatMap { it.declarations }
    private val rootSet = roots.toSet()
    private val classesByName = roots.filterIsInstance<IrClass>().associateBy { it.fqNameWhenAvailable?.asString() }
    private val functionsByName = roots.filterIsInstance<IrSimpleFunction>().associateBy { it.fqNameWhenAvailable?.asString() }
    private val dependencies = linkedMapOf<IrDeclaration, Set<IrDeclaration>>()
    private val specializationRoots = linkedSetOf<IrDeclaration>()

    init {
        for (root in roots) {
            val references = linkedSetOf<IrDeclaration>()
            val visitedTypes = mutableSetOf<IrType>()
            var specializes = false

            fun reference(declaration: IrDeclaration?) {
                declaration?.topLevelOwner()?.takeIf { it in rootSet && it != root }?.let(references::add)
            }

            fun type(value: IrType) {
                if (!visitedTypes.add(value)) return
                val simple = value as? IrSimpleType ?: return
                if (simple.arguments.isNotEmpty()) specializes = true
                reference((simple.classifier as? IrClassSymbol)?.owner)
                simple.arguments.filterIsInstance<IrTypeProjection>().forEach { type(it.type) }
            }
            root.accept(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) {
                        when (element) {
                            is IrExpression -> type(element.type)
                            is IrValueDeclaration -> type(element.type)
                            is IrFunction -> type(element.returnType)
                            is IrClass -> element.superTypes.forEach(::type)
                        }
                        if (element is IrClass) {
                            if (element.kind == ClassKind.ENUM_CLASS) reference(classesByName[GuestEnumEntries.IMPLEMENTATION_NAME])
                            CollectionValueBridges.sourceDependencies(element).forEach { reference(functionsByName[it]) }
                        }
                        if (element is IrSimpleFunction && element.isInline) specializes = true
                        if (element is IrTypeParameter) {
                            specializes = true
                            element.superTypes.forEach(::type)
                        }
                        if (element is IrTypeOperatorCall) type(element.typeOperand)
                        if (element is IrMemberAccessExpression<*>) {
                            reference(element.symbol.owner as? IrDeclaration)
                            element.typeArguments.filterNotNull().forEach(::type)
                        }
                        if (element is IrDeclarationReference) reference(element.symbol.owner as? IrDeclaration)
                        if (element is IrCall) {
                            reference(element.symbol.owner.resolveFakeOverrideMaybeAbstract() ?: element.symbol.owner)
                            compilerSourceDependencies(element).forEach { reference(classesByName[it]) }
                        }
                        if (element is IrRichFunctionReference) {
                            reference(element.reflectionTargetSymbol?.owner as? IrDeclaration)
                        }
                        element.acceptChildren(this, null)
                    }
                },
                null,
            )
            dependencies[root] = references
            if (specializes) specializationRoots += root
        }
    }

    fun requiringSpecialization(admit: (IrDeclaration) -> Boolean): Set<IrDeclaration> {
        val dependents = mutableMapOf<IrDeclaration, MutableSet<IrDeclaration>>()
        dependencies.forEach { (owner, targets) ->
            targets.forEach { dependents.getOrPut(it) { linkedSetOf() } += owner }
        }
        val result = specializationRoots.filterTo(linkedSetOf(), admit)
        val pending = ArrayDeque(result)
        while (pending.isNotEmpty()) {
            dependents[pending.removeFirst()].orEmpty().forEach { if (admit(it) && result.add(it)) pending.addLast(it) }
        }
        return result
    }

    fun reachable(
        initial: Collection<IrDeclaration>,
        admit: (IrDeclaration) -> Boolean,
    ): Set<IrDeclaration> {
        val result = linkedSetOf<IrDeclaration>()
        val pending = ArrayDeque(initial)
        while (pending.isNotEmpty()) {
            val root = pending.removeFirst()
            if (!admit(root) || !result.add(root)) continue
            dependencies[root].orEmpty().forEach(pending::addLast)
        }
        return result
    }
}

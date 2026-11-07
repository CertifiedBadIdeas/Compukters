/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.inline.FunctionInlining
import org.jetbrains.kotlin.ir.inline.InlineFunctionResolver
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid

/** Optional, bounded leaf expansion. Ordinary definitions and callable references keep their identity. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal object GuestSmallFunctionInlining {
    private val primitiveOwners = GuestPrimitive.entries.mapTo(mutableSetOf()) { it.qualifiedName }

    fun lower(
        files: List<IrFile>,
        pluginContext: IrPluginContext,
        maximumBodyNodes: Int = 48,
        maximumExpansionNodes: Long = 65_536,
    ) {
        val candidates = linkedMapOf<IrFunctionSymbol, Pair<IrSimpleFunction, Int>>()
        val declarations =
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildren(this, null)
                }

                override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                    val size = bodySize(declaration, maximumBodyNodes)
                    if (size != null) candidates[declaration.symbol] = declaration to size
                    super.visitSimpleFunction(declaration)
                }
            }
        files.forEach { it.accept(declarations, null) }
        if (candidates.isEmpty()) return
        val sites = mutableMapOf<IrFunctionSymbol, Long>()
        val calls =
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildren(this, null)
                }

                override fun visitCall(expression: IrCall) {
                    if (expression.symbol in candidates) sites.merge(expression.symbol, 1L, Long::plus)
                    super.visitCall(expression)
                }
            }
        files.forEach { it.accept(calls, null) }
        var remaining = maximumExpansionNodes.coerceAtLeast(0)
        val selected = mutableMapOf<IrFunctionSymbol, IrSimpleFunction>()
        candidates.forEach { (symbol, candidate) ->
            val (function, size) = candidate
            val count = sites[symbol] ?: return@forEach
            // Charge body copies and argument/result scaffolding before optional expansion.
            val perSite = size.toLong() + function.parameters.size * 8L + 16L
            if (count <= remaining / perSite) {
                selected[symbol] = function
                remaining -= count * perSite
            }
        }
        if (selected.isEmpty()) return
        val resolver =
            object : InlineFunctionResolver() {
                override fun getFunctionDeclaration(symbol: IrFunctionSymbol): IrFunction? = selected[symbol]
            }
        val inliner = object : FunctionInlining(guestInliningContext(pluginContext) { files.first() }, resolver) {}
        // Visit each body once. Candidates cannot call other candidates, so expansion cannot recurse.
        val bodies =
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildren(this, null)
                }

                override fun visitFunction(declaration: IrFunction) {
                    declaration.parameters.forEach { parameter -> parameter.defaultValue?.let { inliner.lower(it, parameter) } }
                    declaration.body?.let { inliner.lower(it, declaration) }
                }
            }
        files.forEach { it.accept(bodies, null) }
    }

    private fun directValue(type: IrType): Boolean =
        !type.isNullable() && (
            GuestPrimitive.scalar(type) != null ||
                type.classOrNull?.owner?.let { it.isValue && it.typeParameters.isEmpty() } == true
        )

    private fun bodySize(
        function: IrSimpleFunction,
        maximum: Int,
    ): Int? {
        val owner = function.parent
        if (owner !is IrFile && !(owner is IrClass && owner.isValue && owner.typeParameters.isEmpty())) return null
        if (function.isInline || function.isSuspend || function.isFakeOverride || function.modality != Modality.FINAL ||
            function.origin != IrDeclarationOrigin.DEFINED || function.typeParameters.isNotEmpty() ||
            !directValue(function.returnType) || function.parameters.size > 4 ||
            function.parameters.any { it.defaultValue != null || !directValue(it.type) }
        ) {
            return null
        }
        val returned = (function.body as? IrBlockBody)?.statements?.singleOrNull() as? IrReturn ?: return null
        if (returned.returnTargetSymbol != function.symbol) return null
        val parameters = function.parameters.mapTo(mutableSetOf()) { it.symbol }
        var size = 0
        var valid = true
        val visitor =
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    if (!valid) return
                    if (++size > maximum || !allowed(element)) {
                        valid = false
                        return
                    }
                    element.acceptChildren(this, null)
                }

                private fun allowed(element: IrElement): Boolean =
                    when (element) {
                        is IrConst -> {
                            true
                        }

                        is IrGetValue -> {
                            element.symbol in parameters
                        }

                        is IrConstructorCall -> {
                            element.symbol.owner.parent
                                .let { it is IrClass && it.isValue && it.typeParameters.isEmpty() }
                        }

                        is IrCall -> {
                            val target = element.symbol.owner
                            val targetOwner = target.parent as? IrClass
                            val primitive = targetOwner?.fqNameWhenAvailable?.asString() in primitiveOwners
                            val getter =
                                targetOwner?.isValue == true && target.origin == IrDeclarationOrigin.DEFAULT_PROPERTY_ACCESSOR &&
                                    target.correspondingPropertySymbol?.owner?.getter === target
                            (primitive && target.isExternal) || getter
                        }

                        else -> {
                            false
                        }
                    }
            }
        returned.value.accept(visitor, null)
        return size.takeIf { valid }
    }
}

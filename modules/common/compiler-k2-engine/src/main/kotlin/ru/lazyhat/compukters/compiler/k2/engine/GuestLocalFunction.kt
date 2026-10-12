/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid

internal data class GuestLocalFunction(
    val declaration: IrSimpleFunction,
    val ordinal: Int,
    val captures: List<IrValueDeclaration>,
)

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun collectGuestLocalFunctions(roots: List<IrElement>): Map<IrSimpleFunctionSymbol, GuestLocalFunction> {
    val declarations = linkedMapOf<IrSimpleFunctionSymbol, IrSimpleFunction>()
    val collector =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildren(this, null)
            }

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                if (declaration.isGuestLocalFunction()) {
                    if (declaration.typeParameters.isNotEmpty() || declaration.isInline || declaration.isSuspend) {
                        throw UnsupportedKotlinIr(declaration, "generic, inline and suspend local functions are not supported")
                    }
                    declarations.putIfAbsent(declaration.symbol, declaration)
                }
                super.visitSimpleFunction(declaration)
            }
        }
    roots.forEach { it.accept(collector, null) }
    val owned = mutableMapOf<IrSimpleFunctionSymbol, Set<IrValueSymbol>>()
    val captures = mutableMapOf<IrSimpleFunctionSymbol, LinkedHashMap<IrValueSymbol, IrValueDeclaration>>()
    val dependencies = mutableMapOf<IrSimpleFunctionSymbol, Set<IrSimpleFunctionSymbol>>()
    declarations.forEach { (symbol, function) ->
        val localValues = mutableSetOf<IrValueSymbol>()
        function.accept(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildren(this, null)
                }

                override fun visitVariable(declaration: IrVariable) {
                    localValues += declaration.symbol
                    super.visitVariable(declaration)
                }

                override fun visitValueParameter(declaration: IrValueParameter) {
                    localValues += declaration.symbol
                    super.visitValueParameter(declaration)
                }
            },
            null,
        )
        owned[symbol] = localValues
        val free = linkedMapOf<IrValueSymbol, IrValueDeclaration>()
        val callees = linkedSetOf<IrSimpleFunctionSymbol>()

        fun capture(value: IrValueSymbol) {
            if (value !in localValues) free.putIfAbsent(value, value.owner)
        }
        function.accept(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    element.acceptChildren(this, null)
                }

                override fun visitGetValue(expression: IrGetValue) {
                    capture(expression.symbol)
                    super.visitGetValue(expression)
                }

                override fun visitSetValue(expression: IrSetValue) {
                    capture(expression.symbol)
                    super.visitSetValue(expression)
                }

                override fun visitCall(expression: IrCall) {
                    if (expression.symbol in declarations) callees += expression.symbol
                    super.visitCall(expression)
                }

                override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
                    (expression.reflectionTargetSymbol as? IrSimpleFunctionSymbol)?.takeIf { it in declarations }?.let { callees += it }
                    super.visitRichFunctionReference(expression)
                }

                override fun visitFunctionReference(expression: IrFunctionReference) {
                    (expression.reflectionTarget ?: expression.symbol).let { target ->
                        (target as? IrSimpleFunctionSymbol)?.takeIf { it in declarations }?.let { callees += it }
                    }
                    super.visitFunctionReference(expression)
                }
            },
            null,
        )
        captures[symbol] = free
        dependencies[symbol] = callees
    }
    // A helper needs the free values of its local callees, even if its body never reads them.
    do {
        var changed = false
        declarations.keys.forEach { symbol ->
            dependencies.getValue(symbol).forEach { callee ->
                captures.getValue(callee).values.toList().forEach { value ->
                    if (value.symbol !in owned.getValue(symbol) && captures.getValue(symbol).putIfAbsent(value.symbol, value) == null) {
                        changed = true
                    }
                }
            }
        }
    } while (changed)
    return declarations.entries
        .mapIndexed { ordinal, (symbol, function) ->
            symbol to GuestLocalFunction(function, ordinal, captures.getValue(symbol).values.toList())
        }.toMap()
}

internal fun IrSimpleFunction.isGuestLocalFunction(): Boolean =
    !name.isSpecial &&
        (origin == IrDeclarationOrigin.LOCAL_FUNCTION || (parent is IrFunction && origin == IrDeclarationOrigin.DEFINED))

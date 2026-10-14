/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrStatementOriginImpl
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.DeepCopyIrTreeWithSymbols
import org.jetbrains.kotlin.ir.util.DeepCopySymbolRemapper
import org.jetbrains.kotlin.ir.util.DeepCopyTypeRemapper
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.types.Variance

/** Keeps generic equality semantics after specialization replaces an operand's type parameter. */
internal val guestGenericEqualityOrigin = IrStatementOriginImpl("COMPUKTERS_GENERIC_EQUALITY")

/** Copy lexical symbols together, substituting only the selected owner's type parameters. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun copyGuestFunction(
    function: IrSimpleFunction,
    arguments: List<IrType>,
    ownerParameters: List<IrTypeParameter> = emptyList(),
    ownerArguments: List<IrType> = emptyList(),
): IrSimpleFunction {
    val copier =
        guestIrCopier(
            listOf(function),
            function.typeParameters + ownerParameters,
            arguments + ownerArguments,
        )
    val copy = function.transform(copier, null) as IrSimpleFunction
    copy.typeParameters = emptyList()
    if (ownerParameters.isNotEmpty() || (function.parent is IrClass && !function.isGuestLocalFunction())) {
        // A concrete executable copy retains the source member's dispatch identity.
        copy.accept(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) = element.acceptChildren(this, null)

                override fun visitCall(expression: IrCall) {
                    if (expression.symbol == copy.symbol) expression.symbol = function.symbol
                    super.visitCall(expression)
                }

                override fun visitFunctionReference(expression: IrFunctionReference) {
                    if (expression.symbol == copy.symbol) expression.symbol = function.symbol
                    super.visitFunctionReference(expression)
                }

                override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
                    if (expression.reflectionTargetSymbol == copy.symbol) expression.reflectionTargetSymbol = function.symbol
                    super.visitRichFunctionReference(expression)
                }
            },
            null,
        )
    }
    return copy
}

/** One symbol map must cover every root sharing constructor parameters or receiver captures. */
internal fun guestIrCopier(
    roots: List<IrElement>,
    parameters: List<IrTypeParameter>,
    arguments: List<IrType>,
): DeepCopyIrTreeWithSymbols {
    val symbols = DeepCopySymbolRemapper()
    roots.forEach { it.accept(symbols, null) }
    val substitution =
        IrTypeSubstitutor(
            parameters.map { it.symbol },
            arguments.map { makeTypeProjection(it, Variance.INVARIANT) },
            true,
        )
    val copiedTypes = DeepCopyTypeRemapper(symbols)
    val types =
        object : TypeRemapper {
            override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) =
                copiedTypes.enterScope(irTypeParametersContainer)

            override fun leaveScope() = copiedTypes.leaveScope()

            override fun remapType(type: IrType): IrType = copiedTypes.remapType(substitution.substitute(type))
        }
    return object : DeepCopyIrTreeWithSymbols(symbols, types) {
        override fun visitCall(expression: IrCall): IrCall {
            val copy = super.visitCall(expression)
            if (expression.symbol.owner.name
                    .asString() in setOf("EQEQ", "equals", "eqeq") &&
                expression.arguments.filterNotNull().any { (it.type as? IrSimpleType)?.classifier is IrTypeParameterSymbol }
            ) {
                copy.origin = guestGenericEqualityOrigin
            }
            return copy
        }
    }.also { copiedTypes.deepCopy = it }
}

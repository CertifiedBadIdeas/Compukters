/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.DeepCopyIrTreeWithSymbols
import org.jetbrains.kotlin.ir.util.DeepCopySymbolRemapper
import org.jetbrains.kotlin.ir.util.DeepCopyTypeRemapper
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.types.Variance

/** Copy lexical symbols together, substituting only the selected owner's type parameters. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun copyGuestFunction(
    function: IrSimpleFunction,
    arguments: List<IrType>,
): IrSimpleFunction {
    val symbols = DeepCopySymbolRemapper()
    function.accept(symbols, null)
    val substitution =
        IrTypeSubstitutor(
            function.typeParameters.map { it.symbol },
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
    val copier = DeepCopyIrTreeWithSymbols(symbols, types)
    copiedTypes.deepCopy = copier
    val copy = function.transform(copier, null) as IrSimpleFunction
    copy.typeParameters = emptyList()
    return copy
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid

internal data class GuestClassInitialization(
    val constructor: IrConstructor,
    val receiver: IrValueParameter?,
    val fields: Map<IrFieldSymbol, IrExpression>,
    val blocks: Map<IrAnonymousInitializer, IrAnonymousInitializer>,
) {
    val roots: List<IrElement> = listOf(constructor) + fields.values + blocks.values
}

/** Copy executable initialization together while retaining source class and field identities. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun copyGuestClassInitialization(
    declaration: IrClass,
    arguments: List<IrType>,
): GuestClassInitialization {
    val constructor = requireNotNull(declaration.constructors.singleOrNull { it.isPrimary })
    val receiver = declaration.thisReceiver
    val fields = declaration.declarations.filterIsInstance<IrProperty>().mapNotNull { it.backingField }
    val blocks = declaration.declarations.filterIsInstance<IrAnonymousInitializer>()
    val roots = listOfNotNull(constructor, receiver) + fields.mapNotNull { it.initializer } + blocks
    val copier = guestIrCopier(roots, declaration.typeParameters, arguments)
    val copiedConstructor = constructor.transform(copier, null) as IrConstructor
    val copiedReceiver = receiver?.transform(copier, null) as? IrValueParameter
    val copiedFields =
        fields
            .mapNotNull { field ->
                field.initializer?.expression?.let { field.symbol to (it.transform(copier, null) as IrExpression) }
            }.toMap()
    val copiedBlocks = blocks.associateWith { it.transform(copier, null) as IrAnonymousInitializer }
    val result = GuestClassInitialization(copiedConstructor, copiedReceiver, copiedFields, copiedBlocks)
    copiedReceiver?.parent = declaration
    result.roots.forEach { root ->
        root.patchDeclarationParents(declaration)
        root.accept(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) = element.acceptChildren(this, null)

                override fun visitConstructorCall(expression: IrConstructorCall) {
                    // Recursive construction still resolves through the source constructor identity.
                    if (expression.symbol == copiedConstructor.symbol) expression.symbol = constructor.symbol
                    super.visitConstructorCall(expression)
                }
            },
            null,
        )
    }
    return result
}

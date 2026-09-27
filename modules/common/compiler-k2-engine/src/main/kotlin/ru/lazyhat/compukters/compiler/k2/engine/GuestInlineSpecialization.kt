/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.DeepCopyIrTreeWithSymbols
import org.jetbrains.kotlin.ir.util.DeepCopySymbolRemapper
import org.jetbrains.kotlin.ir.util.DeepCopyTypeRemapper
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.types.Variance

/** Internal preparation for common inlining; production compilation does not invoke this pass yet. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class GuestInlineSpecialization(
    private val maximumVariants: Int = 256,
    private val maximumDepth: Int = 64,
) {
    private data class Key(
        val declaration: IrSimpleFunction,
        val arguments: List<IrType>,
    )

    private val copies = mutableMapOf<Key, IrSimpleFunction>()
    private val active = mutableSetOf<Key>()

    fun lower(file: IrFile) {
        // Snapshot: copies are appended while visiting concrete roots. Generic templates remain untouched.
        file.declarations.toList().forEach { it.accept(visitor, null) }
    }

    private val visitor =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildren(this, null)
            }

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                if (declaration.typeParameters.isEmpty()) super.visitSimpleFunction(declaration)
            }

            override fun visitCall(expression: IrCall) {
                super.visitCall(expression)
                val declaration = expression.symbol.owner
                require(active.none { copies[it] === declaration }) { "recursive generic inline specialization is unsupported" }
                if (!declaration.isInline || declaration.typeParameters.isEmpty() || declaration.body == null) return
                require(declaration.typeParameters.none { it.isReified }) { "reified inline specialization is unsupported" }
                val arguments = expression.typeArguments.map { requireNotNull(it) { "missing inline type argument" } }
                require(arguments.size == declaration.typeParameters.size && arguments.none { it.containsTypeParameter() }) {
                    "inline specialization requires concrete type arguments"
                }
                val copy = specialize(Key(declaration, arguments))
                expression.symbol = copy.symbol
                expression.typeArguments.clear()
            }
        }

    private fun specialize(key: Key): IrSimpleFunction {
        require(key !in active) { "recursive generic inline specialization is unsupported" }
        copies[key]?.let { return it }
        require(copies.size < maximumVariants) { "generic inline specialization variant limit exceeded" }
        require(active.size < maximumDepth) { "generic inline specialization depth limit exceeded" }
        val original = key.declaration
        val parent = original.parent as? IrFile
        requireNotNull(parent) { "generic inline specialization currently requires a top-level declaration" }
        val symbols = DeepCopySymbolRemapper()
        original.accept(symbols, null)
        val substitution =
            IrTypeSubstitutor(
                original.typeParameters.map { it.symbol },
                key.arguments.map { makeTypeProjection(it, Variance.INVARIANT) },
                false,
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
        val copy = original.transform(copier, null) as IrSimpleFunction
        copy.typeParameters = emptyList()
        copy.patchDeclarationParents(parent)
        copies[key] = copy
        active += key
        try {
            copy.accept(visitor, null)
        } finally {
            active -= key
        }
        parent.declarations += copy
        return copy
    }
}

private fun IrType.containsTypeParameter(): Boolean =
    this is IrSimpleType &&
        (classifier is IrTypeParameterSymbol || arguments.any { it is IrTypeProjection && it.type.containsTypeParameter() })

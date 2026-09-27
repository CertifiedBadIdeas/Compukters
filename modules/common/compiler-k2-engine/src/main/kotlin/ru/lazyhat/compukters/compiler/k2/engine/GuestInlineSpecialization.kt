/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
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

/** Typed body copies for common inlining; enclosing generic owners keep their emitter specialization. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class GuestInlineSpecialization(
    private val maximumVariants: Int = 256,
    private val maximumDepth: Int = 64,
    private val inlineTarget: (IrCall) -> IrSimpleFunction? = { call ->
        call.symbol.owner.takeIf { it.isInline && it.body != null }
    },
) {
    private data class Key(
        val declaration: IrSimpleFunction,
        val arguments: List<IrType>,
    )

    private val copies = mutableMapOf<Key, IrSimpleFunction>()
    private val active = mutableSetOf<Key>()
    private val enclosingParameters = mutableSetOf<IrTypeParameterSymbol>()

    fun lower(file: IrFile) {
        // Snapshot: copies are appended while visiting concrete roots. Generic templates remain untouched.
        file.declarations.toList().forEach { it.accept(visitor, null) }
    }

    private val visitor =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildren(this, null)
            }

            override fun visitClass(declaration: IrClass) {
                val added = declaration.typeParameters.map { it.symbol }.filter { enclosingParameters.add(it) }
                try {
                    super.visitClass(declaration)
                } finally {
                    enclosingParameters.removeAll(added.toSet())
                }
            }

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                if (declaration.isInline && declaration.typeParameters.isNotEmpty()) return
                val added = declaration.typeParameters.map { it.symbol }.filter { enclosingParameters.add(it) }
                try {
                    super.visitSimpleFunction(declaration)
                } finally {
                    enclosingParameters.removeAll(added.toSet())
                }
            }

            override fun visitCall(expression: IrCall) {
                super.visitCall(expression)
                val declaration = inlineTarget(expression) ?: return
                require(active.none { copies[it] === declaration }) { "recursive generic inline specialization is unsupported" }
                if (!declaration.isInline || declaration.typeParameters.isEmpty() || declaration.body == null) return
                if (declaration.typeParameters.any { it.isReified }) {
                    throw UnsupportedKotlinIr(
                        expression,
                        "reified inline specialization is unsupported",
                    )
                }
                val arguments = expression.typeArguments.map { it ?: throw UnsupportedKotlinIr(expression, "missing inline type argument") }
                if (arguments.size != declaration.typeParameters.size ||
                    arguments.any { it.containsUnownedTypeParameter(enclosingParameters) }
                ) {
                    throw UnsupportedKotlinIr(expression, "inline specialization requires concrete or enclosing generic type arguments")
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

private fun IrType.containsUnownedTypeParameter(owners: Set<IrTypeParameterSymbol>): Boolean =
    this is IrSimpleType &&
        (
            (classifier is IrTypeParameterSymbol && classifier !in owners) ||
                arguments.any { it is IrTypeProjection && it.type.containsUnownedTypeParameter(owners) }
        )

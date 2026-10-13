/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationContainer
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid

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
                    declaration.declarations.toList().forEach { it.accept(this, null) }
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
        val parent = original.parent
        require(parent is IrFile || (parent is IrClass && parent.kind == ClassKind.OBJECT)) {
            "generic inline specialization requires a top-level or object declaration"
        }
        val copy = copyGuestFunction(original, key.arguments)
        copy.patchDeclarationParents(parent)
        copies[key] = copy
        active += key
        try {
            copy.accept(visitor, null)
        } finally {
            active -= key
        }
        (parent as IrDeclarationContainer).declarations += copy
        return copy
    }
}

private fun IrType.containsUnownedTypeParameter(owners: Set<IrTypeParameterSymbol>): Boolean =
    this is IrSimpleType &&
        (
            (classifier is IrTypeParameterSymbol && classifier !in owners) ||
                arguments.any { it is IrTypeProjection && it.type.containsUnownedTypeParameter(owners) }
        )

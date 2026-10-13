/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.name.Name

/** Materialize lexical owners before captures and closures acquire concrete managed layouts. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class GuestLocalSpecialization {
    private data class Key(
        val function: IrSimpleFunction,
        val arguments: List<IrType>,
    )

    private val templates = linkedSetOf<IrSimpleFunction>()
    private val copies = linkedMapOf<Key, IrSimpleFunction>()
    private val origins = mutableMapOf<IrSimpleFunction, IrSimpleFunction>()
    private val visited = mutableSetOf<IrSimpleFunction>()
    val variantCount: Int get() = copies.size
    private var depth = 0

    fun lower(
        functions: List<IrSimpleFunction>,
        otherRoots: List<IrElement>,
    ): List<IrSimpleFunction> {
        functions.filter { it.parent is IrFile && it.typeParameters.isNotEmpty() }.forEach { function ->
            if (function.requiresConcreteLexicalBody()) templates += function
        }
        // A generic caller must also have a concrete lexical body before rewriting its callee.
        do {
            var changed = false
            functions.filter { it.parent is IrFile && it.typeParameters.isNotEmpty() && it !in templates }.forEach { function ->
                function.accept(
                    object : IrVisitorVoid() {
                        override fun visitElement(element: IrElement) = element.acceptChildren(this, null)

                        override fun visitCall(expression: IrCall) {
                            if (expression.symbol.owner in templates && templates.add(function)) changed = true
                            super.visitCall(expression)
                        }
                    },
                    null,
                )
            }
        } while (changed)
        functions.filter { it !in templates }.forEach { it.accept(visitor, null) }
        otherRoots.forEach { it.accept(visitor, null) }
        return functions.filter { it !in templates } + copies.values.filter { it.parent is IrFile }
    }

    private val visitor =
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) = element.acceptChildren(this, null)

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                // Generic member bodies are visited after their concrete owner copy is prepared.
                if (declaration.parent is IrClass &&
                    !declaration.isGuestLocalFunction() &&
                    (
                        declaration.typeParameters.isNotEmpty() ||
                            declaration.parameters.any {
                                it.kind == IrParameterKind.DispatchReceiver && it.type.containsTypeParameter()
                            }
                    )
                ) {
                    return
                }
                if (declaration.isGuestLocalFunction() && declaration.typeParameters.isNotEmpty()) {
                    if (declaration.isInline || declaration.isSuspend || declaration.typeParameters.any { it.isReified }) {
                        throw UnsupportedKotlinIr(declaration, "inline, suspend and reified local functions are not supported")
                    }
                    templates += declaration
                }
                if (declaration in templates || !visited.add(declaration)) return
                super.visitSimpleFunction(declaration)
            }

            override fun visitBlockBody(body: IrBlockBody) {
                super.visitBlockBody(body)
                val statements =
                    body.statements.flatMap { statement ->
                        if (statement is IrSimpleFunction && statement in templates) {
                            copies.filterKeys { it.function === statement }.values.toList()
                        } else {
                            listOf(statement)
                        }
                    }
                body.statements.clear()
                body.statements.addAll(statements)
            }

            override fun visitCall(expression: IrCall) {
                super.visitCall(expression)
                if (expression.symbol.owner in origins && expression.typeArguments.isEmpty()) return
                val target = origins[expression.symbol.owner] ?: expression.symbol.owner
                if (target in templates || (target.isGuestLocalFunction() && target.typeParameters.isNotEmpty())) {
                    templates += target
                    val arguments =
                        expression.typeArguments.map {
                            it
                                ?: throw UnsupportedKotlinIr(expression, "missing local type argument")
                        }
                    expression.symbol = specialize(target, arguments, expression).symbol
                    expression.typeArguments.clear()
                }
            }

            override fun visitContainerExpression(expression: IrContainerExpression) {
                super.visitContainerExpression(expression)
                val statements =
                    expression.statements.flatMap { statement ->
                        if (statement is IrSimpleFunction && statement in templates) {
                            copies.filterKeys { it.function === statement }.values.toList()
                        } else {
                            listOf(statement)
                        }
                    }
                expression.statements.clear()
                expression.statements.addAll(statements)
            }

            override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
                super.visitRichFunctionReference(expression)
                if (expression.reflectionTargetSymbol?.owner in templates) {
                    // Invocation is represented by the concretely rewritten adapter, never reflection.
                    expression.reflectionTargetSymbol = null
                }
            }
        }

    private fun specialize(
        function: IrSimpleFunction,
        arguments: List<IrType>,
        location: IrElement,
    ): IrSimpleFunction {
        if (function.isInline || function.isSuspend || function.typeParameters.any { it.isReified }) {
            throw UnsupportedKotlinIr(location, "inline, suspend and reified lexical specializations are not supported")
        }
        if (arguments.size != function.typeParameters.size || arguments.any { it.containsTypeParameter() }) {
            throw UnsupportedKotlinIr(location, "lexical specialization requires concrete type arguments")
        }
        val key = Key(function, arguments)
        copies[key]?.let { return it }
        if (copies.size >= 256 ||
            depth >= 64
        ) {
            throw UnsupportedKotlinIr(location, "lexical specialization exceeds its variant or depth limit")
        }
        val copy = copyGuestFunction(function, arguments)
        copy.name = Name.identifier("${function.name.asString()}\$lexical${copies.size}")
        copy.patchDeclarationParents(function.parent)
        copies[key] = copy
        origins[copy] = function
        depth++
        try {
            copy.accept(visitor, null)
        } finally {
            depth--
        }
        return copy
    }
}

internal fun IrType.containsTypeParameter(): Boolean =
    this is IrSimpleType &&
        (classifier is IrTypeParameterSymbol || arguments.any { (it as? IrTypeProjection)?.type?.containsTypeParameter() == true })

private fun IrSimpleFunction.requiresConcreteLexicalBody(): Boolean {
    var found = false
    accept(
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                // Anonymous callbacks and reference adapters need the same concrete captures
                // as named local functions, even when the owner has no named declaration.
                if (element is IrFunctionExpression || element is IrFunctionReference || element is IrRichFunctionReference) {
                    found = true
                }
                if (!found) element.acceptChildren(this, null)
            }

            override fun visitSimpleFunction(declaration: IrSimpleFunction) {
                if (declaration !== this@requiresConcreteLexicalBody && declaration.isGuestLocalFunction()) found = true
                super.visitSimpleFunction(declaration)
            }
        },
        null,
    )
    return found
}

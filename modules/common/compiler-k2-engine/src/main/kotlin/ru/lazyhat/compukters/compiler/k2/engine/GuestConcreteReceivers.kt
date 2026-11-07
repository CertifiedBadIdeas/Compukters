/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isNullable
import org.jetbrains.kotlin.ir.util.resolveFakeOverrideMaybeAbstract
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid

/** Refine immutable reference views; only proven final-class implementations become direct calls. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal object GuestConcreteReceivers {
    fun lower(files: List<IrFile>) {
        val variables = mutableSetOf<IrVariable>()
        files.forEach { file ->
            file.accept(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) = element.acceptChildren(this, null)

                    override fun visitVariable(declaration: IrVariable) {
                        if (!declaration.isVar) variables += declaration
                        super.visitVariable(declaration)
                    }
                },
                null,
            )
        }
        val facts = Proof(variables)
        val refined = mutableMapOf<IrValueSymbol, Pair<IrType, IrType>>()
        val calls = mutableListOf<Triple<IrCall, IrSimpleFunction, IrExpression>>()
        files.forEach { file ->
            file.accept(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) = element.acceptChildren(this, null)

                    override fun visitCall(expression: IrCall) {
                        super.visitCall(expression)
                        val receiver = expression.dispatchReceiver ?: return
                        if (expression.superQualifierSymbol != null || expression.typeArguments.isNotEmpty() ||
                            expression.arguments.any { it == null }
                        ) {
                            return
                        }
                        val original = expression.symbol.owner
                        if (original.name.asString() in setOf("equals", "hashCode", "toString")) return
                        val dispatch = original.resolveFakeOverrideMaybeAbstract() ?: original
                        val exact = facts.exact(receiver) ?: return
                        val instance = exact.instance() ?: return
                        if (instance.declaration.modality != Modality.FINAL) return
                        val candidates =
                            members(instance.declaration)
                                .filter { candidate ->
                                    !candidate.isFakeOverride && candidate.modality != Modality.ABSTRACT && !candidate.isSuspend &&
                                        !candidate.isInline && candidate.typeParameters.isEmpty() && candidate.symbol != original.symbol &&
                                        candidate.name == original.name && overrides(candidate, dispatch.symbol) &&
                                        sameSignature(instance, original, candidate, expression.type)
                                }.toList()
                        val target = candidates.singleOrNull() ?: return
                        var argument = receiver
                        while (argument is IrTypeOperatorCall && argument.operator == IrTypeOperator.IMPLICIT_CAST &&
                            facts.exact(argument.argument) == exact
                        ) {
                            argument = argument.argument
                        }
                        (argument as? IrGetValue)?.symbol?.let { symbol ->
                            val variable = symbol.owner as? IrVariable
                            if (variable != null && variable in variables && variable.type != exact &&
                                facts.sameView(exact, variable.type)
                            ) {
                                refined[symbol] = symbol.owner.type to exact
                            }
                        }
                        calls += Triple(expression, target, argument)
                    }
                },
                null,
            )
        }
        // Only refine aliases that feed selected calls. All proofs precede mutation of their declared views.
        refined.forEach { (symbol, types) -> symbol.owner.type = types.second }
        files.forEach { file ->
            file.accept(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) = element.acceptChildren(this, null)

                    override fun visitGetValue(expression: IrGetValue) {
                        refined[expression.symbol]?.let { (previous, exact) ->
                            if (expression.type == previous) expression.type = exact
                        }
                        super.visitGetValue(expression)
                    }
                },
                null,
            )
        }
        calls.forEach { (call, target, receiver) ->
            call.symbol = target.symbol
            call.dispatchReceiver = receiver
        }
    }

    private class Proof(
        val variables: Set<IrVariable>,
    ) {
        private val aliases = mutableMapOf<IrValueSymbol, IrType?>()

        fun exact(
            expression: IrExpression,
            depth: Int = 0,
        ): IrType? {
            if (depth > 64 || expression.type.isNullable()) return null
            val declared = expression.type.instance() ?: return null
            if (referenceClass(declared.declaration) &&
                (declared.declaration.modality == Modality.FINAL || expression is IrConstructorCall)
            ) {
                return expression.type
            }
            return when (expression) {
                is IrGetValue -> {
                    val variable = expression.symbol.owner as? IrVariable ?: return null
                    if (variable !in variables) return null
                    if (variable.symbol !in aliases) {
                        aliases[variable.symbol] = null
                        aliases[variable.symbol] =
                            variable.initializer
                                ?.let { exact(it, depth + 1) }
                                ?.takeIf { sameView(it, variable.type) }
                    }
                    aliases[variable.symbol]
                }

                is IrTypeOperatorCall -> {
                    if (expression.operator in setOf(IrTypeOperator.IMPLICIT_CAST, IrTypeOperator.CAST)) {
                        exact(expression.argument, depth + 1)?.takeIf { sameView(it, expression.type) }
                    } else {
                        null
                    }
                }

                else -> {
                    null
                }
            }
        }

        fun sameView(
            exact: IrType,
            view: IrType,
        ): Boolean {
            if (view.isNullable()) return false
            val declared = view.instance() ?: return false
            val inherited = exact.instance()?.declaringOwner(declared.declaration) ?: return false
            return inherited.arguments == declared.arguments
        }
    }

    private fun IrType.instance(): GuestClassInstance? {
        val simple = this as? IrSimpleType ?: return null
        val owner = (simple.classifier as? IrClassSymbol)?.owner ?: return null
        val arguments = simple.arguments.map { (it as? IrTypeProjection)?.type ?: return null }
        if (arguments.size != owner.typeParameters.size) return null
        return GuestClassInstance(owner, arguments)
    }

    private fun referenceClass(owner: IrClass): Boolean =
        owner.kind in setOf(ClassKind.CLASS, ClassKind.OBJECT) && !owner.isValue &&
            owner.fqNameWhenAvailable?.asString() !in nonReferenceTypes

    private val nonReferenceTypes =
        GuestPrimitive.entries.flatMap { listOf(it.qualifiedName, it.arrayName) }.toSet() + "kotlin.Array"

    private fun members(owner: IrClass): Sequence<IrSimpleFunction> =
        owner.declarations.asSequence().flatMap {
            when (it) {
                is IrSimpleFunction -> sequenceOf(it)
                is IrProperty -> sequenceOf(it.getter, it.setter).filterNotNull()
                else -> emptySequence()
            }
        }

    private fun overrides(
        function: IrSimpleFunction,
        target: IrSimpleFunctionSymbol,
    ): Boolean {
        val pending = ArrayDeque(function.overriddenSymbols)
        val seen = mutableSetOf<IrSimpleFunctionSymbol>()
        while (pending.isNotEmpty() && seen.size < 256) {
            val symbol = pending.removeFirst()
            if (symbol == target) return true
            if (seen.add(symbol)) pending.addAll(symbol.owner.overriddenSymbols)
        }
        return false
    }

    private fun sameSignature(
        receiver: GuestClassInstance,
        original: IrSimpleFunction,
        candidate: IrSimpleFunction,
        result: IrType,
    ): Boolean {
        if (original.typeParameters.isNotEmpty() || original.isSuspend ||
            candidate.parameters.any { it.kind != IrParameterKind.DispatchReceiver && it.kind != IrParameterKind.Regular } ||
            original.parameters.map { it.kind } != candidate.parameters.map { it.kind }
        ) {
            return false
        }
        val owner = (original.parent as? IrClass)?.let(receiver::declaringOwner) ?: return false
        if (receiver.substitute(candidate.returnType) != result || owner.substitute(original.returnType) != result) return false
        return original.parameters.zip(candidate.parameters).all { (source, target) ->
            source.kind == IrParameterKind.DispatchReceiver || owner.substitute(source.type) == receiver.substitute(target.type)
        }
    }
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package ru.lazyhat.compukters.compiler.k2.engine

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid

/** Preflight only: no copies or mutations. Units conservatively charge repeated bodies and callback substitution. */
@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class GuestInlineExpansionGuard(
    private val maximumWork: Long = 1_000_000,
    private val maximumDepth: Int = 64,
) {
    init {
        require(maximumWork > 0 && maximumDepth > 0)
    }

    fun verify(roots: List<IrElement>) {
        val active = mutableSetOf<IrSimpleFunction>()
        val costs = mutableMapOf<IrSimpleFunction, Long>()
        val depths = mutableMapOf<IrSimpleFunction, Int>()

        fun add(
            left: Long,
            right: Long,
        ): Long {
            require(left <= maximumWork && right <= maximumWork - left) { "inline expansion work limit exceeded" }
            return left + right
        }

        fun multiply(
            left: Long,
            right: Long,
        ): Long {
            require(right == 0L || left <= maximumWork / right) { "inline expansion work limit exceeded" }
            return left * right
        }

        var dependencyDepth = 0
        val visitor =
            object : IrVisitorVoid() {
                var work = 0L

                override fun visitElement(element: IrElement) {
                    work = add(work, 1)
                    element.acceptChildren(this, null)
                }

                override fun visitCall(expression: IrCall) {
                    val before = work
                    super.visitCall(expression)
                    val argumentWork = work - before
                    val target = expression.symbol.owner
                    if (!target.isInline || target.body == null) return
                    require(target !in active) { "recursive inline expansion is unsupported" }
                    require(active.size < maximumDepth) { "inline expansion depth limit exceeded" }
                    val bodyWork =
                        costs[target] ?: run {
                            val savedWork = work
                            val savedDepth = dependencyDepth
                            work = 0
                            dependencyDepth = 0
                            active += target
                            try {
                                target.parameters.forEach { it.defaultValue?.accept(this, null) }
                                val defaults = work
                                work = 0
                                target.body!!.accept(this, null)
                                // Defaults may themselves be callbacks substituted at many body nodes.
                                val charged = multiply(add(work, defaults), add(1, defaults))
                                costs[target] = charged
                                depths[target] = dependencyDepth + 1
                                charged
                            } finally {
                                active -= target
                                work = savedWork
                                dependencyDepth = savedDepth
                            }
                        }
                    val depth = requireNotNull(depths[target])
                    require(active.size + depth <= maximumDepth) { "inline expansion depth limit exceeded" }
                    dependencyDepth = maxOf(dependencyDepth, depth)
                    // Every callee node may substitute the entire argument tree. This deliberately overcharges
                    // noinline/stored arguments too; it is a safety score, not a prediction of final IR size.
                    work = add(work, multiply(bodyWork, add(1, argumentWork)))
                }
            }
        roots.forEach { it.accept(visitor, null) }
    }
}

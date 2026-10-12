/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.contracts

/** Opt-in marker for compile-time function contract declarations. */
@RequiresOptIn
public annotation class ExperimentalContracts

/** Compile-time effects; these interfaces are not instantiated by a contract declaration. */
@ExperimentalContracts
public interface Effect

@ExperimentalContracts
public interface ConditionalEffect : Effect

@ExperimentalContracts
public interface SimpleEffect : Effect {
    public infix fun implies(booleanExpression: Boolean): ConditionalEffect
}

@ExperimentalContracts
public interface Returns : SimpleEffect

@ExperimentalContracts
public interface ReturnsNotNull : SimpleEffect

@ExperimentalContracts
public interface CallsInPlace : Effect

/** Number of in-place calls during the owning function invocation. */
@ExperimentalContracts
public enum class InvocationKind { AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE, UNKNOWN }

/** Declaration-only DSL interpreted by K2, with no runtime builder. */
@ExperimentalContracts
public interface ContractBuilder {
    public fun returns(): Returns

    public fun returns(value: Any?): Returns

    public fun returnsNotNull(): ReturnsNotNull

    public fun <R> callsInPlace(lambda: Function<R>, kind: InvocationKind = InvocationKind.UNKNOWN): CallsInPlace
}

/** Describe effects at the beginning of a function body. The block is never executed. */
@ExperimentalContracts
public inline fun contract(builder: ContractBuilder.() -> Unit) {}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

/**
 * Read-only component view shared by concrete [Pair] specializations.
 *
 * Use [Pair.first] and [Pair.second] when their concrete types are known. This view
 * exposes both components as nullable [Any] values for component-wise comparison.
 */
public sealed interface PairComponents {
    /**
     * First component exposed as a nullable [Any] value.
     */
    public val firstComponent: Any?
    /**
     * Second component exposed as a nullable [Any] value.
     */
    public val secondComponent: Any?
}

/**
 * Stores two typed values with component-wise equality and positional destructuring.
 *
 * The component references cannot be replaced, but objects stored in them may still be
 * mutable. Construction and [copy] retain those values without making deep copies.
 * Both component types may be nullable. Guest Pair type parameters are invariant.
 *
 * ```kotlin
 * val entry = "name" to 42
 * val (name, value) = entry
 * ```
 *
 * @param A type of the first component.
 * @param B type of the second component.
 * @param first first component value.
 * @param second second component value.
 * @constructor Creates a pair containing the supplied values.
 * @see to
 */
public class Pair<A, B>(
    /**
     * First component, retaining its declared type and value.
     */
    public val first: A,
    /**
     * Second component, retaining its declared type and value.
     */
    public val second: B,
) : PairComponents {
    /**
     * First component exposed as a nullable [Any] value.
     */
    public override val firstComponent: Any? get() = first
    /**
     * Second component exposed as a nullable [Any] value.
     */
    public override val secondComponent: Any? get() = second

    /**
     * Returns the first component for positional destructuring.
     *
     * @return the same value as [first].
     */
    public operator fun component1(): A = first

    /**
     * Returns the second component for positional destructuring.
     *
     * @return the same value as [second].
     */
    public operator fun component2(): B = second

    /**
     * Creates a new pair using both explicitly supplied component values.
     *
     * Neither argument has a default. To retain a component, pass [first] or [second]
     * explicitly. Stored objects are shared; this function does not clone them.
     *
     * @param first first component of the new pair.
     * @param second second component of the new pair.
     * @return a new pair containing the supplied values.
     */
    public fun copy(
        first: A,
        second: B,
    ): Pair<A, B> = Pair(first, second)

    /**
     * Compares this pair with another [PairComponents] value component by component.
     *
     * Uses `==` for both corresponding values, including nulls. The second component is
     * compared only when the first components are equal. Component comparison exceptions propagate.
     *
     * @param other value to compare against.
     * @return true when [other] is a component view with two equal corresponding values.
     */
    public override fun equals(other: Any?): Boolean =
        other is PairComponents && first == other.firstComponent && second == other.secondComponent

    /**
     * Combines the hash codes of both component values.
     *
     * Null contributes zero. The result is `31 * first.hashCode() + second.hashCode()`
     * with ordinary Int overflow. Exceptions from component hash functions propagate.
     *
     * @return the combined component hash code.
     */
    public override fun hashCode(): Int = 31 * first.hashCode() + second.hashCode()

    /**
     * Formats the components as `(first, second)` using their text conversions.
     *
     * Null components appear as `null`. Exceptions from component text conversion propagate.
     *
     * @return the pair's formatted text.
     */
    public override fun toString(): String = "($first, $second)"
}

/**
 * Creates a pair from this value and [that], retaining their concrete types.
 *
 * The receiver becomes the first component and [that] becomes the second. Values are
 * retained without cloning, including nulls for nullable component types.
 *
 * ```kotlin
 * val entry = "name" to 42
 * ```
 *
 * @param A type of the first component.
 * @param B type of the second component.
 * @receiver value to store as the first component.
 * @param that value to store as the second component.
 * @return a new pair containing this value and [that].
 * @see Pair
 */
public infix fun <A, B> A.to(that: B): Pair<A, B> = Pair(this, that)

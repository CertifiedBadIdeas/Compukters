/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

/** Read-only component view shared by concrete Pair specializations. */
public sealed interface PairComponents {
    public val firstComponent: Any?
    public val secondComponent: Any?
}

/** Two typed components, with value equality and positional destructuring. */
public class Pair<A, B>(public val first: A, public val second: B) : PairComponents {
    public override val firstComponent: Any? get() = first
    public override val secondComponent: Any? get() = second
    public operator fun component1(): A = first
    public operator fun component2(): B = second
    public fun copy(first: A, second: B): Pair<A, B> = Pair(first, second)
    public override fun equals(other: Any?): Boolean =
        other is PairComponents && first == other.firstComponent && second == other.secondComponent
    public override fun hashCode(): Int = 31 * first.hashCode() + second.hashCode()
    public override fun toString(): String = "($first, $second)"
}

/** Creates a pair, retaining both components' concrete types. */
public infix fun <A, B> A.to(that: B): Pair<A, B> = Pair(this, that)

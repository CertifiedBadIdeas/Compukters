/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

internal class ClosedBooleanRange(
    override val start: Boolean,
    override val endInclusive: Boolean,
) : ClosedRange<Boolean> {
    override fun contains(value: Boolean): Boolean = value.compareTo(start) >= 0 && value.compareTo(endInclusive) <= 0

    override fun isEmpty(): Boolean = start.compareTo(endInclusive) > 0
}

internal class OpenEndBooleanRange(
    override val start: Boolean,
    override val endExclusive: Boolean,
) : OpenEndRange<Boolean> {
    override fun contains(value: Boolean): Boolean = value.compareTo(start) >= 0 && value.compareTo(endExclusive) < 0

    override fun isEmpty(): Boolean = start.compareTo(endExclusive) >= 0
}

/**
 * Creates a Boolean range from this value up to [other], inclusive.
 *
 * Ordering is `false < true`. `false..true` contains both values.
 * The range supports membership tests and empty checks, without iteration.
 *
 * @receiver the inclusive lower bound.
 * @param other the inclusive upper bound.
 * @return a Boolean range; empty when the lower bound exceeds [other].
 */
public operator fun Boolean.rangeTo(other: Boolean): ClosedRange<Boolean> = ClosedBooleanRange(this, other)

/**
 * Creates a Boolean range from this value up to [other], exclusive.
 *
 * Ordering is `false < true`. `false..<true` contains only `false`.
 * The range supports membership tests and empty checks, without iteration.
 *
 * @receiver the inclusive lower bound.
 * @param other the exclusive upper bound.
 * @return a Boolean range; empty when the lower bound is at least [other].
 */
public operator fun Boolean.rangeUntil(other: Boolean): OpenEndRange<Boolean> = OpenEndBooleanRange(this, other)

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

import kotlin.collections.Iterable
import kotlin.collections.Iterator

/**
 * An ordered progression of [Long] values with a nonzero, signed step.
 *
 * Positive steps move upwards; negative steps move downwards. The inclusive [last]
 * is adjusted to align with [first] and [step], so it may differ from [endInclusive].
 * A progression is empty when its bounds run against the step direction.
 *
 * @constructor creates a progression with an aligned final element.
 * @param first the starting value.
 * @param endInclusive the requested inclusive bound in the direction of traversal.
 * @param step the signed difference between successive elements.
 * @throws IllegalArgumentException if [step] is zero or `Long.MIN_VALUE`.
 */
public open class LongProgression(
    /**
     * The starting value; an empty progression does not yield it.
     */
    public val first: Long,
    endInclusive: Long,
    /**
     * The signed difference between successive elements; positive for ascending traversal.
     */
    public val step: Long,
) : Iterable<Long> {
    init {
        require(step != 0L && step != Long.MIN_VALUE)
    }

    /**
     * The inclusive final element of a nonempty progression, aligned with [first] and [step].
     *
     * For an empty progression this is a bound, not an element that can be visited.
     */
    public val last: Long = signedProgressionLast(first, endInclusive, step)

    /**
     * Returns whether this progression contains no elements.
     *
     * @return `true` when the bounds run against the sign of [step].
     */
    public open fun isEmpty(): Boolean = if (step > 0L) first > last else first < last

    /**
     * Creates an independent iterator over this progression in step order.
     *
     * The iterator stops at [last] without incrementing past it. Calling `next()` after
     * exhaustion throws [NoSuchElementException].
     *
     * @return a new iterator, initially before the first element.
     */
    public override operator fun iterator(): Iterator<Long> = LongProgressionIterator(first, last, step)
}

/**
 * An ascending, inclusive range of [Long] values with step one.
 *
 * Both bounds belong to a nonempty range. The range is empty when [first] exceeds
 * [last]. Iteration visits each value in order and stops at the inclusive upper bound.
 *
 * @constructor creates a range with the specified inclusive bounds.
 * @param first the inclusive lower bound.
 * @param last the inclusive upper bound.
 */
public class LongRange(
    first: Long,
    last: Long,
) : LongProgression(first, last, 1L),
    ClosedRange<Long>,
    OpenEndRange<Long> {
    /**
     * The inclusive lower bound, equal to [first].
     */
    public override val start: Long get() = first
    /**
     * The inclusive upper bound, equal to [last].
     */
    public override val endInclusive: Long get() = last
    /**
     * The value immediately after the inclusive upper bound.
     *
     * @throws IllegalStateException if [last] is `Long.MAX_VALUE`, because the
     * exclusive upper bound cannot be represented as [Long].
     */
    public override val endExclusive: Long
        get() {
            if (last == Long.MAX_VALUE) throw IllegalStateException("Range includes the maximum value")
            return last + 1L
        }

    /**
     * Returns whether this range contains no elements.
     *
     * @return `true` when [first] exceeds [last].
     */
    public override fun isEmpty(): Boolean = first > last

    /**
     * Tests whether [value] lies between the inclusive bounds.
     *
     * @param value the value to test.
     * @return `true` when [value] is at least [start] and at most [endInclusive];
     * always `false` for an empty range.
     */
    public override operator fun contains(value: Long): Boolean = value >= first && value <= last
}

internal class LongProgressionIterator(
    first: Long,
    private val finalElement: Long,
    private val step: Long,
) : Iterator<Long> {
    private var available: Boolean = if (step > 0L) first <= finalElement else first >= finalElement
    private var nextValue: Long = first

    override fun hasNext(): Boolean = available

    override fun next(): Long {
        if (!available) throw NoSuchElementException("Progression is exhausted")
        val value = nextValue
        if (value == finalElement) available = false else nextValue = nextValue + step
        return value
    }
}

/**
 * Creates a descending progression from this value down to [other], inclusive.
 *
 * @receiver the starting value.
 * @param other the inclusive lower bound.
 * @return a progression with step minus one; empty when the start is below [other].
 */
public infix fun Long.downTo(other: Long): LongProgression = LongProgression(this, other, -1L)

/**
 * Creates a progression with the same direction and a new positive step magnitude.
 *
 * The magnitude replaces the current step; it is not multiplied by it. The final
 * element is aligned again using [first] and [last]. Empty progressions stay empty.
 *
 * @receiver the progression whose bounds and direction are retained.
 * @param step the positive magnitude of the new step.
 * @return a new progression with the original direction and requested magnitude.
 * @throws IllegalArgumentException if [step] is not positive.
 */
public infix fun LongProgression.step(step: Long): LongProgression {
    require(step > 0L)
    return LongProgression(first, last, if (this.step > 0L) step else -step)
}

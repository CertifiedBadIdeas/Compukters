/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

import kotlin.collections.Iterable
import kotlin.collections.Iterator

/**
 * An ordered progression of [UInt] values with a nonzero, signed step.
 *
 * Positive steps move upwards; negative steps move downwards. The inclusive [last]
 * is adjusted to align with [first] and [step], so it may differ from [endInclusive].
 * A progression is empty when its bounds run against the step direction.
 *
 * @constructor creates a progression with an aligned final element.
 * @param first the starting value.
 * @param endInclusive the requested inclusive bound in the direction of traversal.
 * @param step the signed difference between successive elements.
 * @throws IllegalArgumentException if [step] is zero or `Int.MIN_VALUE`.
 */
public open class UIntProgression(
    /**
     * The starting value; an empty progression does not yield it.
     */
    public val first: UInt,
    endInclusive: UInt,
    /**
     * The signed difference between successive elements; positive for ascending traversal.
     */
    public val step: Int,
) : Iterable<UInt> {
    init {
        require(step != 0 && step != Int.MIN_VALUE)
    }

    /**
     * The inclusive final element of a nonempty progression, aligned with [first] and [step].
     *
     * For an empty progression this is a bound, not an element that can be visited.
     */
    public val last: UInt =
        if (step > 0) {
            if (first >= endInclusive) endInclusive else endInclusive - (endInclusive - first) % step.toUInt()
        } else {
            if (first <= endInclusive) endInclusive else endInclusive + (first - endInclusive) % (-step).toUInt()
        }

    /**
     * Returns whether this progression contains no elements.
     *
     * @return `true` when the bounds run against the sign of [step].
     */
    public open fun isEmpty(): Boolean = if (step > 0) first > last else first < last

    /**
     * Creates an independent iterator over this progression in step order.
     *
     * The iterator stops at [last] without incrementing past it. Calling `next()` after
     * exhaustion throws [NoSuchElementException].
     *
     * @return a new iterator, initially before the first element.
     */
    public override operator fun iterator(): Iterator<UInt> = UIntProgressionIterator(first, last, step)
}

/**
 * An ascending, inclusive range of [UInt] values with step one.
 *
 * Both bounds belong to a nonempty range. The range is empty when [first] exceeds
 * [last]. Iteration visits each value in order and stops at the inclusive upper bound.
 *
 * @constructor creates a range with the specified inclusive bounds.
 * @param first the inclusive lower bound.
 * @param last the inclusive upper bound.
 */
public class UIntRange(
    first: UInt,
    last: UInt,
) : UIntProgression(first, last, 1),
    ClosedRange<UInt>,
    OpenEndRange<UInt> {
    /**
     * The inclusive lower bound, equal to [first].
     */
    public override val start: UInt get() = first
    /**
     * The inclusive upper bound, equal to [last].
     */
    public override val endInclusive: UInt get() = last
    /**
     * The value immediately after the inclusive upper bound.
     *
     * @throws IllegalStateException if [last] is `UInt.MAX_VALUE`, because the
     * exclusive upper bound cannot be represented as [UInt].
     */
    public override val endExclusive: UInt
        get() {
            if (last == UInt.MAX_VALUE) throw IllegalStateException("Range includes the maximum value")
            return last + 1u
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
    public override operator fun contains(value: UInt): Boolean = value >= first && value <= last
}

internal class UIntProgressionIterator(
    first: UInt,
    private val finalElement: UInt,
    private val step: Int,
) : Iterator<UInt> {
    private var available: Boolean = if (step > 0) first <= finalElement else first >= finalElement
    private var nextValue: UInt = first

    override fun hasNext(): Boolean = available

    override fun next(): UInt {
        if (!available) throw NoSuchElementException("Progression is exhausted")
        val value = nextValue
        if (value == finalElement) available = false else nextValue = nextValue + step.toUInt()
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
public infix fun UInt.downTo(other: UInt): UIntProgression = UIntProgression(this, other, -1)

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
public infix fun UIntProgression.step(step: Int): UIntProgression {
    require(step > 0)
    return UIntProgression(first, last, if (this.step > 0) step else -step)
}

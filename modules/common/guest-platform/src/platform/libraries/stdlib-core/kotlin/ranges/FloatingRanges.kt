/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

/**
 * A range with inclusive lower and upper bounds.
 *
 * A range describes bounds and membership; it does not by itself provide iteration.
 *
 * @param T the comparable type of the bounds and tested values.
 */
public interface ClosedRange<T : Comparable<T>> {
    /**
     * The inclusive lower bound.
     */
    public val start: T
    /**
     * The inclusive upper bound.
     */
    public val endInclusive: T

    /**
     * Tests whether [value] belongs to this range.
     *
     * @param value the value to test against both bounds.
     * @return `true` if [value] is within the inclusive bounds.
     */
    public operator fun contains(value: T): Boolean

    /**
     * Tests whether this range contains no values.
     *
     * @return `true` if no value can satisfy both bounds.
     */
    public fun isEmpty(): Boolean
}

/**
 * A range with an inclusive lower bound and an exclusive upper bound.
 *
 * A range describes bounds and membership; it does not by itself provide iteration.
 *
 * @param T the comparable type of the bounds and tested values.
 */
public interface OpenEndRange<T : Comparable<T>> {
    /**
     * The inclusive lower bound.
     */
    public val start: T
    /**
     * The exclusive upper bound.
     *
     * For discrete primitive ranges, this property throws [IllegalStateException] if
     * the inclusive upper bound is the maximum representable value.
     */
    public val endExclusive: T

    /**
     * Tests whether [value] belongs to this range.
     *
     * @param value the value to test against both bounds.
     * @return `true` if [value] is within the inclusive lower and exclusive upper bounds.
     */
    public operator fun contains(value: T): Boolean

    /**
     * Tests whether this range contains no values.
     *
     * @return `true` if no value can satisfy both bounds.
     */
    public fun isEmpty(): Boolean
}

/**
 * An inclusive range whose membership uses floating-point comparison.
 *
 * Guest Float and Double ranges use IEEE comparison: NaN is never contained, and
 * a NaN bound makes the range empty. Negative and positive zero compare as equal.
 * These ranges do not provide iteration.
 *
 * @param T the floating-point type of the bounds and tested values.
 */
public interface ClosedFloatingPointRange<T : Comparable<T>> : ClosedRange<T> {
    /**
     * Compares two values using the ordering used by floating-point range membership.
     *
     * Guest Float and Double implementations return `false` if either operand is NaN,
     * and treat negative and positive zero as equal.
     *
     * @param left the value on the left of the comparison.
     * @param right the value on the right of the comparison.
     * @return whether [left] is less than or equal to [right] under this ordering.
     */
    public fun lessThanOrEquals(
        left: T,
        right: T,
    ): Boolean
}

internal class ClosedFloatRange(
    override val start: Float,
    override val endInclusive: Float,
) : ClosedFloatingPointRange<Float> {
    override fun contains(value: Float): Boolean = value >= start && value <= endInclusive

    override fun isEmpty(): Boolean = !(start <= endInclusive)

    override fun lessThanOrEquals(
        left: Float,
        right: Float,
    ): Boolean = left <= right
}

internal class OpenEndFloatRange(
    override val start: Float,
    override val endExclusive: Float,
) : OpenEndRange<Float> {
    override fun contains(value: Float): Boolean = value >= start && value < endExclusive

    override fun isEmpty(): Boolean = !(start < endExclusive)
}

/**
 * Creates a floating-point range from this value up to [other], inclusive.
 *
 * Membership uses IEEE comparisons. NaN is never contained; a NaN bound makes
 * the range empty. Signed zeros compare as equal. The range does not support iteration.
 *
 * @receiver the inclusive lower bound.
 * @param other the inclusive upper bound.
 * @return a range of [Float] values; empty when the lower bound exceeds [other],
 * or either bound is NaN.
 */
public operator fun Float.rangeTo(other: Float): ClosedFloatingPointRange<Float> = ClosedFloatRange(this, other)

/**
 * Creates a floating-point range from this value up to [other], exclusive.
 *
 * Membership uses IEEE comparisons. NaN is never contained; a NaN bound makes
 * the range empty. Signed zeros compare as equal. The range does not support iteration.
 *
 * @receiver the inclusive lower bound.
 * @param other the exclusive upper bound.
 * @return a range of [Float] values; empty when the lower bound is at least [other],
 * or either bound is NaN.
 */
public operator fun Float.rangeUntil(other: Float): OpenEndRange<Float> = OpenEndFloatRange(this, other)

internal class ClosedDoubleRange(
    override val start: Double,
    override val endInclusive: Double,
) : ClosedFloatingPointRange<Double> {
    override fun contains(value: Double): Boolean = value >= start && value <= endInclusive

    override fun isEmpty(): Boolean = !(start <= endInclusive)

    override fun lessThanOrEquals(
        left: Double,
        right: Double,
    ): Boolean = left <= right
}

internal class OpenEndDoubleRange(
    override val start: Double,
    override val endExclusive: Double,
) : OpenEndRange<Double> {
    override fun contains(value: Double): Boolean = value >= start && value < endExclusive

    override fun isEmpty(): Boolean = !(start < endExclusive)
}

/**
 * Creates a floating-point range from this value up to [other], inclusive.
 *
 * Membership uses IEEE comparisons. NaN is never contained; a NaN bound makes
 * the range empty. Signed zeros compare as equal. The range does not support iteration.
 *
 * @receiver the inclusive lower bound.
 * @param other the inclusive upper bound.
 * @return a range of [Double] values; empty when the lower bound exceeds [other],
 * or either bound is NaN.
 */
public operator fun Double.rangeTo(other: Double): ClosedFloatingPointRange<Double> = ClosedDoubleRange(this, other)

/**
 * Creates a floating-point range from this value up to [other], exclusive.
 *
 * Membership uses IEEE comparisons. NaN is never contained; a NaN bound makes
 * the range empty. Signed zeros compare as equal. The range does not support iteration.
 *
 * @receiver the inclusive lower bound.
 * @param other the exclusive upper bound.
 * @return a range of [Double] values; empty when the lower bound is at least [other],
 * or either bound is NaN.
 */
public operator fun Double.rangeUntil(other: Double): OpenEndRange<Double> = OpenEndDoubleRange(this, other)

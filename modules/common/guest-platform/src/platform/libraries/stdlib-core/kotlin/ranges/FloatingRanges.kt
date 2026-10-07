/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

public interface ClosedRange<T : Comparable<T>> {
    public val start: T
    public val endInclusive: T
    public operator fun contains(value: T): Boolean
    public fun isEmpty(): Boolean
}

public interface OpenEndRange<T : Comparable<T>> {
    public val start: T
    public val endExclusive: T
    public operator fun contains(value: T): Boolean
    public fun isEmpty(): Boolean
}

public interface ClosedFloatingPointRange<T : Comparable<T>> : ClosedRange<T> {
    public fun lessThanOrEquals(left: T, right: T): Boolean
}

internal class ClosedFloatRange(override val start: Float, override val endInclusive: Float) : ClosedFloatingPointRange<Float> {
    override fun contains(value: Float): Boolean = value >= start && value <= endInclusive
    override fun isEmpty(): Boolean = !(start <= endInclusive)
    override fun lessThanOrEquals(left: Float, right: Float): Boolean = left <= right
}
internal class OpenEndFloatRange(override val start: Float, override val endExclusive: Float) : OpenEndRange<Float> {
    override fun contains(value: Float): Boolean = value >= start && value < endExclusive
    override fun isEmpty(): Boolean = !(start < endExclusive)
}
public operator fun Float.rangeTo(other: Float): ClosedFloatingPointRange<Float> = ClosedFloatRange(this, other)
public operator fun Float.rangeUntil(other: Float): OpenEndRange<Float> = OpenEndFloatRange(this, other)

internal class ClosedDoubleRange(override val start: Double, override val endInclusive: Double) : ClosedFloatingPointRange<Double> {
    override fun contains(value: Double): Boolean = value >= start && value <= endInclusive
    override fun isEmpty(): Boolean = !(start <= endInclusive)
    override fun lessThanOrEquals(left: Double, right: Double): Boolean = left <= right
}
internal class OpenEndDoubleRange(override val start: Double, override val endExclusive: Double) : OpenEndRange<Double> {
    override fun contains(value: Double): Boolean = value >= start && value < endExclusive
    override fun isEmpty(): Boolean = !(start < endExclusive)
}
public operator fun Double.rangeTo(other: Double): ClosedFloatingPointRange<Double> = ClosedDoubleRange(this, other)
public operator fun Double.rangeUntil(other: Double): OpenEndRange<Double> = OpenEndDoubleRange(this, other)


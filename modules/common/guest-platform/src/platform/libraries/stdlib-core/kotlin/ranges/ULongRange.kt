/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

import kotlin.collections.Iterable
import kotlin.collections.Iterator

public open class ULongProgression(
    public val first: ULong,
    endInclusive: ULong,
    public val step: Long,
) : Iterable<ULong> {
    init { require(step != 0L && step != Long.MIN_VALUE) }
    public val last: ULong = if (step > 0L) {
        if (first >= endInclusive) endInclusive else endInclusive - (endInclusive - first) % step.toULong()
    } else {
        if (first <= endInclusive) endInclusive else endInclusive + (first - endInclusive) % (-step).toULong()
    }
    public open fun isEmpty(): Boolean = if (step > 0L) first > last else first < last
    public override operator fun iterator(): Iterator<ULong> = ULongProgressionIterator(first, last, step)
}

public class ULongRange(first: ULong, last: ULong) : ULongProgression(first, last, 1L), ClosedRange<ULong>, OpenEndRange<ULong> {
    public override val start: ULong get() = first
    public override val endInclusive: ULong get() = last
    public override val endExclusive: ULong
        get() {
            if (last == ULong.MAX_VALUE) throw IllegalStateException("Range includes the maximum value")
            return last + 1uL
        }
    public override fun isEmpty(): Boolean = first > last
    public override operator fun contains(value: ULong): Boolean = value >= first && value <= last
}

internal class ULongProgressionIterator(first: ULong, private val finalElement: ULong, private val step: Long) : Iterator<ULong> {
    private var available: Boolean = if (step > 0L) first <= finalElement else first >= finalElement
    private var nextValue: ULong = first
    override fun hasNext(): Boolean = available
    override fun next(): ULong {
        if (!available) throw NoSuchElementException("Progression is exhausted")
        val value = nextValue
        if (value == finalElement) available = false else nextValue = nextValue + step.toULong()
        return value
    }
}

public infix fun ULong.downTo(other: ULong): ULongProgression = ULongProgression(this, other, -1L)
public infix fun ULongProgression.step(step: Long): ULongProgression {
    require(step > 0L)
    return ULongProgression(first, last, if (this.step > 0L) step else -step)
}

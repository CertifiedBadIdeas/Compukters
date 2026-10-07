/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

import kotlin.collections.Iterable
import kotlin.collections.Iterator

public open class LongProgression(
    public val first: Long,
    endInclusive: Long,
    public val step: Long,
) : Iterable<Long> {
    init { require(step != 0L && step != Long.MIN_VALUE) }
    public val last: Long = signedProgressionLast(first, endInclusive, step)
    public open fun isEmpty(): Boolean = if (step > 0L) first > last else first < last
    public override operator fun iterator(): Iterator<Long> = LongProgressionIterator(first, last, step)
}

public class LongRange(first: Long, last: Long) : LongProgression(first, last, 1L), ClosedRange<Long>, OpenEndRange<Long> {
    public override val start: Long get() = first
    public override val endInclusive: Long get() = last
    public override val endExclusive: Long
        get() {
            if (last == Long.MAX_VALUE) throw IllegalStateException("Range includes the maximum value")
            return last + 1L
        }
    public override fun isEmpty(): Boolean = first > last
    public override operator fun contains(value: Long): Boolean = value >= first && value <= last
}

internal class LongProgressionIterator(first: Long, private val finalElement: Long, private val step: Long) : Iterator<Long> {
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

public infix fun Long.downTo(other: Long): LongProgression = LongProgression(this, other, -1L)
public infix fun LongProgression.step(step: Long): LongProgression {
    require(step > 0L)
    return LongProgression(first, last, if (this.step > 0L) step else -step)
}

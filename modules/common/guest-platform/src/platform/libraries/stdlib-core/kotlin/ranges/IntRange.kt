/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

import kotlin.collections.Iterable
import kotlin.collections.Iterator

public open class IntProgression(
    public val first: Int,
    endInclusive: Int,
    public val step: Int,
) : Iterable<Int> {
    init { require(step != 0 && step != Int.MIN_VALUE) }
    public val last: Int = signedProgressionLast(first.toLong(), endInclusive.toLong(), step.toLong()).toInt()
    public open fun isEmpty(): Boolean = if (step > 0) first > last else first < last
    public override operator fun iterator(): Iterator<Int> = IntProgressionIterator(first, last, step)
}

public class IntRange(first: Int, last: Int) : IntProgression(first, last, 1), ClosedRange<Int>, OpenEndRange<Int> {
    public override val start: Int get() = first
    public override val endInclusive: Int get() = last
    public override val endExclusive: Int
        get() {
            if (last == Int.MAX_VALUE) throw IllegalStateException("Range includes the maximum value")
            return last + 1
        }
    public override fun isEmpty(): Boolean = first > last
    public override operator fun contains(value: Int): Boolean = value >= first && value <= last
}

internal class IntProgressionIterator(first: Int, private val finalElement: Int, private val step: Int) : Iterator<Int> {
    private var available: Boolean = if (step > 0) first <= finalElement else first >= finalElement
    private var nextValue: Int = first
    override fun hasNext(): Boolean = available
    override fun next(): Int {
        if (!available) throw NoSuchElementException("Progression is exhausted")
        val value = nextValue
        if (value == finalElement) available = false else nextValue = nextValue + step
        return value
    }
}

public infix fun Int.downTo(other: Int): IntProgression = IntProgression(this, other, -1)
public infix fun IntProgression.step(step: Int): IntProgression {
    require(step > 0)
    return IntProgression(first, last, if (this.step > 0) step else -step)
}

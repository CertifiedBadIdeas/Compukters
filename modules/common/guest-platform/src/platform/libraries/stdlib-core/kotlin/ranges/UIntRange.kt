/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

import kotlin.collections.Iterable
import kotlin.collections.Iterator

public open class UIntProgression(
    public val first: UInt,
    endInclusive: UInt,
    public val step: Int,
) : Iterable<UInt> {
    init { require(step != 0 && step != Int.MIN_VALUE) }
    public val last: UInt = if (step > 0) {
        if (first >= endInclusive) endInclusive else endInclusive - (endInclusive - first) % step.toUInt()
    } else {
        if (first <= endInclusive) endInclusive else endInclusive + (first - endInclusive) % (-step).toUInt()
    }
    public open fun isEmpty(): Boolean = if (step > 0) first > last else first < last
    public override operator fun iterator(): Iterator<UInt> = UIntProgressionIterator(first, last, step)
}

public class UIntRange(first: UInt, last: UInt) : UIntProgression(first, last, 1), ClosedRange<UInt>, OpenEndRange<UInt> {
    public override val start: UInt get() = first
    public override val endInclusive: UInt get() = last
    public override val endExclusive: UInt
        get() {
            if (last == UInt.MAX_VALUE) throw IllegalStateException("Range includes the maximum value")
            return last + 1u
        }
    public override fun isEmpty(): Boolean = first > last
    public override operator fun contains(value: UInt): Boolean = value >= first && value <= last
}

internal class UIntProgressionIterator(first: UInt, private val finalElement: UInt, private val step: Int) : Iterator<UInt> {
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

public infix fun UInt.downTo(other: UInt): UIntProgression = UIntProgression(this, other, -1)
public infix fun UIntProgression.step(step: Int): UIntProgression {
    require(step > 0)
    return UIntProgression(first, last, if (this.step > 0) step else -step)
}

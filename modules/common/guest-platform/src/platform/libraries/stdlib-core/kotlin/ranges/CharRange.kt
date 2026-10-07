/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

import kotlin.collections.Iterable
import kotlin.collections.Iterator

public open class CharProgression(
    public val first: Char,
    endInclusive: Char,
    public val step: Int,
) : Iterable<Char> {
    init { require(step != 0 && step != Int.MIN_VALUE) }
    public val last: Char = signedProgressionLast(first.code.toLong(), endInclusive.code.toLong(), step.toLong()).toInt().toChar()
    public open fun isEmpty(): Boolean = if (step > 0) first > last else first < last
    public override operator fun iterator(): Iterator<Char> = CharProgressionIterator(first, last, step)
}

public class CharRange(first: Char, last: Char) : CharProgression(first, last, 1), ClosedRange<Char>, OpenEndRange<Char> {
    public override val start: Char get() = first
    public override val endInclusive: Char get() = last
    public override val endExclusive: Char
        get() {
            if (last == Char.MAX_VALUE) throw IllegalStateException("Range includes the maximum value")
            return last + 1
        }
    public override fun isEmpty(): Boolean = first > last
    public override operator fun contains(value: Char): Boolean = value >= first && value <= last
}

internal class CharProgressionIterator(first: Char, private val finalElement: Char, private val step: Int) : Iterator<Char> {
    private var available: Boolean = if (step > 0) first <= finalElement else first >= finalElement
    private var nextValue: Char = first
    override fun hasNext(): Boolean = available
    override fun next(): Char {
        if (!available) throw NoSuchElementException("Progression is exhausted")
        val value = nextValue
        if (value == finalElement) available = false else nextValue = (nextValue.code + step).toChar()
        return value
    }
}

public infix fun Char.downTo(other: Char): CharProgression = CharProgression(this, other, -1)
public infix fun CharProgression.step(step: Int): CharProgression {
    require(step > 0)
    return CharProgression(first, last, if (this.step > 0) step else -step)
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

import kotlin.collections.IntIterator

public open class IntProgression(
    public val first: Int,
    public val last: Int,
    public val step: Int,
) {
    public external operator fun iterator(): IntIterator
}

public class IntRange(
    first: Int,
    last: Int,
) : IntProgression(first, last, 1) {
    public operator fun contains(value: Int): Boolean = value >= first && value <= last
}

public operator fun Int.rangeTo(other: Int): IntRange = IntRange(this, other)

public external infix fun Int.until(other: Int): IntRange

public external operator fun Int.rangeUntil(other: Int): IntRange

public external infix fun Int.downTo(other: Int): IntProgression

public external infix fun IntProgression.step(step: Int): IntProgression

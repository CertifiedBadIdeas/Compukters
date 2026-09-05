/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

import kotlin.collections.IntIterator

public open class IntProgression(
    public open val first: Int,
    public open val last: Int,
    public val step: Int,
) : Iterable<Int> {
    public external override operator fun iterator(): IntIterator
}

public class IntRange(
    public override val first: Int,
    public override val last: Int,
) : IntProgression(first, last, 1) {
    public operator fun contains(value: Int): Boolean = value >= first && value <= last
}

public operator fun Int.rangeTo(other: Int): IntRange = IntRange(this, other)

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

internal class ClosedBooleanRange(override val start: Boolean, override val endInclusive: Boolean) : ClosedRange<Boolean> {
    override fun contains(value: Boolean): Boolean = value.compareTo(start) >= 0 && value.compareTo(endInclusive) <= 0
    override fun isEmpty(): Boolean = start.compareTo(endInclusive) > 0
}

internal class OpenEndBooleanRange(override val start: Boolean, override val endExclusive: Boolean) : OpenEndRange<Boolean> {
    override fun contains(value: Boolean): Boolean = value.compareTo(start) >= 0 && value.compareTo(endExclusive) < 0
    override fun isEmpty(): Boolean = start.compareTo(endExclusive) >= 0
}

public operator fun Boolean.rangeTo(other: Boolean): ClosedRange<Boolean> = ClosedBooleanRange(this, other)
public operator fun Boolean.rangeUntil(other: Boolean): OpenEndRange<Boolean> = OpenEndBooleanRange(this, other)

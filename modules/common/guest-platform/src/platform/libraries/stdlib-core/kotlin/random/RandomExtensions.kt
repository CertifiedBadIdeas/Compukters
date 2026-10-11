/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.random

/** Unsigned full-width bits from this source. */
public fun Random.nextUInt(): UInt = nextInt().toUInt()

public fun Random.nextUInt(until: UInt): UInt = nextUInt(0u, until)

/** Uniform value in [from, until), using rejection before modulo reduction. */
public fun Random.nextUInt(from: UInt, until: UInt): UInt {
    if (from >= until) throw IllegalArgumentException("Random range must be nonempty")
    val width = until - from
    val threshold = (0u - width) % width
    while (true) {
        val value = nextUInt()
        if (value >= threshold) return from + value % width
    }
}

/** Unsigned full-width bits from this source. */
public fun Random.nextULong(): ULong = nextLong().toULong()

public fun Random.nextULong(until: ULong): ULong = nextULong(0uL, until)

/** Uniform value in [from, until), using rejection before modulo reduction. */
public fun Random.nextULong(from: ULong, until: ULong): ULong {
    if (from >= until) throw IllegalArgumentException("Random range must be nonempty")
    val width = until - from
    val threshold = (0uL - width) % width
    while (true) {
        val value = nextULong()
        if (value >= threshold) return from + value % width
    }
}

/** Uniform value in the inclusive range, including the full Int domain. */
public fun Random.nextInt(range: IntRange): Int {
    val first = range.first
    val last = range.last
    if (first > last) throw IllegalArgumentException("Random range must be nonempty")
    if (last < Int.MAX_VALUE) return nextInt(first, last + 1)
    if (first > Int.MIN_VALUE) return nextInt(first - 1, last) + 1
    return nextInt()
}

/** Uniform value in the inclusive range, including the full Long domain. */
public fun Random.nextLong(range: LongRange): Long {
    val first = range.first
    val last = range.last
    if (first > last) throw IllegalArgumentException("Random range must be nonempty")
    if (last < Long.MAX_VALUE) return nextLong(first, last + 1L)
    if (first > Long.MIN_VALUE) return nextLong(first - 1L, last) + 1L
    return nextLong()
}

/** Uniform value in the inclusive range, including the full UInt domain. */
public fun Random.nextUInt(range: UIntRange): UInt {
    val first = range.first
    val last = range.last
    if (first > last) throw IllegalArgumentException("Random range must be nonempty")
    if (last < UInt.MAX_VALUE) return nextUInt(first, last + 1u)
    if (first > 0u) return nextUInt(first - 1u, last) + 1u
    return nextUInt()
}

/** Uniform value in the inclusive range, including the full ULong domain. */
public fun Random.nextULong(range: ULongRange): ULong {
    val first = range.first
    val last = range.last
    if (first > last) throw IllegalArgumentException("Random range must be nonempty")
    if (last < ULong.MAX_VALUE) return nextULong(first, last + 1uL)
    if (first > 0uL) return nextULong(first - 1uL, last) + 1uL
    return nextULong()
}

public fun Random.nextUBytes(array: UByteArray): UByteArray = nextUBytes(array, 0, array.size)

public fun Random.nextUBytes(size: Int): UByteArray = nextUBytes(UByteArray(size))

/** Fills exactly [fromIndex, toIndex); invalid ranges do not consume state or modify the array. */
public fun Random.nextUBytes(array: UByteArray, fromIndex: Int, toIndex: Int): UByteArray {
    if (fromIndex < 0 || toIndex < fromIndex || toIndex > array.size) {
        throw IllegalArgumentException("Random byte range is outside the array")
    }
    var index = fromIndex
    while (index < toIndex) {
        array[index] = nextBits(8).toUByte()
        index += 1
    }
    return array
}

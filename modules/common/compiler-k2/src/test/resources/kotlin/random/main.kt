/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

import kotlin.random.*
import kotlin.math.nextUp

class IntSource(private val values: IntArray) : Random() {
    var calls: Int = 0
    override fun nextBits(bitCount: Int): Int {
        val value = values[calls]
        calls += 1
        return value
    }
}

class LongSource(private val values: LongArray) : Random() {
    var calls: Int = 0
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextLong(): Long {
        val value = values[calls]
        calls += 1
        return value
    }
}

class HighBits : Random() {
    var calls: Int = 0
    override fun nextBits(bitCount: Int): Int {
        calls += 1
        return if (bitCount == 32) -1 else if (bitCount == 0) 0 else (-1).ushr(32 - bitCount)
    }
}

fun invalid(action: () -> Unit) {
    var rejected = false
    try { action() } catch (error: IllegalArgumentException) { rejected = true }
    check(rejected)
}

fun main() {
    // Independently calculated SplitMix64 known answers, rather than JVM Random comparisons.
    val golden = intArrayOf(-501176263, 1853398634, 113532184, -125060952, 456755562, 1405853452, 746756798, -981200070)
    val factory: (Int) -> Random = ::Random
    check(factory(0).nextInt() == golden[0])
    val vector = Random(0L)
    for (value in golden) check(vector.nextInt() == value)
    check(Random(-7L).nextLong() == 7790691225229063386L)

    // Long-lived mutable objects and the singleton cross thousands of instruction slices.
    val independent = Random(0L)
    val seeded = Random(1234567890123456789L)
    val alias: Random = Random.Default
    check(Random === Random.Default)
    var defaultHash = 0L
    var seededHash = 0L
    var i = 0
    while (i < 2048) {
        val value = if (i % 2 == 0) Random.nextInt() else alias.nextInt()
        check(value == independent.nextInt())
        defaultHash = defaultHash * 31L + value.toLong()
        seededHash = seededHash * 31L + seeded.nextInt().toLong()
        i += 1
    }
    check(defaultHash == 5343809447350443141L)
    check(seededHash == -4618274079368369599L)

    val intSeed = Random(Int.MIN_VALUE)
    val longSeed = Random(Int.MIN_VALUE.toLong())
    repeat(40) { check(intSeed.nextInt() == longSeed.nextInt()) }
    val zero = Random(1L)
    val untouched = Random(1L)
    check(zero.nextBits(0) == 0)
    invalid { zero.nextBits(-1) }
    invalid { zero.nextBits(33) }
    invalid { zero.nextInt(0) }
    invalid { zero.nextInt(8, 8) }
    invalid { zero.nextLong(-1L) }
    invalid { zero.nextUInt(0u) }
    invalid { zero.nextULong(9uL, 8uL) }
    invalid { zero.nextInt(2..1) }
    invalid { zero.nextLong(2L..1L) }
    invalid { zero.nextUInt(2u..1u) }
    invalid { zero.nextULong(2uL..1uL) }
    invalid { zero.nextDouble(Double.NaN) }
    invalid { zero.nextDouble(Double.POSITIVE_INFINITY) }
    invalid { zero.nextDouble(Double.NEGATIVE_INFINITY, 1.0) }
    invalid { zero.nextDouble(1.0, 1.0) }
    check(zero.nextInt() == untouched.nextInt())

    val rejectedInt = IntSource(intArrayOf(-1, 8))
    check(rejectedInt.nextInt(3) == 1 && rejectedInt.calls == 2)
    val rejectedLong = LongSource(longArrayOf(-1L, 8L))
    check(rejectedLong.nextLong(3L) == 1L && rejectedLong.calls == 2)
    val wideInt = IntSource(intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE))
    check(wideInt.nextInt(Int.MIN_VALUE, Int.MAX_VALUE) == Int.MIN_VALUE && wideInt.calls == 2)
    val wideLong = LongSource(longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE))
    check(wideLong.nextLong(Long.MIN_VALUE, Long.MAX_VALUE) == Long.MIN_VALUE && wideLong.calls == 2)
    val unsignedInt = IntSource(intArrayOf(0, 4))
    check(unsignedInt.nextUInt(3u) == 1u && unsignedInt.calls == 2)
    val unsignedLong = LongSource(longArrayOf(0L, 4L))
    check(unsignedLong.nextULong(3uL) == 1uL && unsignedLong.calls == 2)
    check(IntSource(intArrayOf(-1)).nextInt(Int.MIN_VALUE..Int.MAX_VALUE) == -1)
    check(LongSource(longArrayOf(-1L)).nextLong(Long.MIN_VALUE..Long.MAX_VALUE) == -1L)
    check(IntSource(intArrayOf(-1)).nextUInt(0u..UInt.MAX_VALUE) == UInt.MAX_VALUE)
    check(LongSource(longArrayOf(-1L)).nextULong(0uL..ULong.MAX_VALUE) == ULong.MAX_VALUE)
    check(IntSource(intArrayOf(0)).nextInt(Int.MAX_VALUE..Int.MAX_VALUE) == Int.MAX_VALUE)
    check(LongSource(longArrayOf(0L)).nextLong(Long.MAX_VALUE..Long.MAX_VALUE) == Long.MAX_VALUE)
    check(IntSource(intArrayOf(0)).nextUInt(UInt.MAX_VALUE..UInt.MAX_VALUE) == UInt.MAX_VALUE)
    check(LongSource(longArrayOf(0L)).nextULong(ULong.MAX_VALUE..ULong.MAX_VALUE) == ULong.MAX_VALUE)

    val high = HighBits()
    check(high.nextBoolean())
    check(high.nextFloat() == 0.9999999403953552f)
    check(high.nextDouble() == 0.9999999999999999)
    check(high.nextDouble(1.0, 1.0.nextUp()) == 1.0)
    val wideDouble = high.nextDouble(-Double.MAX_VALUE, Double.MAX_VALUE)
    check(wideDouble >= -Double.MAX_VALUE && wideDouble < Double.MAX_VALUE)
    val tinyDouble = high.nextDouble(0.0, 0.0.nextUp())
    check(tinyDouble == 0.0)

    val bytes = byteArrayOf(11, 11, 11, 11)
    val byteSource = IntSource(intArrayOf(255, 128, 0, 127))
    check(byteSource.nextBytes(bytes, 1, 3) === bytes)
    check(bytes[0] == 11.toByte() && bytes[1] == (-1).toByte() && bytes[2] == (-128).toByte() && bytes[3] == 11.toByte())
    check(byteSource.nextBytes(bytes, 4, 4) === bytes && byteSource.calls == 2)
    invalid { byteSource.nextBytes(bytes, -1, 1) }
    invalid { byteSource.nextBytes(bytes, 2, 1) }
    invalid { byteSource.nextBytes(bytes, 0, 5) }
    check(byteSource.calls == 2 && bytes[0] == 11.toByte())
    val allocated = byteSource.nextBytes(2)
    check(allocated[0] == 0.toByte() && allocated[1] == 127.toByte())
    check(Random(5).nextBytes(ByteArray(0)).size == 0)
    var negativeSize = false
    try { Random(5).nextBytes(-1) } catch (error: NegativeArraySizeException) { negativeSize = true }
    check(negativeSize)
    val unsignedBytes = ubyteArrayOf(11u, 11u, 11u)
    val ubyteSource = IntSource(intArrayOf(255, 128, 0))
    check(ubyteSource.nextUBytes(unsignedBytes, 1, 2) === unsignedBytes)
    check(unsignedBytes[0] == 11.toUByte() && unsignedBytes[1] == 255.toUByte() && unsignedBytes[2] == 11.toUByte())
    invalid { ubyteSource.nextUBytes(unsignedBytes, 0, 4) }
    check(ubyteSource.calls == 1)
    check(ubyteSource.nextUBytes(1)[0] == 128.toUByte())
    check(ubyteSource.nextUBytes(UByteArray(1))[0] == 0.toUByte())

    val source = Random(27)
    val bound: (Int) -> Int = source::nextInt
    val unsignedBound: (UInt) -> UInt = { source.nextUInt(it) }
    repeat(200) {
        check(bound(17) >= 0 && bound(17) < 17)
        val n = source.nextInt(-99, 100)
        check(n >= -99 && n < 100)
        val l = source.nextLong(-99L, 100L)
        check(l >= -99L && l < 100L)
        check(unsignedBound(19u) < 19u)
        val u = source.nextUInt(100u, UInt.MAX_VALUE)
        check(u >= 100u && u < UInt.MAX_VALUE)
        val ul = source.nextULong(100uL, ULong.MAX_VALUE)
        check(ul >= 100uL && ul < ULong.MAX_VALUE)
        val f = source.nextFloat()
        val d = source.nextDouble(10.0)
        check(f >= 0f && f < 1f && d >= 0.0 && d < 10.0)
    }
    i = 0
    while (i <= 32) {
        val bits = source.nextBits(i)
        if (i < 32) check(bits >= 0 && bits.toLong() < (1L shl i))
        i += 1
    }
}

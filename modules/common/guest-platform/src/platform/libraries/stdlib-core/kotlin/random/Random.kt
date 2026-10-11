/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.random

import kotlin.math.nextDown

/**
 * A Guest pseudorandom source. Seeded generators and Default are reproducible on Compukters;
 * their sequences are independent of Kotlin/JVM implementations. Not a cryptographic source.
 * Each call executes ordinary metered Guest instructions.
 */
public abstract class Random() {
    /** Returns random low bits with upper bits zero; bitCount must be in 0..32. */
    public abstract fun nextBits(bitCount: Int): Int

    public open fun nextInt(): Int = nextBits(32)

    /** Uniform value in [0, until). */
    public open fun nextInt(until: Int): Int = nextInt(0, until)

    /** Uniform value in [from, until); invalid bounds do not consume random bits. */
    public open fun nextInt(from: Int, until: Int): Int {
        if (from >= until) throw IllegalArgumentException("Random range must be nonempty")
        val width = until - from
        if (width > 0) {
            while (true) {
                val bits = nextInt().ushr(1)
                val offset = bits % width
                if (bits - offset + (width - 1) >= 0) return from + offset
            }
        }
        // The width overflowed signed Int; accept full-width values directly.
        while (true) {
            val value = nextInt()
            if (value >= from && value < until) return value
        }
    }

    public open fun nextLong(): Long = (nextBits(32).toLong() shl 32) or (nextBits(32).toLong() and 4294967295L)

    public open fun nextLong(until: Long): Long = nextLong(0L, until)

    public open fun nextLong(from: Long, until: Long): Long {
        if (from >= until) throw IllegalArgumentException("Random range must be nonempty")
        val width = until - from
        if (width > 0L) {
            while (true) {
                val bits = nextLong().ushr(1)
                val offset = bits % width
                if (bits - offset + (width - 1L) >= 0L) return from + offset
            }
        }
        while (true) {
            val value = nextLong()
            if (value >= from && value < until) return value
        }
    }

    public open fun nextBoolean(): Boolean = nextBits(1) != 0

    /** Uniform 24-bit fraction in [0, 1). */
    public open fun nextFloat(): Float = nextBits(24).toFloat() / 16777216f

    /** Uniform 53-bit fraction in [0, 1). */
    public open fun nextDouble(): Double = ((nextBits(26).toLong() shl 27) + nextBits(27).toLong()).toDouble() / 9007199254740992.0

    public open fun nextDouble(until: Double): Double = nextDouble(0.0, until)

    /** Finite bounds with an exclusive upper endpoint, including adjacent representable values. */
    public open fun nextDouble(from: Double, until: Double): Double {
        if (!(from < until) || from < -Double.MAX_VALUE || until > Double.MAX_VALUE) {
            throw IllegalArgumentException("Random floating bounds must be finite and increasing")
        }
        val fraction = nextDouble()
        val width = until - from
        val value = if (width > Double.MAX_VALUE) {
            // Halve before subtracting, then scale back without overflowing the intermediate width.
            (from / 2.0 + fraction * (until / 2.0 - from / 2.0)) * 2.0
        } else {
            from + fraction * width
        }
        return if (value >= until) until.nextDown() else if (value < from) from else value
    }

    public open fun nextBytes(array: ByteArray): ByteArray = nextBytes(array, 0, array.size)

    public open fun nextBytes(size: Int): ByteArray = nextBytes(ByteArray(size))

    /** Fills exactly [fromIndex, toIndex); invalid ranges leave both array and generator unchanged. */
    public open fun nextBytes(array: ByteArray, fromIndex: Int, toIndex: Int): ByteArray {
        if (fromIndex < 0 || toIndex < fromIndex || toIndex > array.size) {
            throw IllegalArgumentException("Random byte range is outside the array")
        }
        var index = fromIndex
        while (index < toIndex) {
            array[index] = nextBits(8).toByte()
            index += 1
        }
        return array
    }

    /** The shared, program-local sequence starts from seed 0L; aliases and tasks share its state. */
    public companion object Default : Random() {
        private val generator: Random = SplitMixRandom(0L)
        public override fun nextBits(bitCount: Int): Int = generator.nextBits(bitCount)
    }
}

/** Independent reproducible generator; Int seeds sign-extend to Long. */
public fun Random(seed: Int): Random = SplitMixRandom(seed.toLong())

/** Independent reproducible generator with all 64 seed bits retained. */
public fun Random(seed: Long): Random = SplitMixRandom(seed)

private class SplitMixRandom(seed: Long) : Random() {
    private var state: Long = seed

    override fun nextBits(bitCount: Int): Int {
        if (bitCount < 0 || bitCount > 32) throw IllegalArgumentException("bitCount must be in 0..32")
        if (bitCount == 0) return 0
        state += -7046029254386353131L
        var word = state
        word = (word xor word.ushr(30)) * -4658895280553007687L
        word = (word xor word.ushr(27)) * -7723592293110705685L
        word = word xor word.ushr(31)
        return word.ushr(64 - bitCount).toInt()
    }
}

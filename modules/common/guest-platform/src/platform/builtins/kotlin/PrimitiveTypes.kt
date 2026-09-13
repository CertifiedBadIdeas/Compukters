/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

public abstract class Number

public class Boolean private constructor() : Comparable<Boolean> {
    public external override operator fun compareTo(other: Boolean): Int

    public external operator fun not(): Boolean
}

public class Char private constructor() : Comparable<Char> {
    public external override operator fun compareTo(other: Char): Int

    public external fun toInt(): Int
}

public class Int private constructor() : Number(), Comparable<Int> {
    public external override operator fun compareTo(other: Int): Int

    public external operator fun compareTo(other: Long): Int

    public external operator fun compareTo(other: Float): Int

    public external operator fun plus(other: Int): Int

    public external operator fun plus(other: Long): Long

    public external operator fun plus(other: Float): Float

    public external operator fun minus(other: Int): Int

    public external operator fun minus(other: Long): Long

    public external operator fun minus(other: Float): Float

    public external operator fun times(other: Int): Int

    public external operator fun times(other: Long): Long

    public external operator fun times(other: Float): Float

    public external operator fun div(other: Int): Int

    public external operator fun div(other: Long): Long

    public external operator fun div(other: Float): Float

    public external operator fun rem(other: Int): Int

    public external operator fun rem(other: Long): Long

    public external operator fun rem(other: Float): Float

    public external operator fun unaryMinus(): Int

    public external infix fun and(other: Int): Int

    public external infix fun or(other: Int): Int

    public external infix fun xor(other: Int): Int

    public external fun inv(): Int

    public external infix fun shl(bitCount: Int): Int

    public external infix fun shr(bitCount: Int): Int

    public external infix fun ushr(bitCount: Int): Int

    public external fun toChar(): Char

    public external fun toLong(): Long

    public external fun toFloat(): Float

    public companion object {
        public const val MIN_VALUE: Int = -2147483647 - 1
        public const val MAX_VALUE: Int = 2147483647
    }
}

internal class Byte private constructor() : Number()

internal class Short private constructor() : Number()

public class Long private constructor() : Number(), Comparable<Long> {
    public external override operator fun compareTo(other: Long): Int

    public external operator fun compareTo(other: Int): Int

    public external operator fun compareTo(other: Float): Int

    public external operator fun plus(other: Long): Long

    public external operator fun plus(other: Int): Long

    public external operator fun plus(other: Float): Float

    public external operator fun minus(other: Long): Long

    public external operator fun minus(other: Int): Long

    public external operator fun minus(other: Float): Float

    public external operator fun times(other: Long): Long

    public external operator fun times(other: Int): Long

    public external operator fun times(other: Float): Float

    public external operator fun div(other: Long): Long

    public external operator fun div(other: Int): Long

    public external operator fun div(other: Float): Float

    public external operator fun rem(other: Long): Long

    public external operator fun rem(other: Int): Long

    public external operator fun rem(other: Float): Float

    public external operator fun unaryMinus(): Long

    public external infix fun and(other: Long): Long

    public external infix fun or(other: Long): Long

    public external infix fun xor(other: Long): Long

    public external fun inv(): Long

    public external infix fun shl(bitCount: Int): Long

    public external infix fun shr(bitCount: Int): Long

    public external infix fun ushr(bitCount: Int): Long

    public external fun toInt(): Int

    public external fun toFloat(): Float

    public companion object {
        public const val MIN_VALUE: Long = -9223372036854775807L - 1L
        public const val MAX_VALUE: Long = 9223372036854775807L
    }
}

public class Float private constructor() : Number(), Comparable<Float> {
    public external override operator fun compareTo(other: Float): Int

    public external operator fun compareTo(other: Int): Int

    public external operator fun compareTo(other: Long): Int

    public external operator fun plus(other: Float): Float

    public external operator fun plus(other: Int): Float

    public external operator fun plus(other: Long): Float

    public external operator fun minus(other: Float): Float

    public external operator fun minus(other: Int): Float

    public external operator fun minus(other: Long): Float

    public external operator fun times(other: Float): Float

    public external operator fun times(other: Int): Float

    public external operator fun times(other: Long): Float

    public external operator fun div(other: Float): Float

    public external operator fun div(other: Int): Float

    public external operator fun div(other: Long): Float

    public external operator fun rem(other: Float): Float

    public external operator fun rem(other: Int): Float

    public external operator fun rem(other: Long): Float

    public external operator fun unaryMinus(): Float

    public external fun toInt(): Int

    public external fun toLong(): Long

    public companion object {
        public const val MIN_VALUE: Float = 1.4E-45F
        public const val MAX_VALUE: Float = 3.4028235E38F
        public external val POSITIVE_INFINITY: Float
        public external val NEGATIVE_INFINITY: Float
        public external val NaN: Float
    }
}

internal class Double private constructor() : Number()

internal class UByte private constructor()

internal class UShort private constructor()

internal class UInt private constructor()

internal class ULong private constructor()

public class String : Comparable<String>, CharSequence {
    public external constructor()

    public external constructor(chars: CharArray)

    public external constructor(chars: CharArray, offset: Int, length: Int)

    public external override val length: Int

    public external override operator fun get(index: Int): Char

    public external override operator fun compareTo(other: String): Int

    public external operator fun plus(other: Any?): String

    public external fun substring(startIndex: Int, endIndex: Int): String
}

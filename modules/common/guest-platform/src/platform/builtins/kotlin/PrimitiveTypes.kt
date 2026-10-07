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
    public external infix fun and(other: Boolean): Boolean
    public external infix fun or(other: Boolean): Boolean
    public external infix fun xor(other: Boolean): Boolean
}

public class Char private constructor() : Comparable<Char> {
    public external override operator fun compareTo(other: Char): Int
    public external operator fun plus(other: Int): Char
    public external operator fun minus(other: Int): Char
    public external operator fun minus(other: Char): Int
    public external operator fun inc(): Char
    public external operator fun dec(): Char
    public external val code: Int
    public external fun toInt(): Int
    public companion object {
        public const val MIN_VALUE: Char = '\u0000'
        public const val MAX_VALUE: Char = '\uFFFF'
    }
}

public class Byte private constructor() : Number(), Comparable<Byte> {
    public external override operator fun compareTo(other: Byte): Int

    public external operator fun compareTo(other: Short): Int

    public external operator fun compareTo(other: Int): Int

    public external operator fun compareTo(other: Long): Int

    public external operator fun compareTo(other: Float): Int

    public external operator fun compareTo(other: Double): Int

    public external operator fun plus(other: Byte): Int

    public external operator fun plus(other: Short): Int

    public external operator fun plus(other: Int): Int

    public external operator fun plus(other: Long): Long

    public external operator fun plus(other: Float): Float

    public external operator fun plus(other: Double): Double

    public external operator fun minus(other: Byte): Int

    public external operator fun minus(other: Short): Int

    public external operator fun minus(other: Int): Int

    public external operator fun minus(other: Long): Long

    public external operator fun minus(other: Float): Float

    public external operator fun minus(other: Double): Double

    public external operator fun times(other: Byte): Int

    public external operator fun times(other: Short): Int

    public external operator fun times(other: Int): Int

    public external operator fun times(other: Long): Long

    public external operator fun times(other: Float): Float

    public external operator fun times(other: Double): Double

    public external operator fun div(other: Byte): Int

    public external operator fun div(other: Short): Int

    public external operator fun div(other: Int): Int

    public external operator fun div(other: Long): Long

    public external operator fun div(other: Float): Float

    public external operator fun div(other: Double): Double

    public external operator fun rem(other: Byte): Int

    public external operator fun rem(other: Short): Int

    public external operator fun rem(other: Int): Int

    public external operator fun rem(other: Long): Long

    public external operator fun rem(other: Float): Float

    public external operator fun rem(other: Double): Double

    public external operator fun unaryPlus(): Int

    public external operator fun unaryMinus(): Int

    public external operator fun inc(): Byte

    public external operator fun dec(): Byte

    public external fun toByte(): Byte

    public external fun toShort(): Short

    public external fun toInt(): Int

    public external fun toLong(): Long

    public external fun toFloat(): Float

    public external fun toDouble(): Double

    public external fun toChar(): Char

    public external fun toUByte(): UByte

    public external fun toUShort(): UShort

    public external fun toUInt(): UInt

    public external fun toULong(): ULong



    public companion object {
        public const val MIN_VALUE: Byte = -128

        public const val MAX_VALUE: Byte = 127
    }
}

public class Short private constructor() : Number(), Comparable<Short> {
    public external operator fun compareTo(other: Byte): Int

    public external override operator fun compareTo(other: Short): Int

    public external operator fun compareTo(other: Int): Int

    public external operator fun compareTo(other: Long): Int

    public external operator fun compareTo(other: Float): Int

    public external operator fun compareTo(other: Double): Int

    public external operator fun plus(other: Byte): Int

    public external operator fun plus(other: Short): Int

    public external operator fun plus(other: Int): Int

    public external operator fun plus(other: Long): Long

    public external operator fun plus(other: Float): Float

    public external operator fun plus(other: Double): Double

    public external operator fun minus(other: Byte): Int

    public external operator fun minus(other: Short): Int

    public external operator fun minus(other: Int): Int

    public external operator fun minus(other: Long): Long

    public external operator fun minus(other: Float): Float

    public external operator fun minus(other: Double): Double

    public external operator fun times(other: Byte): Int

    public external operator fun times(other: Short): Int

    public external operator fun times(other: Int): Int

    public external operator fun times(other: Long): Long

    public external operator fun times(other: Float): Float

    public external operator fun times(other: Double): Double

    public external operator fun div(other: Byte): Int

    public external operator fun div(other: Short): Int

    public external operator fun div(other: Int): Int

    public external operator fun div(other: Long): Long

    public external operator fun div(other: Float): Float

    public external operator fun div(other: Double): Double

    public external operator fun rem(other: Byte): Int

    public external operator fun rem(other: Short): Int

    public external operator fun rem(other: Int): Int

    public external operator fun rem(other: Long): Long

    public external operator fun rem(other: Float): Float

    public external operator fun rem(other: Double): Double

    public external operator fun unaryPlus(): Int

    public external operator fun unaryMinus(): Int

    public external operator fun inc(): Short

    public external operator fun dec(): Short

    public external fun toByte(): Byte

    public external fun toShort(): Short

    public external fun toInt(): Int

    public external fun toLong(): Long

    public external fun toFloat(): Float

    public external fun toDouble(): Double

    public external fun toChar(): Char

    public external fun toUByte(): UByte

    public external fun toUShort(): UShort

    public external fun toUInt(): UInt

    public external fun toULong(): ULong



    public companion object {
        public const val MIN_VALUE: Short = -32768

        public const val MAX_VALUE: Short = 32767
    }
}

public class Int private constructor() : Number(), Comparable<Int> {
    public external operator fun compareTo(other: Byte): Int

    public external operator fun compareTo(other: Short): Int

    public external override operator fun compareTo(other: Int): Int

    public external operator fun compareTo(other: Long): Int

    public external operator fun compareTo(other: Float): Int

    public external operator fun compareTo(other: Double): Int

    public external operator fun plus(other: Byte): Int

    public external operator fun plus(other: Short): Int

    public external operator fun plus(other: Int): Int

    public external operator fun plus(other: Long): Long

    public external operator fun plus(other: Float): Float

    public external operator fun plus(other: Double): Double

    public external operator fun minus(other: Byte): Int

    public external operator fun minus(other: Short): Int

    public external operator fun minus(other: Int): Int

    public external operator fun minus(other: Long): Long

    public external operator fun minus(other: Float): Float

    public external operator fun minus(other: Double): Double

    public external operator fun times(other: Byte): Int

    public external operator fun times(other: Short): Int

    public external operator fun times(other: Int): Int

    public external operator fun times(other: Long): Long

    public external operator fun times(other: Float): Float

    public external operator fun times(other: Double): Double

    public external operator fun div(other: Byte): Int

    public external operator fun div(other: Short): Int

    public external operator fun div(other: Int): Int

    public external operator fun div(other: Long): Long

    public external operator fun div(other: Float): Float

    public external operator fun div(other: Double): Double

    public external operator fun rem(other: Byte): Int

    public external operator fun rem(other: Short): Int

    public external operator fun rem(other: Int): Int

    public external operator fun rem(other: Long): Long

    public external operator fun rem(other: Float): Float

    public external operator fun rem(other: Double): Double

    public external operator fun unaryPlus(): Int

    public external operator fun unaryMinus(): Int

    public external operator fun inc(): Int

    public external operator fun dec(): Int

    public external infix fun and(other: Int): Int

    public external infix fun or(other: Int): Int

    public external infix fun xor(other: Int): Int

    public external fun inv(): Int

    public external infix fun shl(bitCount: Int): Int

    public external infix fun shr(bitCount: Int): Int

    public external infix fun ushr(bitCount: Int): Int

    public external fun toByte(): Byte

    public external fun toShort(): Short

    public external fun toInt(): Int

    public external fun toLong(): Long

    public external fun toFloat(): Float

    public external fun toDouble(): Double

    public external fun toChar(): Char

    public external fun toUByte(): UByte

    public external fun toUShort(): UShort

    public external fun toUInt(): UInt

    public external fun toULong(): ULong



    public companion object {
        public const val MIN_VALUE: Int = -2147483647 - 1

        public const val MAX_VALUE: Int = 2147483647
    }
}

public class Long private constructor() : Number(), Comparable<Long> {
    public external operator fun compareTo(other: Byte): Int

    public external operator fun compareTo(other: Short): Int

    public external operator fun compareTo(other: Int): Int

    public external override operator fun compareTo(other: Long): Int

    public external operator fun compareTo(other: Float): Int

    public external operator fun compareTo(other: Double): Int

    public external operator fun plus(other: Byte): Long

    public external operator fun plus(other: Short): Long

    public external operator fun plus(other: Int): Long

    public external operator fun plus(other: Long): Long

    public external operator fun plus(other: Float): Float

    public external operator fun plus(other: Double): Double

    public external operator fun minus(other: Byte): Long

    public external operator fun minus(other: Short): Long

    public external operator fun minus(other: Int): Long

    public external operator fun minus(other: Long): Long

    public external operator fun minus(other: Float): Float

    public external operator fun minus(other: Double): Double

    public external operator fun times(other: Byte): Long

    public external operator fun times(other: Short): Long

    public external operator fun times(other: Int): Long

    public external operator fun times(other: Long): Long

    public external operator fun times(other: Float): Float

    public external operator fun times(other: Double): Double

    public external operator fun div(other: Byte): Long

    public external operator fun div(other: Short): Long

    public external operator fun div(other: Int): Long

    public external operator fun div(other: Long): Long

    public external operator fun div(other: Float): Float

    public external operator fun div(other: Double): Double

    public external operator fun rem(other: Byte): Long

    public external operator fun rem(other: Short): Long

    public external operator fun rem(other: Int): Long

    public external operator fun rem(other: Long): Long

    public external operator fun rem(other: Float): Float

    public external operator fun rem(other: Double): Double

    public external operator fun unaryPlus(): Long

    public external operator fun unaryMinus(): Long

    public external operator fun inc(): Long

    public external operator fun dec(): Long

    public external infix fun and(other: Long): Long

    public external infix fun or(other: Long): Long

    public external infix fun xor(other: Long): Long

    public external fun inv(): Long

    public external infix fun shl(bitCount: Int): Long

    public external infix fun shr(bitCount: Int): Long

    public external infix fun ushr(bitCount: Int): Long

    public external fun toByte(): Byte

    public external fun toShort(): Short

    public external fun toInt(): Int

    public external fun toLong(): Long

    public external fun toFloat(): Float

    public external fun toDouble(): Double

    public external fun toChar(): Char

    public external fun toUByte(): UByte

    public external fun toUShort(): UShort

    public external fun toUInt(): UInt

    public external fun toULong(): ULong



    public companion object {
        public const val MIN_VALUE: Long = -9223372036854775807L - 1L

        public const val MAX_VALUE: Long = 9223372036854775807L
    }
}

public class Float private constructor() : Number(), Comparable<Float> {
    public external operator fun compareTo(other: Byte): Int

    public external operator fun compareTo(other: Short): Int

    public external operator fun compareTo(other: Int): Int

    public external operator fun compareTo(other: Long): Int

    public external override operator fun compareTo(other: Float): Int

    public external operator fun compareTo(other: Double): Int

    public external operator fun plus(other: Byte): Float

    public external operator fun plus(other: Short): Float

    public external operator fun plus(other: Int): Float

    public external operator fun plus(other: Long): Float

    public external operator fun plus(other: Float): Float

    public external operator fun plus(other: Double): Double

    public external operator fun minus(other: Byte): Float

    public external operator fun minus(other: Short): Float

    public external operator fun minus(other: Int): Float

    public external operator fun minus(other: Long): Float

    public external operator fun minus(other: Float): Float

    public external operator fun minus(other: Double): Double

    public external operator fun times(other: Byte): Float

    public external operator fun times(other: Short): Float

    public external operator fun times(other: Int): Float

    public external operator fun times(other: Long): Float

    public external operator fun times(other: Float): Float

    public external operator fun times(other: Double): Double

    public external operator fun div(other: Byte): Float

    public external operator fun div(other: Short): Float

    public external operator fun div(other: Int): Float

    public external operator fun div(other: Long): Float

    public external operator fun div(other: Float): Float

    public external operator fun div(other: Double): Double

    public external operator fun rem(other: Byte): Float

    public external operator fun rem(other: Short): Float

    public external operator fun rem(other: Int): Float

    public external operator fun rem(other: Long): Float

    public external operator fun rem(other: Float): Float

    public external operator fun rem(other: Double): Double

    public external operator fun unaryPlus(): Float

    public external operator fun unaryMinus(): Float

    public external operator fun inc(): Float

    public external operator fun dec(): Float

    public external fun toByte(): Byte

    public external fun toShort(): Short

    public external fun toInt(): Int

    public external fun toLong(): Long

    public external fun toFloat(): Float

    public external fun toDouble(): Double

    public external fun toChar(): Char

    public external fun toUByte(): UByte

    public external fun toUShort(): UShort

    public external fun toUInt(): UInt

    public external fun toULong(): ULong



    public companion object {
        public const val MIN_VALUE: Float = 1.4E-45F

        public const val MAX_VALUE: Float = 3.4028235E38F

        public external val POSITIVE_INFINITY: Float

        public external val NEGATIVE_INFINITY: Float

        public external val NaN: Float
    }
}

public class Double private constructor() : Number(), Comparable<Double> {
    public external operator fun compareTo(other: Byte): Int

    public external operator fun compareTo(other: Short): Int

    public external operator fun compareTo(other: Int): Int

    public external operator fun compareTo(other: Long): Int

    public external operator fun compareTo(other: Float): Int

    public external override operator fun compareTo(other: Double): Int

    public external operator fun plus(other: Byte): Double

    public external operator fun plus(other: Short): Double

    public external operator fun plus(other: Int): Double

    public external operator fun plus(other: Long): Double

    public external operator fun plus(other: Float): Double

    public external operator fun plus(other: Double): Double

    public external operator fun minus(other: Byte): Double

    public external operator fun minus(other: Short): Double

    public external operator fun minus(other: Int): Double

    public external operator fun minus(other: Long): Double

    public external operator fun minus(other: Float): Double

    public external operator fun minus(other: Double): Double

    public external operator fun times(other: Byte): Double

    public external operator fun times(other: Short): Double

    public external operator fun times(other: Int): Double

    public external operator fun times(other: Long): Double

    public external operator fun times(other: Float): Double

    public external operator fun times(other: Double): Double

    public external operator fun div(other: Byte): Double

    public external operator fun div(other: Short): Double

    public external operator fun div(other: Int): Double

    public external operator fun div(other: Long): Double

    public external operator fun div(other: Float): Double

    public external operator fun div(other: Double): Double

    public external operator fun rem(other: Byte): Double

    public external operator fun rem(other: Short): Double

    public external operator fun rem(other: Int): Double

    public external operator fun rem(other: Long): Double

    public external operator fun rem(other: Float): Double

    public external operator fun rem(other: Double): Double

    public external operator fun unaryPlus(): Double

    public external operator fun unaryMinus(): Double

    public external operator fun inc(): Double

    public external operator fun dec(): Double

    public external fun toByte(): Byte

    public external fun toShort(): Short

    public external fun toInt(): Int

    public external fun toLong(): Long

    public external fun toFloat(): Float

    public external fun toDouble(): Double

    public external fun toChar(): Char

    public external fun toUByte(): UByte

    public external fun toUShort(): UShort

    public external fun toUInt(): UInt

    public external fun toULong(): ULong



    public companion object {
        public const val MIN_VALUE: Double = 4.9E-324

        public const val MAX_VALUE: Double = 1.7976931348623157E308

        public external val POSITIVE_INFINITY: Double

        public external val NEGATIVE_INFINITY: Double

        public external val NaN: Double
    }
}

public class UByte private constructor() : Comparable<UByte> {
    public external override operator fun compareTo(other: UByte): Int

    public external operator fun compareTo(other: UShort): Int

    public external operator fun compareTo(other: UInt): Int

    public external operator fun compareTo(other: ULong): Int

    public external operator fun plus(other: UByte): UInt

    public external operator fun plus(other: UShort): UInt

    public external operator fun plus(other: UInt): UInt

    public external operator fun plus(other: ULong): ULong

    public external operator fun minus(other: UByte): UInt

    public external operator fun minus(other: UShort): UInt

    public external operator fun minus(other: UInt): UInt

    public external operator fun minus(other: ULong): ULong

    public external operator fun times(other: UByte): UInt

    public external operator fun times(other: UShort): UInt

    public external operator fun times(other: UInt): UInt

    public external operator fun times(other: ULong): ULong

    public external operator fun div(other: UByte): UInt

    public external operator fun div(other: UShort): UInt

    public external operator fun div(other: UInt): UInt

    public external operator fun div(other: ULong): ULong

    public external operator fun rem(other: UByte): UInt

    public external operator fun rem(other: UShort): UInt

    public external operator fun rem(other: UInt): UInt

    public external operator fun rem(other: ULong): ULong

    public external operator fun inc(): UByte

    public external operator fun dec(): UByte

    public external infix fun and(other: UByte): UByte

    public external infix fun or(other: UByte): UByte

    public external infix fun xor(other: UByte): UByte

    public external fun inv(): UByte

    public external fun toByte(): Byte

    public external fun toShort(): Short

    public external fun toInt(): Int

    public external fun toLong(): Long

    public external fun toFloat(): Float

    public external fun toDouble(): Double

    public external fun toUByte(): UByte

    public external fun toUShort(): UShort

    public external fun toUInt(): UInt

    public external fun toULong(): ULong



    public companion object {
        public const val MIN_VALUE: UByte = 0u

        public const val MAX_VALUE: UByte = 255u
    }
}

public class UShort private constructor() : Comparable<UShort> {
    public external operator fun compareTo(other: UByte): Int

    public external override operator fun compareTo(other: UShort): Int

    public external operator fun compareTo(other: UInt): Int

    public external operator fun compareTo(other: ULong): Int

    public external operator fun plus(other: UByte): UInt

    public external operator fun plus(other: UShort): UInt

    public external operator fun plus(other: UInt): UInt

    public external operator fun plus(other: ULong): ULong

    public external operator fun minus(other: UByte): UInt

    public external operator fun minus(other: UShort): UInt

    public external operator fun minus(other: UInt): UInt

    public external operator fun minus(other: ULong): ULong

    public external operator fun times(other: UByte): UInt

    public external operator fun times(other: UShort): UInt

    public external operator fun times(other: UInt): UInt

    public external operator fun times(other: ULong): ULong

    public external operator fun div(other: UByte): UInt

    public external operator fun div(other: UShort): UInt

    public external operator fun div(other: UInt): UInt

    public external operator fun div(other: ULong): ULong

    public external operator fun rem(other: UByte): UInt

    public external operator fun rem(other: UShort): UInt

    public external operator fun rem(other: UInt): UInt

    public external operator fun rem(other: ULong): ULong

    public external operator fun inc(): UShort

    public external operator fun dec(): UShort

    public external infix fun and(other: UShort): UShort

    public external infix fun or(other: UShort): UShort

    public external infix fun xor(other: UShort): UShort

    public external fun inv(): UShort

    public external fun toByte(): Byte

    public external fun toShort(): Short

    public external fun toInt(): Int

    public external fun toLong(): Long

    public external fun toFloat(): Float

    public external fun toDouble(): Double

    public external fun toUByte(): UByte

    public external fun toUShort(): UShort

    public external fun toUInt(): UInt

    public external fun toULong(): ULong



    public companion object {
        public const val MIN_VALUE: UShort = 0u

        public const val MAX_VALUE: UShort = 65535u
    }
}

public class UInt private constructor() : Comparable<UInt> {
    public external operator fun compareTo(other: UByte): Int

    public external operator fun compareTo(other: UShort): Int

    public external override operator fun compareTo(other: UInt): Int

    public external operator fun compareTo(other: ULong): Int

    public external operator fun plus(other: UByte): UInt

    public external operator fun plus(other: UShort): UInt

    public external operator fun plus(other: UInt): UInt

    public external operator fun plus(other: ULong): ULong

    public external operator fun minus(other: UByte): UInt

    public external operator fun minus(other: UShort): UInt

    public external operator fun minus(other: UInt): UInt

    public external operator fun minus(other: ULong): ULong

    public external operator fun times(other: UByte): UInt

    public external operator fun times(other: UShort): UInt

    public external operator fun times(other: UInt): UInt

    public external operator fun times(other: ULong): ULong

    public external operator fun div(other: UByte): UInt

    public external operator fun div(other: UShort): UInt

    public external operator fun div(other: UInt): UInt

    public external operator fun div(other: ULong): ULong

    public external operator fun rem(other: UByte): UInt

    public external operator fun rem(other: UShort): UInt

    public external operator fun rem(other: UInt): UInt

    public external operator fun rem(other: ULong): ULong

    public external operator fun inc(): UInt

    public external operator fun dec(): UInt

    public external infix fun and(other: UInt): UInt

    public external infix fun or(other: UInt): UInt

    public external infix fun xor(other: UInt): UInt

    public external fun inv(): UInt

    public external infix fun shl(bitCount: Int): UInt

    public external infix fun shr(bitCount: Int): UInt

    public external fun toByte(): Byte

    public external fun toShort(): Short

    public external fun toInt(): Int

    public external fun toLong(): Long

    public external fun toFloat(): Float

    public external fun toDouble(): Double

    public external fun toUByte(): UByte

    public external fun toUShort(): UShort

    public external fun toUInt(): UInt

    public external fun toULong(): ULong



    public companion object {
        public const val MIN_VALUE: UInt = 0u

        public const val MAX_VALUE: UInt = 4294967295u
    }
}

public class ULong private constructor() : Comparable<ULong> {
    public external operator fun compareTo(other: UByte): Int

    public external operator fun compareTo(other: UShort): Int

    public external operator fun compareTo(other: UInt): Int

    public external override operator fun compareTo(other: ULong): Int

    public external operator fun plus(other: UByte): ULong

    public external operator fun plus(other: UShort): ULong

    public external operator fun plus(other: UInt): ULong

    public external operator fun plus(other: ULong): ULong

    public external operator fun minus(other: UByte): ULong

    public external operator fun minus(other: UShort): ULong

    public external operator fun minus(other: UInt): ULong

    public external operator fun minus(other: ULong): ULong

    public external operator fun times(other: UByte): ULong

    public external operator fun times(other: UShort): ULong

    public external operator fun times(other: UInt): ULong

    public external operator fun times(other: ULong): ULong

    public external operator fun div(other: UByte): ULong

    public external operator fun div(other: UShort): ULong

    public external operator fun div(other: UInt): ULong

    public external operator fun div(other: ULong): ULong

    public external operator fun rem(other: UByte): ULong

    public external operator fun rem(other: UShort): ULong

    public external operator fun rem(other: UInt): ULong

    public external operator fun rem(other: ULong): ULong

    public external operator fun inc(): ULong

    public external operator fun dec(): ULong

    public external infix fun and(other: ULong): ULong

    public external infix fun or(other: ULong): ULong

    public external infix fun xor(other: ULong): ULong

    public external fun inv(): ULong

    public external infix fun shl(bitCount: Int): ULong

    public external infix fun shr(bitCount: Int): ULong

    public external fun toByte(): Byte

    public external fun toShort(): Short

    public external fun toInt(): Int

    public external fun toLong(): Long

    public external fun toFloat(): Float

    public external fun toDouble(): Double

    public external fun toUByte(): UByte

    public external fun toUShort(): UShort

    public external fun toUInt(): UInt

    public external fun toULong(): ULong



    public companion object {
        public const val MIN_VALUE: ULong = 0uL

        public const val MAX_VALUE: ULong = 18446744073709551615uL
    }
}

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

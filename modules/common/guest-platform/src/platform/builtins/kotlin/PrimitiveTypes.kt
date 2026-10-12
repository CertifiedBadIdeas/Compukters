/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

/**
 * Common supertype of the signed numeric primitives.
 *
 * The current Guest API exposes conversions on concrete numeric types rather than on [Number].
 * Unsigned primitives are separate types and do not inherit from this class.
 */
public abstract class Number

/**
 * A truth value, either true or false.
 *
 * Use `&&` and `||` for short-circuit evaluation. The [and], [or] and [xor] functions evaluate
 * both operands before combining their values. Ordering places false before true.
 */
public class Boolean private constructor() : Comparable<Boolean> {
    /**
     * Compares this value with [other], placing false before true.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external override operator fun compareTo(other: Boolean): Int
    /**
     * Returns the logical negation of this value.
     *
     * @return false for true, or true for false.
     */
    public external operator fun not(): Boolean
    /**
     * Returns true when this value and [other] are both true.
     *
     * Both operands are evaluated; this function does not short-circuit.
     *
     * @param other value to combine with this value.
     * @return the logical result.
     */
    public external infix fun and(other: Boolean): Boolean
    /**
     * Returns true when at least one of this value and [other] is true.
     *
     * Both operands are evaluated; this function does not short-circuit.
     *
     * @param other value to combine with this value.
     * @return the logical result.
     */
    public external infix fun or(other: Boolean): Boolean
    /**
     * Returns true when exactly one of this value and [other] is true.
     *
     * Both operands are evaluated; this function does not short-circuit.
     *
     * @param other value to combine with this value.
     * @return the logical result.
     */
    public external infix fun xor(other: Boolean): Boolean
}

/**
 * A single unsigned 16-bit UTF-16 code unit.
 *
 * Values range from U+0000 through U+FFFF, including surrogate code units. A supplementary
 * Unicode character requires two Char values in a string. Operations returning Char wrap modulo 65,536;
 * [code] exposes the code unit as a non-negative [Int].
 */
public class Char private constructor() : Comparable<Char> {
    /**
     * Compares UTF-16 code units numerically.
     *
     * @param other code unit to compare with.
     * @return this [code] minus the code of [other].
     */
    public external override operator fun compareTo(other: Char): Int
    /**
     * Adds [other] to this UTF-16 code unit.
     *
     * The result wraps modulo 65,536 and may be a surrogate code unit.
     *
     * @param other signed code-unit offset.
     * @return the resulting code unit.
     */
    public external operator fun plus(other: Int): Char
    /**
     * Subtracts [other] from this UTF-16 code unit.
     *
     * The result wraps modulo 65,536 and may be a surrogate code unit.
     *
     * @param other signed code-unit offset.
     * @return the resulting code unit.
     */
    public external operator fun minus(other: Int): Char
    /**
     * Returns the signed distance between two UTF-16 code units.
     *
     * @param other code unit to subtract.
     * @return this [code] minus the code of [other], as an [Int].
     */
    public external operator fun minus(other: Char): Int
    /**
     * Returns the next UTF-16 code unit.
     *
     * The result wraps modulo 65,536 and may be a surrogate code unit.
     * @return the resulting code unit.
     */
    public external operator fun inc(): Char
    /**
     * Returns the previous UTF-16 code unit.
     *
     * The result wraps modulo 65,536 and may be a surrogate code unit.
     * @return the resulting code unit.
     */
    public external operator fun dec(): Char
    /**
     * The unsigned UTF-16 code-unit value, in the range 0 through 65,535.
     *
     * @see toInt
     */
    public external val code: Int
    /**
     * Returns this UTF-16 code unit as an [Int].
     *
     * @return the code unit in the range 0 through 65,535.
     */
    public external fun toInt(): Int
    public companion object {
        /**
         * The smallest representable UTF-16 code unit: U+0000.
         */
        public const val MIN_VALUE: Char = '\u0000'
        /**
         * The largest representable UTF-16 code unit: U+FFFF.
         */
        public const val MAX_VALUE: Char = '\uFFFF'
    }
}

/**
 * A signed 8-bit integer with values from -128 through 127.
 *
 * Arithmetic with Byte, Short or Int operands produces [Int]; wider operands determine the
 * result type shown in the signature. [inc], [dec] and conversion back to Byte wrap to 8 bits.
 * Bitwise extensions are available in `kotlin.experimental`.
 */
public class Byte private constructor() : Number(), Comparable<Byte> {
    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external override operator fun compareTo(other: Byte): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Short): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Int): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Long): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Float precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Float): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Double precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Double): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the Int sum.
     */
    public external operator fun plus(other: Byte): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the Int sum.
     */
    public external operator fun plus(other: Short): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the Int sum.
     */
    public external operator fun plus(other: Int): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the Long sum.
     */
    public external operator fun plus(other: Long): Long

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Float sum.
     */
    public external operator fun plus(other: Float): Float

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Double sum.
     */
    public external operator fun plus(other: Double): Double

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the Int difference.
     */
    public external operator fun minus(other: Byte): Int

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the Int difference.
     */
    public external operator fun minus(other: Short): Int

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the Int difference.
     */
    public external operator fun minus(other: Int): Int

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the Long difference.
     */
    public external operator fun minus(other: Long): Long

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Float difference.
     */
    public external operator fun minus(other: Float): Float

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Double difference.
     */
    public external operator fun minus(other: Double): Double

    /**
     * Multiplies this value by [other].
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the Int product.
     */
    public external operator fun times(other: Byte): Int

    /**
     * Multiplies this value by [other].
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the Int product.
     */
    public external operator fun times(other: Short): Int

    /**
     * Multiplies this value by [other].
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the Int product.
     */
    public external operator fun times(other: Int): Int

    /**
     * Multiplies this value by [other].
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the Long product.
     */
    public external operator fun times(other: Long): Long

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Float product.
     */
    public external operator fun times(other: Float): Float

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Double product.
     */
    public external operator fun times(other: Double): Double

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     *
     * @param other divisor.
     * @return the Int quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Byte): Int

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     *
     * @param other divisor.
     * @return the Int quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Short): Int

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     *
     * @param other divisor.
     * @return the Int quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Int): Int

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     *
     * @param other divisor.
     * @return the Long quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Long): Long

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Float quotient.
     */
    public external operator fun div(other: Float): Float

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Double quotient.
     */
    public external operator fun div(other: Double): Double

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Int remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Byte): Int

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Int remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Short): Int

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Int remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Int): Int

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Long remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Long): Long

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Float remainder.
     */
    public external operator fun rem(other: Float): Float

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Double remainder.
     */
    public external operator fun rem(other: Double): Double

    /**
     * Returns this value with the type shown in the signature.
     *
     * @return the resulting Int value.
     */
    public external operator fun unaryPlus(): Int

    /**
     * Returns the negation of this value.
     *
     * The result wraps to 32 bits on overflow.
     *
     * @return the resulting Int value.
     */
    public external operator fun unaryMinus(): Int

    /**
     * Returns this value plus one.
     *
     * The result wraps to 8 bits on overflow.
     *
     * @return the resulting Byte value.
     */
    public external operator fun inc(): Byte

    /**
     * Returns this value minus one.
     *
     * The result wraps to 8 bits on overflow.
     *
     * @return the resulting Byte value.
     */
    public external operator fun dec(): Byte

    /**
     * Returns this value unchanged.
     *
     * @return this Byte value.
     */
    public external fun toByte(): Byte

    /**
     * Converts this value to [Short].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Short value.
     */
    public external fun toShort(): Short

    /**
     * Converts this value to [Int].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Int value.
     */
    public external fun toInt(): Int

    /**
     * Converts this value to [Long].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Long value.
     */
    public external fun toLong(): Long

    /**
     * Converts this value to [Float].
     *
     * Uses the signed numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toFloat(): Float

    /**
     * Converts this value to [Double].
     *
     * Uses the signed numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toDouble(): Double

    /**
     * Converts this value to a UTF-16 code unit using its lowest 16 integer bits.
     *
     * Surrogate code units are retained without Unicode validation.
     *
     * @return the resulting [Char].
     */
    public external fun toChar(): Char

    /**
     * Converts this value to [UByte].
     *
     * Retains the lowest 8 bits, interpreted as an unsigned value.
     *
     * @return the converted UByte value.
     */
    public external fun toUByte(): UByte

    /**
     * Converts this value to [UShort].
     *
     * Non-negative values are preserved. Negative values wrap modulo 2^16.
     *
     * @return the converted UShort value.
     */
    public external fun toUShort(): UShort

    /**
     * Converts this value to [UInt].
     *
     * Non-negative values are preserved. Negative values wrap modulo 2^32.
     *
     * @return the converted UInt value.
     */
    public external fun toUInt(): UInt

    /**
     * Converts this value to [ULong].
     *
     * Non-negative values are preserved. Negative values wrap modulo 2^64.
     *
     * @return the converted ULong value.
     */
    public external fun toULong(): ULong



    public companion object {
        /**
         * The smallest representable signed integer value.
         */
        public const val MIN_VALUE: Byte = -128

        /**
         * The largest representable signed integer value.
         */
        public const val MAX_VALUE: Byte = 127
    }
}

/**
 * A signed 16-bit integer with values from -32,768 through 32,767.
 *
 * Arithmetic with Byte, Short or Int operands produces [Int]; wider operands determine the
 * result type shown in the signature. [inc], [dec] and conversion back to Short wrap to 16 bits.
 * Bitwise extensions are available in `kotlin.experimental`.
 */
public class Short private constructor() : Number(), Comparable<Short> {
    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Byte): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external override operator fun compareTo(other: Short): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Int): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Long): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Float precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Float): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Double precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Double): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the Int sum.
     */
    public external operator fun plus(other: Byte): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the Int sum.
     */
    public external operator fun plus(other: Short): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the Int sum.
     */
    public external operator fun plus(other: Int): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the Long sum.
     */
    public external operator fun plus(other: Long): Long

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Float sum.
     */
    public external operator fun plus(other: Float): Float

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Double sum.
     */
    public external operator fun plus(other: Double): Double

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the Int difference.
     */
    public external operator fun minus(other: Byte): Int

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the Int difference.
     */
    public external operator fun minus(other: Short): Int

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the Int difference.
     */
    public external operator fun minus(other: Int): Int

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the Long difference.
     */
    public external operator fun minus(other: Long): Long

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Float difference.
     */
    public external operator fun minus(other: Float): Float

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Double difference.
     */
    public external operator fun minus(other: Double): Double

    /**
     * Multiplies this value by [other].
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the Int product.
     */
    public external operator fun times(other: Byte): Int

    /**
     * Multiplies this value by [other].
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the Int product.
     */
    public external operator fun times(other: Short): Int

    /**
     * Multiplies this value by [other].
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the Int product.
     */
    public external operator fun times(other: Int): Int

    /**
     * Multiplies this value by [other].
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the Long product.
     */
    public external operator fun times(other: Long): Long

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Float product.
     */
    public external operator fun times(other: Float): Float

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Double product.
     */
    public external operator fun times(other: Double): Double

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     *
     * @param other divisor.
     * @return the Int quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Byte): Int

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     *
     * @param other divisor.
     * @return the Int quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Short): Int

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     *
     * @param other divisor.
     * @return the Int quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Int): Int

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     *
     * @param other divisor.
     * @return the Long quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Long): Long

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Float quotient.
     */
    public external operator fun div(other: Float): Float

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Double quotient.
     */
    public external operator fun div(other: Double): Double

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Int remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Byte): Int

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Int remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Short): Int

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Int remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Int): Int

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Long remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Long): Long

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Float remainder.
     */
    public external operator fun rem(other: Float): Float

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Double remainder.
     */
    public external operator fun rem(other: Double): Double

    /**
     * Returns this value with the type shown in the signature.
     *
     * @return the resulting Int value.
     */
    public external operator fun unaryPlus(): Int

    /**
     * Returns the negation of this value.
     *
     * The result wraps to 32 bits on overflow.
     *
     * @return the resulting Int value.
     */
    public external operator fun unaryMinus(): Int

    /**
     * Returns this value plus one.
     *
     * The result wraps to 16 bits on overflow.
     *
     * @return the resulting Short value.
     */
    public external operator fun inc(): Short

    /**
     * Returns this value minus one.
     *
     * The result wraps to 16 bits on overflow.
     *
     * @return the resulting Short value.
     */
    public external operator fun dec(): Short

    /**
     * Converts this value to [Byte].
     *
     * Retains the lowest 8 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Byte value.
     */
    public external fun toByte(): Byte

    /**
     * Returns this value unchanged.
     *
     * @return this Short value.
     */
    public external fun toShort(): Short

    /**
     * Converts this value to [Int].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Int value.
     */
    public external fun toInt(): Int

    /**
     * Converts this value to [Long].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Long value.
     */
    public external fun toLong(): Long

    /**
     * Converts this value to [Float].
     *
     * Uses the signed numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toFloat(): Float

    /**
     * Converts this value to [Double].
     *
     * Uses the signed numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toDouble(): Double

    /**
     * Converts this value to a UTF-16 code unit using its lowest 16 integer bits.
     *
     * Surrogate code units are retained without Unicode validation.
     *
     * @return the resulting [Char].
     */
    public external fun toChar(): Char

    /**
     * Converts this value to [UByte].
     *
     * Retains the lowest 8 bits, interpreted as an unsigned value.
     *
     * @return the converted UByte value.
     */
    public external fun toUByte(): UByte

    /**
     * Converts this value to [UShort].
     *
     * Retains the lowest 16 bits, interpreted as an unsigned value.
     *
     * @return the converted UShort value.
     */
    public external fun toUShort(): UShort

    /**
     * Converts this value to [UInt].
     *
     * Non-negative values are preserved. Negative values wrap modulo 2^32.
     *
     * @return the converted UInt value.
     */
    public external fun toUInt(): UInt

    /**
     * Converts this value to [ULong].
     *
     * Non-negative values are preserved. Negative values wrap modulo 2^64.
     *
     * @return the converted ULong value.
     */
    public external fun toULong(): ULong



    public companion object {
        /**
         * The smallest representable signed integer value.
         */
        public const val MIN_VALUE: Short = -32768

        /**
         * The largest representable signed integer value.
         */
        public const val MAX_VALUE: Short = 32767
    }
}

/**
 * A signed 32-bit integer with values from -2,147,483,648 through 2,147,483,647.
 *
 * Integer addition, subtraction and multiplication wrap on overflow. Division truncates
 * towards zero; integer division and remainder by zero throw [ArithmeticException]. Shift
 * counts use only their lowest five bits. Mixed arithmetic uses the signature's result type.
 */
public class Int private constructor() : Number(), Comparable<Int> {
    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Byte): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Short): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external override operator fun compareTo(other: Int): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Long): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Float precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Float): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Double precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Double): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the Int sum.
     */
    public external operator fun plus(other: Byte): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the Int sum.
     */
    public external operator fun plus(other: Short): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the Int sum.
     */
    public external operator fun plus(other: Int): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the Long sum.
     */
    public external operator fun plus(other: Long): Long

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Float sum.
     */
    public external operator fun plus(other: Float): Float

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Double sum.
     */
    public external operator fun plus(other: Double): Double

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the Int difference.
     */
    public external operator fun minus(other: Byte): Int

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the Int difference.
     */
    public external operator fun minus(other: Short): Int

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the Int difference.
     */
    public external operator fun minus(other: Int): Int

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the Long difference.
     */
    public external operator fun minus(other: Long): Long

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Float difference.
     */
    public external operator fun minus(other: Float): Float

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Double difference.
     */
    public external operator fun minus(other: Double): Double

    /**
     * Multiplies this value by [other].
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the Int product.
     */
    public external operator fun times(other: Byte): Int

    /**
     * Multiplies this value by [other].
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the Int product.
     */
    public external operator fun times(other: Short): Int

    /**
     * Multiplies this value by [other].
     *
     * Operands use Int arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the Int product.
     */
    public external operator fun times(other: Int): Int

    /**
     * Multiplies this value by [other].
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the Long product.
     */
    public external operator fun times(other: Long): Long

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Float product.
     */
    public external operator fun times(other: Float): Float

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Double product.
     */
    public external operator fun times(other: Double): Double

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     * Dividing [Int.MIN_VALUE] by -1 returns MIN_VALUE through wrapping overflow.
     *
     * @param other divisor.
     * @return the Int quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Byte): Int

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     * Dividing [Int.MIN_VALUE] by -1 returns MIN_VALUE through wrapping overflow.
     *
     * @param other divisor.
     * @return the Int quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Short): Int

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     * Dividing [Int.MIN_VALUE] by -1 returns MIN_VALUE through wrapping overflow.
     *
     * @param other divisor.
     * @return the Int quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Int): Int

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     *
     * @param other divisor.
     * @return the Long quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Long): Long

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Float quotient.
     */
    public external operator fun div(other: Float): Float

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Double quotient.
     */
    public external operator fun div(other: Double): Double

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Int remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Byte): Int

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Int remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Short): Int

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Int remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Int): Int

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Long remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Long): Long

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Float remainder.
     */
    public external operator fun rem(other: Float): Float

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Double remainder.
     */
    public external operator fun rem(other: Double): Double

    /**
     * Returns this value with the type shown in the signature.
     *
     * @return the resulting Int value.
     */
    public external operator fun unaryPlus(): Int

    /**
     * Returns the negation of this value.
     *
     * The result wraps to 32 bits on overflow.
     *
     * @return the resulting Int value.
     */
    public external operator fun unaryMinus(): Int

    /**
     * Returns this value plus one.
     *
     * The result wraps to 32 bits on overflow.
     *
     * @return the resulting Int value.
     */
    public external operator fun inc(): Int

    /**
     * Returns this value minus one.
     *
     * The result wraps to 32 bits on overflow.
     *
     * @return the resulting Int value.
     */
    public external operator fun dec(): Int

    /**
     * Returns the bitwise AND of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting Int bit pattern.
     */
    public external infix fun and(other: Int): Int

    /**
     * Returns the bitwise OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting Int bit pattern.
     */
    public external infix fun or(other: Int): Int

    /**
     * Returns the bitwise exclusive OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting Int bit pattern.
     */
    public external infix fun xor(other: Int): Int

    /**
     * Inverts all 32 bits of this value.
     * @return the resulting Int bit pattern.
     */
    public external fun inv(): Int

    /**
     * Shifts this value left, discarding bits that leave the value and filling low bits with zeros.
     *
     * The effective count is `bitCount and 31`, including for negative counts.
     *
     * @param bitCount requested shift distance; only the lowest 5 bits are used.
     * @return the shifted value.
     */
    public external infix fun shl(bitCount: Int): Int

    /**
     * Shifts this value right, filling high bits with the sign bit.
     *
     * The effective count is `bitCount and 31`, including for negative counts.
     *
     * @param bitCount requested shift distance; only the lowest 5 bits are used.
     * @return the shifted value.
     */
    public external infix fun shr(bitCount: Int): Int

    /**
     * Shifts this value right, filling high bits with zeros.
     *
     * The effective count is `bitCount and 31`, including for negative counts.
     *
     * @param bitCount requested shift distance; only the lowest 5 bits are used.
     * @return the shifted value.
     */
    public external infix fun ushr(bitCount: Int): Int

    /**
     * Converts this value to [Byte].
     *
     * Retains the lowest 8 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Byte value.
     */
    public external fun toByte(): Byte

    /**
     * Converts this value to [Short].
     *
     * Retains the lowest 16 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Short value.
     */
    public external fun toShort(): Short

    /**
     * Returns this value unchanged.
     *
     * @return this Int value.
     */
    public external fun toInt(): Int

    /**
     * Converts this value to [Long].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Long value.
     */
    public external fun toLong(): Long

    /**
     * Converts this value to [Float].
     *
     * Uses the signed numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toFloat(): Float

    /**
     * Converts this value to [Double].
     *
     * Uses the signed numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toDouble(): Double

    /**
     * Converts this value to a UTF-16 code unit using its lowest 16 integer bits.
     *
     * Surrogate code units are retained without Unicode validation.
     *
     * @return the resulting [Char].
     */
    public external fun toChar(): Char

    /**
     * Converts this value to [UByte].
     *
     * Retains the lowest 8 bits, interpreted as an unsigned value.
     *
     * @return the converted UByte value.
     */
    public external fun toUByte(): UByte

    /**
     * Converts this value to [UShort].
     *
     * Retains the lowest 16 bits, interpreted as an unsigned value.
     *
     * @return the converted UShort value.
     */
    public external fun toUShort(): UShort

    /**
     * Converts this value to [UInt].
     *
     * Retains the lowest 32 bits, interpreted as an unsigned value.
     *
     * @return the converted UInt value.
     */
    public external fun toUInt(): UInt

    /**
     * Converts this value to [ULong].
     *
     * Non-negative values are preserved. Negative values wrap modulo 2^64.
     *
     * @return the converted ULong value.
     */
    public external fun toULong(): ULong



    public companion object {
        /**
         * The smallest representable signed integer value.
         */
        public const val MIN_VALUE: Int = -2147483647 - 1

        /**
         * The largest representable signed integer value.
         */
        public const val MAX_VALUE: Int = 2147483647
    }
}

/**
 * A signed 64-bit integer with values from -9,223,372,036,854,775,808 through 9,223,372,036,854,775,807.
 *
 * Integer addition, subtraction and multiplication wrap on overflow. Division truncates
 * towards zero; integer division and remainder by zero throw [ArithmeticException]. Shift
 * counts use only their lowest six bits. Floating arithmetic may lose integer precision.
 */
public class Long private constructor() : Number(), Comparable<Long> {
    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Byte): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Short): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Int): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external override operator fun compareTo(other: Long): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Float precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Float): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Double precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Double): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the Long sum.
     */
    public external operator fun plus(other: Byte): Long

    /**
     * Adds [other] to this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the Long sum.
     */
    public external operator fun plus(other: Short): Long

    /**
     * Adds [other] to this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the Long sum.
     */
    public external operator fun plus(other: Int): Long

    /**
     * Adds [other] to this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the Long sum.
     */
    public external operator fun plus(other: Long): Long

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Float sum.
     */
    public external operator fun plus(other: Float): Float

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Double sum.
     */
    public external operator fun plus(other: Double): Double

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the Long difference.
     */
    public external operator fun minus(other: Byte): Long

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the Long difference.
     */
    public external operator fun minus(other: Short): Long

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the Long difference.
     */
    public external operator fun minus(other: Int): Long

    /**
     * Subtracts [other] from this value.
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the Long difference.
     */
    public external operator fun minus(other: Long): Long

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Float difference.
     */
    public external operator fun minus(other: Float): Float

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Double difference.
     */
    public external operator fun minus(other: Double): Double

    /**
     * Multiplies this value by [other].
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the Long product.
     */
    public external operator fun times(other: Byte): Long

    /**
     * Multiplies this value by [other].
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the Long product.
     */
    public external operator fun times(other: Short): Long

    /**
     * Multiplies this value by [other].
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the Long product.
     */
    public external operator fun times(other: Int): Long

    /**
     * Multiplies this value by [other].
     *
     * Operands use Long arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the Long product.
     */
    public external operator fun times(other: Long): Long

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Float product.
     */
    public external operator fun times(other: Float): Float

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Double product.
     */
    public external operator fun times(other: Double): Double

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     * Dividing [Long.MIN_VALUE] by -1 returns MIN_VALUE through wrapping overflow.
     *
     * @param other divisor.
     * @return the Long quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Byte): Long

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     * Dividing [Long.MIN_VALUE] by -1 returns MIN_VALUE through wrapping overflow.
     *
     * @param other divisor.
     * @return the Long quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Short): Long

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     * Dividing [Long.MIN_VALUE] by -1 returns MIN_VALUE through wrapping overflow.
     *
     * @param other divisor.
     * @return the Long quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Int): Long

    /**
     * Divides this value by [other].
     *
     * The quotient truncates towards zero.
     * Dividing [Long.MIN_VALUE] by -1 returns MIN_VALUE through wrapping overflow.
     *
     * @param other divisor.
     * @return the Long quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: Long): Long

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Float quotient.
     */
    public external operator fun div(other: Float): Float

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Double quotient.
     */
    public external operator fun div(other: Double): Double

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Long remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Byte): Long

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Long remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Short): Long

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Long remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Int): Long

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * The quotient truncates towards zero; a nonzero remainder has the dividend's sign.
     *
     * @param other divisor.
     * @return the Long remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: Long): Long

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Float remainder.
     */
    public external operator fun rem(other: Float): Float

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Double remainder.
     */
    public external operator fun rem(other: Double): Double

    /**
     * Returns this value with the type shown in the signature.
     *
     * @return the resulting Long value.
     */
    public external operator fun unaryPlus(): Long

    /**
     * Returns the negation of this value.
     *
     * The result wraps to 64 bits on overflow.
     *
     * @return the resulting Long value.
     */
    public external operator fun unaryMinus(): Long

    /**
     * Returns this value plus one.
     *
     * The result wraps to 64 bits on overflow.
     *
     * @return the resulting Long value.
     */
    public external operator fun inc(): Long

    /**
     * Returns this value minus one.
     *
     * The result wraps to 64 bits on overflow.
     *
     * @return the resulting Long value.
     */
    public external operator fun dec(): Long

    /**
     * Returns the bitwise AND of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting Long bit pattern.
     */
    public external infix fun and(other: Long): Long

    /**
     * Returns the bitwise OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting Long bit pattern.
     */
    public external infix fun or(other: Long): Long

    /**
     * Returns the bitwise exclusive OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting Long bit pattern.
     */
    public external infix fun xor(other: Long): Long

    /**
     * Inverts all 64 bits of this value.
     * @return the resulting Long bit pattern.
     */
    public external fun inv(): Long

    /**
     * Shifts this value left, discarding bits that leave the value and filling low bits with zeros.
     *
     * The effective count is `bitCount and 63`, including for negative counts.
     *
     * @param bitCount requested shift distance; only the lowest 6 bits are used.
     * @return the shifted value.
     */
    public external infix fun shl(bitCount: Int): Long

    /**
     * Shifts this value right, filling high bits with the sign bit.
     *
     * The effective count is `bitCount and 63`, including for negative counts.
     *
     * @param bitCount requested shift distance; only the lowest 6 bits are used.
     * @return the shifted value.
     */
    public external infix fun shr(bitCount: Int): Long

    /**
     * Shifts this value right, filling high bits with zeros.
     *
     * The effective count is `bitCount and 63`, including for negative counts.
     *
     * @param bitCount requested shift distance; only the lowest 6 bits are used.
     * @return the shifted value.
     */
    public external infix fun ushr(bitCount: Int): Long

    /**
     * Converts this value to [Byte].
     *
     * Retains the lowest 8 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Byte value.
     */
    public external fun toByte(): Byte

    /**
     * Converts this value to [Short].
     *
     * Retains the lowest 16 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Short value.
     */
    public external fun toShort(): Short

    /**
     * Converts this value to [Int].
     *
     * Retains the lowest 32 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Int value.
     */
    public external fun toInt(): Int

    /**
     * Returns this value unchanged.
     *
     * @return this Long value.
     */
    public external fun toLong(): Long

    /**
     * Converts this value to [Float].
     *
     * Uses the signed numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toFloat(): Float

    /**
     * Converts this value to [Double].
     *
     * Uses the signed numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toDouble(): Double

    /**
     * Converts this value to a UTF-16 code unit using its lowest 16 integer bits.
     *
     * Surrogate code units are retained without Unicode validation.
     *
     * @return the resulting [Char].
     */
    public external fun toChar(): Char

    /**
     * Converts this value to [UByte].
     *
     * Retains the lowest 8 bits, interpreted as an unsigned value.
     *
     * @return the converted UByte value.
     */
    public external fun toUByte(): UByte

    /**
     * Converts this value to [UShort].
     *
     * Retains the lowest 16 bits, interpreted as an unsigned value.
     *
     * @return the converted UShort value.
     */
    public external fun toUShort(): UShort

    /**
     * Converts this value to [UInt].
     *
     * Retains the lowest 32 bits, interpreted as an unsigned value.
     *
     * @return the converted UInt value.
     */
    public external fun toUInt(): UInt

    /**
     * Converts this value to [ULong].
     *
     * Retains the lowest 64 bits, interpreted as an unsigned value.
     *
     * @return the converted ULong value.
     */
    public external fun toULong(): ULong



    public companion object {
        /**
         * The smallest representable signed integer value.
         */
        public const val MIN_VALUE: Long = -9223372036854775807L - 1L

        /**
         * The largest representable signed integer value.
         */
        public const val MAX_VALUE: Long = 9223372036854775807L
    }
}

/**
 * An IEEE 754 binary32 floating-point value.
 *
 * Supports finite values, signed zeros, infinities and NaN. Arithmetic rounds to Float precision
 * unless a Double operand promotes the result. [MIN_VALUE] is the smallest positive subnormal
 * value, not the most negative value. [compareTo] orders NaN above numbers and negative zero
 * below positive zero; primitive equality treats NaN as unequal and both zeros as equal.
 */
public class Float private constructor() : Number(), Comparable<Float> {
    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Float precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Byte): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Float precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Short): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Float precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Int): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Float precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Long): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Float precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external override operator fun compareTo(other: Float): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Double precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Double): Int

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Float sum.
     */
    public external operator fun plus(other: Byte): Float

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Float sum.
     */
    public external operator fun plus(other: Short): Float

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Float sum.
     */
    public external operator fun plus(other: Int): Float

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Float sum.
     */
    public external operator fun plus(other: Long): Float

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Float sum.
     */
    public external operator fun plus(other: Float): Float

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Double sum.
     */
    public external operator fun plus(other: Double): Double

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Float difference.
     */
    public external operator fun minus(other: Byte): Float

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Float difference.
     */
    public external operator fun minus(other: Short): Float

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Float difference.
     */
    public external operator fun minus(other: Int): Float

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Float difference.
     */
    public external operator fun minus(other: Long): Float

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Float difference.
     */
    public external operator fun minus(other: Float): Float

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Double difference.
     */
    public external operator fun minus(other: Double): Double

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Float product.
     */
    public external operator fun times(other: Byte): Float

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Float product.
     */
    public external operator fun times(other: Short): Float

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Float product.
     */
    public external operator fun times(other: Int): Float

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Float product.
     */
    public external operator fun times(other: Long): Float

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Float product.
     */
    public external operator fun times(other: Float): Float

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Double product.
     */
    public external operator fun times(other: Double): Double

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Float quotient.
     */
    public external operator fun div(other: Byte): Float

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Float quotient.
     */
    public external operator fun div(other: Short): Float

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Float quotient.
     */
    public external operator fun div(other: Int): Float

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Float quotient.
     */
    public external operator fun div(other: Long): Float

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Float quotient.
     */
    public external operator fun div(other: Float): Float

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Double quotient.
     */
    public external operator fun div(other: Double): Double

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Float remainder.
     */
    public external operator fun rem(other: Byte): Float

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Float remainder.
     */
    public external operator fun rem(other: Short): Float

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Float remainder.
     */
    public external operator fun rem(other: Int): Float

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Float remainder.
     */
    public external operator fun rem(other: Long): Float

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Float before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Float remainder.
     */
    public external operator fun rem(other: Float): Float

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Double remainder.
     */
    public external operator fun rem(other: Double): Double

    /**
     * Returns this value with the type shown in the signature.
     *
     * @return the resulting Float value.
     */
    public external operator fun unaryPlus(): Float

    /**
     * Returns the negation of this value.
     *
     * Floating results follow IEEE arithmetic.
     *
     * @return the resulting Float value.
     */
    public external operator fun unaryMinus(): Float

    /**
     * Returns this value plus one.
     *
     * The result rounds to Float precision and can remain unchanged for large finite values.
     *
     * @return the resulting Float value.
     */
    public external operator fun inc(): Float

    /**
     * Returns this value minus one.
     *
     * The result rounds to Float precision and can remain unchanged for large finite values.
     *
     * @return the resulting Float value.
     */
    public external operator fun dec(): Float

    /**
     * Converts this value to [Byte].
     *
     * Truncates towards zero and saturates to the [Int] range; NaN becomes zero.
     * Then retains the lowest 8 bits, interpreted as a signed Byte value.
     *
     * @return the converted Byte value.
     */
    public external fun toByte(): Byte

    /**
     * Converts this value to [Short].
     *
     * Truncates towards zero and saturates to the [Int] range; NaN becomes zero.
     * Then retains the lowest 16 bits, interpreted as a signed Short value.
     *
     * @return the converted Short value.
     */
    public external fun toShort(): Short

    /**
     * Converts this value to [Int].
     *
     * Truncates towards zero and saturates to the [Int] range; NaN becomes zero.
     *
     * @return the converted Int value.
     */
    public external fun toInt(): Int

    /**
     * Converts this value to [Long].
     *
     * Truncates towards zero and saturates to the [Long] range; NaN becomes zero.
     *
     * @return the converted Long value.
     */
    public external fun toLong(): Long

    /**
     * Returns this value unchanged.
     *
     * @return this Float value.
     */
    public external fun toFloat(): Float

    /**
     * Converts this value to [Double].
     *
     * Finite Float values and signed zeros are represented exactly; infinities and NaN are retained.
     *
     * @return the floating-point representation.
     */
    public external fun toDouble(): Double

    /**
     * Converts this value to a UTF-16 code unit using its lowest 16 integer bits.
     *
     * Floating input first truncates and saturates to Int, with NaN becoming zero.
     * Surrogate code units are retained without Unicode validation.
     *
     * @return the resulting [Char].
     */
    public external fun toChar(): Char

    /**
     * Converts this value to [UByte].
     *
     * Truncates towards zero and saturates to the [UInt] range; NaN becomes zero.
     * Then retains the lowest 8 bits, interpreted as an unsigned UByte value.
     *
     * @return the converted UByte value.
     */
    public external fun toUByte(): UByte

    /**
     * Converts this value to [UShort].
     *
     * Truncates towards zero and saturates to the [UInt] range; NaN becomes zero.
     * Then retains the lowest 16 bits, interpreted as an unsigned UShort value.
     *
     * @return the converted UShort value.
     */
    public external fun toUShort(): UShort

    /**
     * Converts this value to [UInt].
     *
     * Truncates towards zero and saturates to the [UInt] range; NaN becomes zero.
     *
     * @return the converted UInt value.
     */
    public external fun toUInt(): UInt

    /**
     * Converts this value to [ULong].
     *
     * Truncates towards zero and saturates to the [ULong] range; NaN becomes zero.
     *
     * @return the converted ULong value.
     */
    public external fun toULong(): ULong



    public companion object {
        /**
         * The smallest positive nonzero subnormal value, not the most negative value.
         */
        public const val MIN_VALUE: Float = 1.4E-45F

        /**
         * The largest finite positive value.
         */
        public const val MAX_VALUE: Float = 3.4028235E38F

        /**
         * Positive infinity, beyond every finite value in that direction.
         */
        public external val POSITIVE_INFINITY: Float

        /**
         * Negative infinity, beyond every finite value in that direction.
         */
        public external val NEGATIVE_INFINITY: Float

        /**
         * A not-a-number value.
         *
         * Primitive equality does not equate NaN with itself. [compareTo] does, and orders it above numbers.
         */
        public external val NaN: Float
    }
}

/**
 * An IEEE 754 binary64 floating-point value.
 *
 * Supports finite values, signed zeros, infinities and NaN. Mixed arithmetic promotes numeric
 * operands to Double and may round large integers. [MIN_VALUE] is the smallest positive
 * subnormal value, not the most negative value. [compareTo] orders NaN above numbers and
 * negative zero below positive zero; primitive equality treats NaN as unequal and both zeros
 * as equal.
 */
public class Double private constructor() : Number(), Comparable<Double> {
    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Double precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Byte): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Double precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Short): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Double precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Int): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Double precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Long): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Double precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: Float): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Operands use Double precision; large integers may round. NaN compares equal to NaN
     * and above every number. Negative zero compares below positive zero. This total order differs
     * from primitive equality and relational operators involving NaN or signed zero.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external override operator fun compareTo(other: Double): Int

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Double sum.
     */
    public external operator fun plus(other: Byte): Double

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Double sum.
     */
    public external operator fun plus(other: Short): Double

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Double sum.
     */
    public external operator fun plus(other: Int): Double

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Double sum.
     */
    public external operator fun plus(other: Long): Double

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Double sum.
     */
    public external operator fun plus(other: Float): Double

    /**
     * Adds [other] to this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to add.
     * @return the Double sum.
     */
    public external operator fun plus(other: Double): Double

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Double difference.
     */
    public external operator fun minus(other: Byte): Double

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Double difference.
     */
    public external operator fun minus(other: Short): Double

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Double difference.
     */
    public external operator fun minus(other: Int): Double

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Double difference.
     */
    public external operator fun minus(other: Long): Double

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Double difference.
     */
    public external operator fun minus(other: Float): Double

    /**
     * Subtracts [other] from this value.
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other value to subtract.
     * @return the Double difference.
     */
    public external operator fun minus(other: Double): Double

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Double product.
     */
    public external operator fun times(other: Byte): Double

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Double product.
     */
    public external operator fun times(other: Short): Double

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Double product.
     */
    public external operator fun times(other: Int): Double

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Double product.
     */
    public external operator fun times(other: Long): Double

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Double product.
     */
    public external operator fun times(other: Float): Double

    /**
     * Multiplies this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     *
     * @param other multiplier.
     * @return the Double product.
     */
    public external operator fun times(other: Double): Double

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Double quotient.
     */
    public external operator fun div(other: Byte): Double

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Double quotient.
     */
    public external operator fun div(other: Short): Double

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Double quotient.
     */
    public external operator fun div(other: Int): Double

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Double quotient.
     */
    public external operator fun div(other: Long): Double

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Double quotient.
     */
    public external operator fun div(other: Float): Double

    /**
     * Divides this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * A zero divisor produces an IEEE infinity or NaN, without an arithmetic exception.
     *
     * @param other divisor.
     * @return the Double quotient.
     */
    public external operator fun div(other: Double): Double

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Double remainder.
     */
    public external operator fun rem(other: Byte): Double

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Double remainder.
     */
    public external operator fun rem(other: Short): Double

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Double remainder.
     */
    public external operator fun rem(other: Int): Double

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Double remainder.
     */
    public external operator fun rem(other: Long): Double

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Double remainder.
     */
    public external operator fun rem(other: Float): Double

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Operands are converted to Double before the operation; integral precision can be lost.
     * Floating results follow IEEE arithmetic, including signed zeros, infinities and NaN.
     * Uses a quotient truncated towards zero, rather than IEEE nearest-integer remainder.
     * A zero divisor or infinite dividend produces NaN; a finite dividend modulo infinity is unchanged.
     *
     * @param other divisor.
     * @return the Double remainder.
     */
    public external operator fun rem(other: Double): Double

    /**
     * Returns this value with the type shown in the signature.
     *
     * @return the resulting Double value.
     */
    public external operator fun unaryPlus(): Double

    /**
     * Returns the negation of this value.
     *
     * Floating results follow IEEE arithmetic.
     *
     * @return the resulting Double value.
     */
    public external operator fun unaryMinus(): Double

    /**
     * Returns this value plus one.
     *
     * The result rounds to Double precision and can remain unchanged for large finite values.
     *
     * @return the resulting Double value.
     */
    public external operator fun inc(): Double

    /**
     * Returns this value minus one.
     *
     * The result rounds to Double precision and can remain unchanged for large finite values.
     *
     * @return the resulting Double value.
     */
    public external operator fun dec(): Double

    /**
     * Converts this value to [Byte].
     *
     * Truncates towards zero and saturates to the [Int] range; NaN becomes zero.
     * Then retains the lowest 8 bits, interpreted as a signed Byte value.
     *
     * @return the converted Byte value.
     */
    public external fun toByte(): Byte

    /**
     * Converts this value to [Short].
     *
     * Truncates towards zero and saturates to the [Int] range; NaN becomes zero.
     * Then retains the lowest 16 bits, interpreted as a signed Short value.
     *
     * @return the converted Short value.
     */
    public external fun toShort(): Short

    /**
     * Converts this value to [Int].
     *
     * Truncates towards zero and saturates to the [Int] range; NaN becomes zero.
     *
     * @return the converted Int value.
     */
    public external fun toInt(): Int

    /**
     * Converts this value to [Long].
     *
     * Truncates towards zero and saturates to the [Long] range; NaN becomes zero.
     *
     * @return the converted Long value.
     */
    public external fun toLong(): Long

    /**
     * Converts this value to [Float].
     *
     * Rounding may lose precision. Values too large for Float become signed infinity;
     * very small values may round to signed zero. NaN and infinities are retained.
     *
     * @return the floating-point representation.
     */
    public external fun toFloat(): Float

    /**
     * Returns this value unchanged.
     *
     * @return this Double value.
     */
    public external fun toDouble(): Double

    /**
     * Converts this value to a UTF-16 code unit using its lowest 16 integer bits.
     *
     * Floating input first truncates and saturates to Int, with NaN becoming zero.
     * Surrogate code units are retained without Unicode validation.
     *
     * @return the resulting [Char].
     */
    public external fun toChar(): Char

    /**
     * Converts this value to [UByte].
     *
     * Truncates towards zero and saturates to the [UInt] range; NaN becomes zero.
     * Then retains the lowest 8 bits, interpreted as an unsigned UByte value.
     *
     * @return the converted UByte value.
     */
    public external fun toUByte(): UByte

    /**
     * Converts this value to [UShort].
     *
     * Truncates towards zero and saturates to the [UInt] range; NaN becomes zero.
     * Then retains the lowest 16 bits, interpreted as an unsigned UShort value.
     *
     * @return the converted UShort value.
     */
    public external fun toUShort(): UShort

    /**
     * Converts this value to [UInt].
     *
     * Truncates towards zero and saturates to the [UInt] range; NaN becomes zero.
     *
     * @return the converted UInt value.
     */
    public external fun toUInt(): UInt

    /**
     * Converts this value to [ULong].
     *
     * Truncates towards zero and saturates to the [ULong] range; NaN becomes zero.
     *
     * @return the converted ULong value.
     */
    public external fun toULong(): ULong



    public companion object {
        /**
         * The smallest positive nonzero subnormal value, not the most negative value.
         */
        public const val MIN_VALUE: Double = 4.9E-324

        /**
         * The largest finite positive value.
         */
        public const val MAX_VALUE: Double = 1.7976931348623157E308

        /**
         * Positive infinity, beyond every finite value in that direction.
         */
        public external val POSITIVE_INFINITY: Double

        /**
         * Negative infinity, beyond every finite value in that direction.
         */
        public external val NEGATIVE_INFINITY: Double

        /**
         * A not-a-number value.
         *
         * Primitive equality does not equate NaN with itself. [compareTo] does, and orders it above numbers.
         */
        public external val NaN: Double
    }
}

/**
 * An unsigned 8-bit integer with values from zero through 255.
 *
 * Arithmetic uses unsigned ordering, division and remainder, with wrapping on overflow.
 * Byte-sized and short-sized unsigned arithmetic produces [UInt] unless an operand is ULong.
 * Integer conversions retain the destination's low bits; signed interpretations may change the value.
 */
public class UByte private constructor() : Comparable<UByte> {
    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external override operator fun compareTo(other: UByte): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: UShort): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: UInt): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: ULong): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the UInt sum.
     */
    public external operator fun plus(other: UByte): UInt

    /**
     * Adds [other] to this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the UInt sum.
     */
    public external operator fun plus(other: UShort): UInt

    /**
     * Adds [other] to this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the UInt sum.
     */
    public external operator fun plus(other: UInt): UInt

    /**
     * Adds [other] to this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the ULong sum.
     */
    public external operator fun plus(other: ULong): ULong

    /**
     * Subtracts [other] from this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the UInt difference.
     */
    public external operator fun minus(other: UByte): UInt

    /**
     * Subtracts [other] from this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the UInt difference.
     */
    public external operator fun minus(other: UShort): UInt

    /**
     * Subtracts [other] from this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the UInt difference.
     */
    public external operator fun minus(other: UInt): UInt

    /**
     * Subtracts [other] from this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the ULong difference.
     */
    public external operator fun minus(other: ULong): ULong

    /**
     * Multiplies this value by [other].
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the UInt product.
     */
    public external operator fun times(other: UByte): UInt

    /**
     * Multiplies this value by [other].
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the UInt product.
     */
    public external operator fun times(other: UShort): UInt

    /**
     * Multiplies this value by [other].
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the UInt product.
     */
    public external operator fun times(other: UInt): UInt

    /**
     * Multiplies this value by [other].
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the ULong product.
     */
    public external operator fun times(other: ULong): ULong

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the UInt quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UByte): UInt

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the UInt quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UShort): UInt

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the UInt quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UInt): UInt

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the ULong quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: ULong): ULong

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the UInt remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UByte): UInt

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the UInt remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UShort): UInt

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the UInt remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UInt): UInt

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the ULong remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: ULong): ULong

    /**
     * Returns this value plus one.
     *
     * The result wraps to 8 bits on overflow.
     *
     * @return the resulting UByte value.
     */
    public external operator fun inc(): UByte

    /**
     * Returns this value minus one.
     *
     * The result wraps to 8 bits on overflow.
     *
     * @return the resulting UByte value.
     */
    public external operator fun dec(): UByte

    /**
     * Returns the bitwise AND of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting UByte bit pattern.
     */
    public external infix fun and(other: UByte): UByte

    /**
     * Returns the bitwise OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting UByte bit pattern.
     */
    public external infix fun or(other: UByte): UByte

    /**
     * Returns the bitwise exclusive OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting UByte bit pattern.
     */
    public external infix fun xor(other: UByte): UByte

    /**
     * Inverts all 8 bits of this value.
     * @return the resulting UByte bit pattern.
     */
    public external fun inv(): UByte

    /**
     * Converts this value to [Byte].
     *
     * Retains the lowest 8 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Byte value.
     */
    public external fun toByte(): Byte

    /**
     * Converts this value to [Short].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Short value.
     */
    public external fun toShort(): Short

    /**
     * Converts this value to [Int].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Int value.
     */
    public external fun toInt(): Int

    /**
     * Converts this value to [Long].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Long value.
     */
    public external fun toLong(): Long

    /**
     * Converts this value to [Float].
     *
     * Uses the unsigned numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toFloat(): Float

    /**
     * Converts this value to [Double].
     *
     * Uses the unsigned numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toDouble(): Double

    /**
     * Returns this value unchanged.
     *
     * @return this UByte value.
     */
    public external fun toUByte(): UByte

    /**
     * Converts this value to [UShort].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted UShort value.
     */
    public external fun toUShort(): UShort

    /**
     * Converts this value to [UInt].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted UInt value.
     */
    public external fun toUInt(): UInt

    /**
     * Converts this value to [ULong].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted ULong value.
     */
    public external fun toULong(): ULong



    public companion object {
        /**
         * The smallest representable unsigned value: zero.
         */
        public const val MIN_VALUE: UByte = 0u

        /**
         * The largest representable unsigned integer value.
         */
        public const val MAX_VALUE: UByte = 255u
    }
}

/**
 * An unsigned 16-bit integer with values from zero through 65,535.
 *
 * Arithmetic uses unsigned ordering, division and remainder, with wrapping on overflow.
 * Byte-sized and short-sized unsigned arithmetic produces [UInt] unless an operand is ULong.
 * Integer conversions retain the destination's low bits; signed interpretations may change the value.
 */
public class UShort private constructor() : Comparable<UShort> {
    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: UByte): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external override operator fun compareTo(other: UShort): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: UInt): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: ULong): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the UInt sum.
     */
    public external operator fun plus(other: UByte): UInt

    /**
     * Adds [other] to this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the UInt sum.
     */
    public external operator fun plus(other: UShort): UInt

    /**
     * Adds [other] to this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the UInt sum.
     */
    public external operator fun plus(other: UInt): UInt

    /**
     * Adds [other] to this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the ULong sum.
     */
    public external operator fun plus(other: ULong): ULong

    /**
     * Subtracts [other] from this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the UInt difference.
     */
    public external operator fun minus(other: UByte): UInt

    /**
     * Subtracts [other] from this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the UInt difference.
     */
    public external operator fun minus(other: UShort): UInt

    /**
     * Subtracts [other] from this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the UInt difference.
     */
    public external operator fun minus(other: UInt): UInt

    /**
     * Subtracts [other] from this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the ULong difference.
     */
    public external operator fun minus(other: ULong): ULong

    /**
     * Multiplies this value by [other].
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the UInt product.
     */
    public external operator fun times(other: UByte): UInt

    /**
     * Multiplies this value by [other].
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the UInt product.
     */
    public external operator fun times(other: UShort): UInt

    /**
     * Multiplies this value by [other].
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the UInt product.
     */
    public external operator fun times(other: UInt): UInt

    /**
     * Multiplies this value by [other].
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the ULong product.
     */
    public external operator fun times(other: ULong): ULong

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the UInt quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UByte): UInt

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the UInt quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UShort): UInt

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the UInt quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UInt): UInt

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the ULong quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: ULong): ULong

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the UInt remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UByte): UInt

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the UInt remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UShort): UInt

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the UInt remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UInt): UInt

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the ULong remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: ULong): ULong

    /**
     * Returns this value plus one.
     *
     * The result wraps to 16 bits on overflow.
     *
     * @return the resulting UShort value.
     */
    public external operator fun inc(): UShort

    /**
     * Returns this value minus one.
     *
     * The result wraps to 16 bits on overflow.
     *
     * @return the resulting UShort value.
     */
    public external operator fun dec(): UShort

    /**
     * Returns the bitwise AND of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting UShort bit pattern.
     */
    public external infix fun and(other: UShort): UShort

    /**
     * Returns the bitwise OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting UShort bit pattern.
     */
    public external infix fun or(other: UShort): UShort

    /**
     * Returns the bitwise exclusive OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting UShort bit pattern.
     */
    public external infix fun xor(other: UShort): UShort

    /**
     * Inverts all 16 bits of this value.
     * @return the resulting UShort bit pattern.
     */
    public external fun inv(): UShort

    /**
     * Converts this value to [Byte].
     *
     * Retains the lowest 8 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Byte value.
     */
    public external fun toByte(): Byte

    /**
     * Converts this value to [Short].
     *
     * Retains the lowest 16 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Short value.
     */
    public external fun toShort(): Short

    /**
     * Converts this value to [Int].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Int value.
     */
    public external fun toInt(): Int

    /**
     * Converts this value to [Long].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Long value.
     */
    public external fun toLong(): Long

    /**
     * Converts this value to [Float].
     *
     * Uses the unsigned numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toFloat(): Float

    /**
     * Converts this value to [Double].
     *
     * Uses the unsigned numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toDouble(): Double

    /**
     * Converts this value to [UByte].
     *
     * Retains the lowest 8 bits, interpreted as an unsigned value.
     *
     * @return the converted UByte value.
     */
    public external fun toUByte(): UByte

    /**
     * Returns this value unchanged.
     *
     * @return this UShort value.
     */
    public external fun toUShort(): UShort

    /**
     * Converts this value to [UInt].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted UInt value.
     */
    public external fun toUInt(): UInt

    /**
     * Converts this value to [ULong].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted ULong value.
     */
    public external fun toULong(): ULong



    public companion object {
        /**
         * The smallest representable unsigned value: zero.
         */
        public const val MIN_VALUE: UShort = 0u

        /**
         * The largest representable unsigned integer value.
         */
        public const val MAX_VALUE: UShort = 65535u
    }
}

/**
 * An unsigned 32-bit integer with values from zero through 4,294,967,295.
 *
 * Integer arithmetic wraps on overflow. Division and remainder use unsigned magnitudes and
 * throw [ArithmeticException] for a zero divisor. Shifts fill with zeros and mask the count
 * to its lowest five bits. Signed conversions can reinterpret the high bit as a sign bit.
 */
public class UInt private constructor() : Comparable<UInt> {
    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: UByte): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: UShort): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external override operator fun compareTo(other: UInt): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: ULong): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the UInt sum.
     */
    public external operator fun plus(other: UByte): UInt

    /**
     * Adds [other] to this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the UInt sum.
     */
    public external operator fun plus(other: UShort): UInt

    /**
     * Adds [other] to this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to add.
     * @return the UInt sum.
     */
    public external operator fun plus(other: UInt): UInt

    /**
     * Adds [other] to this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the ULong sum.
     */
    public external operator fun plus(other: ULong): ULong

    /**
     * Subtracts [other] from this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the UInt difference.
     */
    public external operator fun minus(other: UByte): UInt

    /**
     * Subtracts [other] from this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the UInt difference.
     */
    public external operator fun minus(other: UShort): UInt

    /**
     * Subtracts [other] from this value.
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other value to subtract.
     * @return the UInt difference.
     */
    public external operator fun minus(other: UInt): UInt

    /**
     * Subtracts [other] from this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the ULong difference.
     */
    public external operator fun minus(other: ULong): ULong

    /**
     * Multiplies this value by [other].
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the UInt product.
     */
    public external operator fun times(other: UByte): UInt

    /**
     * Multiplies this value by [other].
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the UInt product.
     */
    public external operator fun times(other: UShort): UInt

    /**
     * Multiplies this value by [other].
     *
     * Operands use UInt arithmetic, wrapping modulo 2^32 on overflow.
     *
     * @param other multiplier.
     * @return the UInt product.
     */
    public external operator fun times(other: UInt): UInt

    /**
     * Multiplies this value by [other].
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the ULong product.
     */
    public external operator fun times(other: ULong): ULong

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the UInt quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UByte): UInt

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the UInt quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UShort): UInt

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the UInt quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UInt): UInt

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the ULong quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: ULong): ULong

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the UInt remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UByte): UInt

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the UInt remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UShort): UInt

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the UInt remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UInt): UInt

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the ULong remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: ULong): ULong

    /**
     * Returns this value plus one.
     *
     * The result wraps to 32 bits on overflow.
     *
     * @return the resulting UInt value.
     */
    public external operator fun inc(): UInt

    /**
     * Returns this value minus one.
     *
     * The result wraps to 32 bits on overflow.
     *
     * @return the resulting UInt value.
     */
    public external operator fun dec(): UInt

    /**
     * Returns the bitwise AND of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting UInt bit pattern.
     */
    public external infix fun and(other: UInt): UInt

    /**
     * Returns the bitwise OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting UInt bit pattern.
     */
    public external infix fun or(other: UInt): UInt

    /**
     * Returns the bitwise exclusive OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting UInt bit pattern.
     */
    public external infix fun xor(other: UInt): UInt

    /**
     * Inverts all 32 bits of this value.
     * @return the resulting UInt bit pattern.
     */
    public external fun inv(): UInt

    /**
     * Shifts this value left, discarding bits that leave the value and filling low bits with zeros.
     *
     * The effective count is `bitCount and 31`, including for negative counts.
     *
     * @param bitCount requested shift distance; only the lowest 5 bits are used.
     * @return the shifted value.
     */
    public external infix fun shl(bitCount: Int): UInt

    /**
     * Shifts this value right, filling high bits with zeros.
     *
     * The effective count is `bitCount and 31`, including for negative counts.
     *
     * @param bitCount requested shift distance; only the lowest 5 bits are used.
     * @return the shifted value.
     */
    public external infix fun shr(bitCount: Int): UInt

    /**
     * Converts this value to [Byte].
     *
     * Retains the lowest 8 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Byte value.
     */
    public external fun toByte(): Byte

    /**
     * Converts this value to [Short].
     *
     * Retains the lowest 16 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Short value.
     */
    public external fun toShort(): Short

    /**
     * Converts this value to [Int].
     *
     * Retains the lowest 32 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Int value.
     */
    public external fun toInt(): Int

    /**
     * Converts this value to [Long].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted Long value.
     */
    public external fun toLong(): Long

    /**
     * Converts this value to [Float].
     *
     * Uses the unsigned numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toFloat(): Float

    /**
     * Converts this value to [Double].
     *
     * Uses the unsigned numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toDouble(): Double

    /**
     * Converts this value to [UByte].
     *
     * Retains the lowest 8 bits, interpreted as an unsigned value.
     *
     * @return the converted UByte value.
     */
    public external fun toUByte(): UByte

    /**
     * Converts this value to [UShort].
     *
     * Retains the lowest 16 bits, interpreted as an unsigned value.
     *
     * @return the converted UShort value.
     */
    public external fun toUShort(): UShort

    /**
     * Returns this value unchanged.
     *
     * @return this UInt value.
     */
    public external fun toUInt(): UInt

    /**
     * Converts this value to [ULong].
     *
     * Widening preserves the numeric value.
     *
     * @return the converted ULong value.
     */
    public external fun toULong(): ULong



    public companion object {
        /**
         * The smallest representable unsigned value: zero.
         */
        public const val MIN_VALUE: UInt = 0u

        /**
         * The largest representable unsigned integer value.
         */
        public const val MAX_VALUE: UInt = 4294967295u
    }
}

/**
 * An unsigned 64-bit integer with values from zero through 18,446,744,073,709,551,615.
 *
 * Integer arithmetic wraps on overflow. Division and remainder use unsigned magnitudes and
 * throw [ArithmeticException] for a zero divisor. Shifts fill with zeros and mask the count
 * to its lowest six bits. Signed conversions can reinterpret the high bit as a sign bit.
 */
public class ULong private constructor() : Comparable<ULong> {
    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: UByte): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: UShort): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external operator fun compareTo(other: UInt): Int

    /**
     * Compares this value with [other] after numeric promotion.
     *
     * Uses unsigned magnitudes without interpreting the high bit as a sign.
     *
     * @param other value to compare with.
     * @return -1, 0 or 1 when this value is less than, equal to or greater than [other].
     */
    public external override operator fun compareTo(other: ULong): Int

    /**
     * Adds [other] to this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the ULong sum.
     */
    public external operator fun plus(other: UByte): ULong

    /**
     * Adds [other] to this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the ULong sum.
     */
    public external operator fun plus(other: UShort): ULong

    /**
     * Adds [other] to this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the ULong sum.
     */
    public external operator fun plus(other: UInt): ULong

    /**
     * Adds [other] to this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to add.
     * @return the ULong sum.
     */
    public external operator fun plus(other: ULong): ULong

    /**
     * Subtracts [other] from this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the ULong difference.
     */
    public external operator fun minus(other: UByte): ULong

    /**
     * Subtracts [other] from this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the ULong difference.
     */
    public external operator fun minus(other: UShort): ULong

    /**
     * Subtracts [other] from this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the ULong difference.
     */
    public external operator fun minus(other: UInt): ULong

    /**
     * Subtracts [other] from this value.
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other value to subtract.
     * @return the ULong difference.
     */
    public external operator fun minus(other: ULong): ULong

    /**
     * Multiplies this value by [other].
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the ULong product.
     */
    public external operator fun times(other: UByte): ULong

    /**
     * Multiplies this value by [other].
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the ULong product.
     */
    public external operator fun times(other: UShort): ULong

    /**
     * Multiplies this value by [other].
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the ULong product.
     */
    public external operator fun times(other: UInt): ULong

    /**
     * Multiplies this value by [other].
     *
     * Operands use ULong arithmetic, wrapping modulo 2^64 on overflow.
     *
     * @param other multiplier.
     * @return the ULong product.
     */
    public external operator fun times(other: ULong): ULong

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the ULong quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UByte): ULong

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the ULong quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UShort): ULong

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the ULong quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: UInt): ULong

    /**
     * Divides this value by [other].
     *
     * Uses unsigned magnitudes and discards the fractional part.
     *
     * @param other divisor.
     * @return the ULong quotient.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun div(other: ULong): ULong

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the ULong remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UByte): ULong

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the ULong remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UShort): ULong

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the ULong remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: UInt): ULong

    /**
     * Returns the remainder after dividing this value by [other].
     *
     * Uses unsigned magnitudes; a nonzero remainder is smaller than the divisor.
     *
     * @param other divisor.
     * @return the ULong remainder.
     * @throws ArithmeticException if [other] is zero.
     */
    public external operator fun rem(other: ULong): ULong

    /**
     * Returns this value plus one.
     *
     * The result wraps to 64 bits on overflow.
     *
     * @return the resulting ULong value.
     */
    public external operator fun inc(): ULong

    /**
     * Returns this value minus one.
     *
     * The result wraps to 64 bits on overflow.
     *
     * @return the resulting ULong value.
     */
    public external operator fun dec(): ULong

    /**
     * Returns the bitwise AND of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting ULong bit pattern.
     */
    public external infix fun and(other: ULong): ULong

    /**
     * Returns the bitwise OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting ULong bit pattern.
     */
    public external infix fun or(other: ULong): ULong

    /**
     * Returns the bitwise exclusive OR of this value and [other].
     *
     * @param other bit pattern to combine with this value.
     * @return the resulting ULong bit pattern.
     */
    public external infix fun xor(other: ULong): ULong

    /**
     * Inverts all 64 bits of this value.
     * @return the resulting ULong bit pattern.
     */
    public external fun inv(): ULong

    /**
     * Shifts this value left, discarding bits that leave the value and filling low bits with zeros.
     *
     * The effective count is `bitCount and 63`, including for negative counts.
     *
     * @param bitCount requested shift distance; only the lowest 6 bits are used.
     * @return the shifted value.
     */
    public external infix fun shl(bitCount: Int): ULong

    /**
     * Shifts this value right, filling high bits with zeros.
     *
     * The effective count is `bitCount and 63`, including for negative counts.
     *
     * @param bitCount requested shift distance; only the lowest 6 bits are used.
     * @return the shifted value.
     */
    public external infix fun shr(bitCount: Int): ULong

    /**
     * Converts this value to [Byte].
     *
     * Retains the lowest 8 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Byte value.
     */
    public external fun toByte(): Byte

    /**
     * Converts this value to [Short].
     *
     * Retains the lowest 16 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Short value.
     */
    public external fun toShort(): Short

    /**
     * Converts this value to [Int].
     *
     * Retains the lowest 32 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Int value.
     */
    public external fun toInt(): Int

    /**
     * Converts this value to [Long].
     *
     * Retains the lowest 64 bits, interpreted as a signed two's-complement value.
     *
     * @return the converted Long value.
     */
    public external fun toLong(): Long

    /**
     * Converts this value to [Float].
     *
     * Uses the unsigned numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toFloat(): Float

    /**
     * Converts this value to [Double].
     *
     * Uses the unsigned numeric magnitude. Large integers may round to the destination precision.
     *
     * @return the floating-point representation.
     */
    public external fun toDouble(): Double

    /**
     * Converts this value to [UByte].
     *
     * Retains the lowest 8 bits, interpreted as an unsigned value.
     *
     * @return the converted UByte value.
     */
    public external fun toUByte(): UByte

    /**
     * Converts this value to [UShort].
     *
     * Retains the lowest 16 bits, interpreted as an unsigned value.
     *
     * @return the converted UShort value.
     */
    public external fun toUShort(): UShort

    /**
     * Converts this value to [UInt].
     *
     * Retains the lowest 32 bits, interpreted as an unsigned value.
     *
     * @return the converted UInt value.
     */
    public external fun toUInt(): UInt

    /**
     * Returns this value unchanged.
     *
     * @return this ULong value.
     */
    public external fun toULong(): ULong



    public companion object {
        /**
         * The smallest representable unsigned value: zero.
         */
        public const val MIN_VALUE: ULong = 0uL

        /**
         * The largest representable unsigned integer value.
         */
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

    public external override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): String
}

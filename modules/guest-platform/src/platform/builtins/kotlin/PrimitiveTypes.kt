/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

public class Boolean private constructor() : Comparable<Boolean> {
    public external override operator fun compareTo(other: Boolean): Int
}

public class Char private constructor() : Comparable<Char> {
    public external override operator fun compareTo(other: Char): Int

    public external fun toInt(): Int
}

public class Int private constructor() : Comparable<Int> {
    public external override operator fun compareTo(other: Int): Int

    public external operator fun plus(other: Int): Int

    public external operator fun minus(other: Int): Int

    public external operator fun times(other: Int): Int

    public external operator fun div(other: Int): Int

    public external operator fun rem(other: Int): Int

    public external operator fun unaryMinus(): Int

    public external infix fun and(other: Int): Int

    public external infix fun or(other: Int): Int

    public external infix fun xor(other: Int): Int

    public external fun inv(): Int

    public external infix fun shl(bitCount: Int): Int

    public external infix fun shr(bitCount: Int): Int

    public external infix fun ushr(bitCount: Int): Int

    public external fun toChar(): Char

    public companion object {
        public const val MIN_VALUE: Int = -2147483647 - 1
        public const val MAX_VALUE: Int = 2147483647
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
}

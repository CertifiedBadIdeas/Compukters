/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

import kotlin.collections.IntIterator
import kotlin.collections.DoubleIterator

public class Array<T> private constructor() {
    public external val size: Int

    public external operator fun get(index: Int): T

    public external operator fun set(index: Int, value: T): Unit
}

public class CharArray external constructor(size: Int) {
    public external val size: Int

    public external operator fun get(index: Int): Char

    public external operator fun set(index: Int, value: Char): Unit
}

internal class BooleanArray private constructor()

internal class ByteArray private constructor()

internal class ShortArray private constructor()

public class IntArray external constructor(size: Int) {
    public external val size: Int

    public external operator fun get(index: Int): Int

    public external operator fun set(index: Int, value: Int): Unit

    public external operator fun iterator(): IntIterator
}

internal class LongArray private constructor()

internal class FloatArray private constructor()

public class DoubleArray external constructor(size: Int) {
    public external val size: Int

    public external operator fun get(index: Int): Double

    public external operator fun set(index: Int, value: Double): Unit

    public external operator fun iterator(): DoubleIterator
}

internal class UByteArray private constructor()

internal class UShortArray private constructor()

internal class UIntArray private constructor()

internal class ULongArray private constructor()

public external fun <T> arrayOf(vararg elements: T): Array<T>

public external fun intArrayOf(vararg elements: Int): IntArray

public external fun doubleArrayOf(vararg elements: Double): DoubleArray

/** Creates an array of the requested size with every element set to null. */
public external fun <T> arrayOfNulls(size: Int): Array<T?>

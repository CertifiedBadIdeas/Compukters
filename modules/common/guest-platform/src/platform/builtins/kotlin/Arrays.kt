/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

import kotlin.collections.*

public class Array<T> private constructor() {
    public external val size: Int

    public external operator fun get(index: Int): T

    public external operator fun set(index: Int, value: T): Unit
}

public class BooleanArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> Boolean)

    public external val size: Int

    public external operator fun get(index: Int): Boolean

    public external operator fun set(index: Int, value: Boolean): Unit

    public external operator fun iterator(): BooleanIterator
}

public class ByteArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> Byte)

    public external val size: Int

    public external operator fun get(index: Int): Byte

    public external operator fun set(index: Int, value: Byte): Unit

    public external operator fun iterator(): ByteIterator
}

public class CharArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> Char)

    public external val size: Int

    public external operator fun get(index: Int): Char

    public external operator fun set(index: Int, value: Char): Unit

    public external operator fun iterator(): CharIterator
}

public class ShortArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> Short)

    public external val size: Int

    public external operator fun get(index: Int): Short

    public external operator fun set(index: Int, value: Short): Unit

    public external operator fun iterator(): ShortIterator
}

public class IntArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> Int)

    public external val size: Int

    public external operator fun get(index: Int): Int

    public external operator fun set(index: Int, value: Int): Unit

    public external operator fun iterator(): IntIterator
}

public class LongArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> Long)

    public external val size: Int

    public external operator fun get(index: Int): Long

    public external operator fun set(index: Int, value: Long): Unit

    public external operator fun iterator(): LongIterator
}

public class FloatArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> Float)

    public external val size: Int

    public external operator fun get(index: Int): Float

    public external operator fun set(index: Int, value: Float): Unit

    public external operator fun iterator(): FloatIterator
}

public class DoubleArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> Double)

    public external val size: Int

    public external operator fun get(index: Int): Double

    public external operator fun set(index: Int, value: Double): Unit

    public external operator fun iterator(): DoubleIterator
}

public class UByteArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> UByte)

    public external val size: Int

    public external operator fun get(index: Int): UByte

    public external operator fun set(index: Int, value: UByte): Unit

    public external operator fun iterator(): Iterator<UByte>
}

public class UShortArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> UShort)

    public external val size: Int

    public external operator fun get(index: Int): UShort

    public external operator fun set(index: Int, value: UShort): Unit

    public external operator fun iterator(): Iterator<UShort>
}

public class UIntArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> UInt)

    public external val size: Int

    public external operator fun get(index: Int): UInt

    public external operator fun set(index: Int, value: UInt): Unit

    public external operator fun iterator(): Iterator<UInt>
}

public class ULongArray {
    public external constructor(size: Int)

    public external constructor(size: Int, init: (Int) -> ULong)

    public external val size: Int

    public external operator fun get(index: Int): ULong

    public external operator fun set(index: Int, value: ULong): Unit

    public external operator fun iterator(): Iterator<ULong>
}

public external fun <T> arrayOf(vararg elements: T): Array<T>

public external fun booleanArrayOf(vararg elements: Boolean): BooleanArray

public external fun byteArrayOf(vararg elements: Byte): ByteArray

public external fun charArrayOf(vararg elements: Char): CharArray

public external fun shortArrayOf(vararg elements: Short): ShortArray

public external fun intArrayOf(vararg elements: Int): IntArray

public external fun longArrayOf(vararg elements: Long): LongArray

public external fun floatArrayOf(vararg elements: Float): FloatArray

public external fun doubleArrayOf(vararg elements: Double): DoubleArray

public external fun ubyteArrayOf(vararg elements: UByte): UByteArray

public external fun ushortArrayOf(vararg elements: UShort): UShortArray

public external fun uintArrayOf(vararg elements: UInt): UIntArray

public external fun ulongArrayOf(vararg elements: ULong): ULongArray

/** Creates an array of the requested size with every element set to null. */
public external fun <T> arrayOfNulls(size: Int): Array<T?>

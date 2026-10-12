/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin

import kotlin.collections.*

/**
 * A fixed-size, mutable array of [T] elements.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The initializer constructor calls its callback once for each index, in ascending
 * order. Copies share element references; they do not clone referenced objects.
 *
 * @param T the element type.
 */
public class Array<T> {
    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> T)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): T

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: T): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): Iterator<T>
}

/**
 * A fixed-size, mutable array of [Boolean] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with `false`. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class BooleanArray {
    /**
     * Creates an array with [size] elements, each initially `false`.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> Boolean)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): Boolean

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: Boolean): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): BooleanIterator
}

/**
 * A fixed-size, mutable array of [Byte] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with zero. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class ByteArray {
    /**
     * Creates an array with [size] elements, each initially zero.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> Byte)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): Byte

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: Byte): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): ByteIterator
}

/**
 * A fixed-size, mutable array of [Char] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with `'\u0000'`. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class CharArray {
    /**
     * Creates an array with [size] elements, each initially `'\u0000'`.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> Char)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): Char

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: Char): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): CharIterator
}

/**
 * A fixed-size, mutable array of [Short] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with zero. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class ShortArray {
    /**
     * Creates an array with [size] elements, each initially zero.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> Short)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): Short

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: Short): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): ShortIterator
}

/**
 * A fixed-size, mutable array of [Int] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with zero. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class IntArray {
    /**
     * Creates an array with [size] elements, each initially zero.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> Int)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): Int

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: Int): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): IntIterator
}

/**
 * A fixed-size, mutable array of [Long] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with zero. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class LongArray {
    /**
     * Creates an array with [size] elements, each initially zero.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> Long)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): Long

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: Long): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): LongIterator
}

/**
 * A fixed-size, mutable array of [Float] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with `0.0`. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class FloatArray {
    /**
     * Creates an array with [size] elements, each initially `0.0`.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> Float)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): Float

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: Float): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): FloatIterator
}

/**
 * A fixed-size, mutable array of [Double] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with `0.0`. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class DoubleArray {
    /**
     * Creates an array with [size] elements, each initially `0.0`.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> Double)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): Double

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: Double): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): DoubleIterator
}

/**
 * A fixed-size, mutable array of [UByte] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with zero. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class UByteArray {
    /**
     * Creates an array with [size] elements, each initially zero.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> UByte)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): UByte

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: UByte): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): Iterator<UByte>
}

/**
 * A fixed-size, mutable array of [UShort] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with zero. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class UShortArray {
    /**
     * Creates an array with [size] elements, each initially zero.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> UShort)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): UShort

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: UShort): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): Iterator<UShort>
}

/**
 * A fixed-size, mutable array of [UInt] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with zero. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class UIntArray {
    /**
     * Creates an array with [size] elements, each initially zero.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> UInt)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): UInt

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: UInt): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): Iterator<UInt>
}

/**
 * A fixed-size, mutable array of [ULong] values.
 *
 * Indices start at zero and end at `size - 1`. Changing an element does not change
 * the array length. Equality compares array identity, not element contents.
 *
 * The size-only constructor fills every element with zero. The initializer
 * constructor calls its callback once for each index, in ascending order.
 */
public class ULongArray {
    /**
     * Creates an array with [size] elements, each initially zero.
     *
     * @param size the number of elements.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int)

    /**
     * Creates an array by invoking [init] once for each index in ascending order.
     *
     * For size zero, [init] is not called. If a callback throws, the exception propagates
     * and no array is returned; effects of completed callback calls are retained.
     *
     * @param size the number of elements.
     * @param init computes the element at the given zero-based index.
     * @throws NegativeArraySizeException if [size] is negative.
     */
    public external constructor(size: Int, init: (Int) -> ULong)

    /**
     * The number of elements in this array; always non-negative and unchanged after creation.
     */
    public external val size: Int

    /**
     * Returns the element at [index].
     *
     * @param index the zero-based element index.
     * @return the current element at [index].
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun get(index: Int): ULong

    /**
     * Replaces the element at [index] with [value].
     *
     * @param index the zero-based element index.
     * @param value the new element.
     * @throws IndexOutOfBoundsException if [index] is negative or is at least [size].
     */
    public external operator fun set(index: Int, value: ULong): Unit

    /**
     * Structural declaration for direct array loops; stored iterators are not executable.
     *
     * Guest `for` loops use indexed access in ascending order. Changes to elements not
     * yet visited are visible to the loop.
     */
    public external operator fun iterator(): Iterator<ULong>
}

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Elements are copied as values;
 * referenced objects are not cloned. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param T the element type.
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun <T> arrayOf(vararg elements: T): Array<T>

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun booleanArrayOf(vararg elements: Boolean): BooleanArray

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun byteArrayOf(vararg elements: Byte): ByteArray

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun charArrayOf(vararg elements: Char): CharArray

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun shortArrayOf(vararg elements: Short): ShortArray

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun intArrayOf(vararg elements: Int): IntArray

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun longArrayOf(vararg elements: Long): LongArray

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun floatArrayOf(vararg elements: Float): FloatArray

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun doubleArrayOf(vararg elements: Double): DoubleArray

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun ubyteArrayOf(vararg elements: UByte): UByteArray

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun ushortArrayOf(vararg elements: UShort): UShortArray

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun uintArrayOf(vararg elements: UInt): UIntArray

/**
 * Creates a new array containing [elements] in argument order.
 *
 * With no arguments, returns a new empty array. Guest calls accept explicit arguments;
 * spreading another array into this factory is not supported.
 *
 * @param elements the elements to include in order.
 * @return a new mutable array containing the supplied elements.
 */
public external fun ulongArrayOf(vararg elements: ULong): ULongArray

/**
 * Creates an array of the requested size with every element set to null.
 *
 * @param T the element type whose nullable form is used by the result.
 * @param size the number of elements.
 * @return a new array of [size] null elements.
 * @throws NegativeArraySizeException if [size] is negative.
 */
public external fun <T> arrayOfNulls(size: Int): Array<T?>

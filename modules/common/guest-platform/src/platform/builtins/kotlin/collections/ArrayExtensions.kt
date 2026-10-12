/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

import kotlin.*

/**
 * Returns a new array containing elements from [fromIndex] up to, but excluding, [toIndex].
 *
 * Guest execution currently supports this overload only for `Array<String>`.
 * The requested range must fit entirely inside the source; it is not padded.
 *
 * @param T the element type; currently only [String] is executable for this overload.
 * @receiver the array to copy from.
 * @param fromIndex the inclusive start of the range.
 * @param toIndex the exclusive end of the range.
 * @return a new array containing the selected range.
 * @throws NegativeArraySizeException if the computed range length is negative.
 * @throws IndexOutOfBoundsException if the source range is outside this array.
 */
public external fun <T> Array<T>.copyOfRange(fromIndex: Int, toIndex: Int): Array<T>

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 * Referenced objects are shared, not cloned.
 *
 * @param T the element type.
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun <T> Array<T>.copyOf(): Array<T>
/**
 * Returns a resized copy, padding extra elements with null.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds null elements. The result has a nullable element type even when shrinking.
 * The source is unchanged. Referenced objects are shared, not cloned.
 *
 * @param T the source element type.
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun <T> Array<T>.copyOf(newSize: Int): Array<T?>
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 * Referenced objects are shared, not cloned.
 *
 * @param T the destination element type.
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun <T> Array<out T>.copyInto(destination: Array<T>, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): Array<T>

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun BooleanArray.copyOf(): BooleanArray
/**
 * Returns a resized copy, padding extra elements with false.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds `false` elements.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun BooleanArray.copyOf(newSize: Int): BooleanArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun BooleanArray.copyInto(destination: BooleanArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): BooleanArray

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun ByteArray.copyOf(): ByteArray
/**
 * Returns a resized copy, padding extra elements with zero.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds zero-valued elements.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun ByteArray.copyOf(newSize: Int): ByteArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun ByteArray.copyInto(destination: ByteArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): ByteArray

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun CharArray.copyOf(): CharArray
/**
 * Returns a resized copy, padding extra elements with zero characters.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds `'\u0000'` characters.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun CharArray.copyOf(newSize: Int): CharArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun CharArray.copyInto(destination: CharArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): CharArray

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun ShortArray.copyOf(): ShortArray
/**
 * Returns a resized copy, padding extra elements with zero.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds zero-valued elements.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun ShortArray.copyOf(newSize: Int): ShortArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun ShortArray.copyInto(destination: ShortArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): ShortArray

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun IntArray.copyOf(): IntArray
/**
 * Returns a resized copy, padding extra elements with zero.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds zero-valued elements.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun IntArray.copyOf(newSize: Int): IntArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun IntArray.copyInto(destination: IntArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): IntArray

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun LongArray.copyOf(): LongArray
/**
 * Returns a resized copy, padding extra elements with zero.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds zero-valued elements.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun LongArray.copyOf(newSize: Int): LongArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun LongArray.copyInto(destination: LongArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): LongArray

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun FloatArray.copyOf(): FloatArray
/**
 * Returns a resized copy, padding extra elements with zero.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds zero-valued elements.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun FloatArray.copyOf(newSize: Int): FloatArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun FloatArray.copyInto(destination: FloatArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): FloatArray

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun DoubleArray.copyOf(): DoubleArray
/**
 * Returns a resized copy, padding extra elements with zero.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds zero-valued elements.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun DoubleArray.copyOf(newSize: Int): DoubleArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun DoubleArray.copyInto(destination: DoubleArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): DoubleArray

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun UByteArray.copyOf(): UByteArray
/**
 * Returns a resized copy, padding extra elements with zero.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds zero-valued elements.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun UByteArray.copyOf(newSize: Int): UByteArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun UByteArray.copyInto(destination: UByteArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): UByteArray

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun UShortArray.copyOf(): UShortArray
/**
 * Returns a resized copy, padding extra elements with zero.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds zero-valued elements.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun UShortArray.copyOf(newSize: Int): UShortArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun UShortArray.copyInto(destination: UShortArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): UShortArray

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun UIntArray.copyOf(): UIntArray
/**
 * Returns a resized copy, padding extra elements with zero.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds zero-valued elements.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun UIntArray.copyOf(newSize: Int): UIntArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun UIntArray.copyInto(destination: UIntArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): UIntArray

/**
 * Returns a new array containing the same elements.
 *
 * The copy has the same length and element type but its own mutable storage.
 *
 * @receiver the array to copy.
 * @return a distinct array containing these elements in order.
 */
public external fun ULongArray.copyOf(): ULongArray
/**
 * Returns a resized copy, padding extra elements with zero.
 *
 * Copies the first `minOf(size, newSize)` elements. A smaller size discards the tail;
 * a larger size adds zero-valued elements.
 * The source is unchanged.
 *
 * @receiver the array to copy.
 * @param newSize the length of the result.
 * @return a new array of [newSize] elements.
 * @throws NegativeArraySizeException if [newSize] is negative.
 */
public external fun ULongArray.copyOf(newSize: Int): ULongArray
/**
 * Copies this range into [destination], supporting overlapping ranges, and returns it.
 *
 * The source range includes [startIndex] and excludes [endIndex]. Overlapping copies
 * within the same array preserve the original source values in either direction.
 * Both ranges are checked before any elements are written, even for an empty copy.
 *
 * @receiver the array to copy from.
 * @param destination the array to receive the elements.
 * @param destinationOffset the index at which to start writing in [destination].
 * @param startIndex the inclusive source index; defaults to zero.
 * @param endIndex the exclusive source index; defaults to the source size.
 * @return the same [destination] array.
 * @throws IndexOutOfBoundsException if either range is outside its array or [endIndex]
 * is less than [startIndex].
 */
public external fun ULongArray.copyInto(destination: ULongArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): ULongArray

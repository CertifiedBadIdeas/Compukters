/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

import kotlin.*

public external fun <T> Array<T>.copyOfRange(fromIndex: Int, toIndex: Int): Array<T>

/** Returns a new array containing the same elements. */
public external fun <T> Array<T>.copyOf(): Array<T>
/** Returns a resized copy, padding extra elements with null. */
public external fun <T> Array<T>.copyOf(newSize: Int): Array<T?>
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun <T> Array<out T>.copyInto(destination: Array<T>, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): Array<T>

/** Returns a new array containing the same elements. */
public external fun BooleanArray.copyOf(): BooleanArray
/** Returns a resized copy, padding extra elements with false. */
public external fun BooleanArray.copyOf(newSize: Int): BooleanArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun BooleanArray.copyInto(destination: BooleanArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): BooleanArray

/** Returns a new array containing the same elements. */
public external fun ByteArray.copyOf(): ByteArray
/** Returns a resized copy, padding extra elements with zero. */
public external fun ByteArray.copyOf(newSize: Int): ByteArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun ByteArray.copyInto(destination: ByteArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): ByteArray

/** Returns a new array containing the same elements. */
public external fun CharArray.copyOf(): CharArray
/** Returns a resized copy, padding extra elements with zero characters. */
public external fun CharArray.copyOf(newSize: Int): CharArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun CharArray.copyInto(destination: CharArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): CharArray

/** Returns a new array containing the same elements. */
public external fun ShortArray.copyOf(): ShortArray
/** Returns a resized copy, padding extra elements with zero. */
public external fun ShortArray.copyOf(newSize: Int): ShortArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun ShortArray.copyInto(destination: ShortArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): ShortArray

/** Returns a new array containing the same elements. */
public external fun IntArray.copyOf(): IntArray
/** Returns a resized copy, padding extra elements with zero. */
public external fun IntArray.copyOf(newSize: Int): IntArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun IntArray.copyInto(destination: IntArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): IntArray

/** Returns a new array containing the same elements. */
public external fun LongArray.copyOf(): LongArray
/** Returns a resized copy, padding extra elements with zero. */
public external fun LongArray.copyOf(newSize: Int): LongArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun LongArray.copyInto(destination: LongArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): LongArray

/** Returns a new array containing the same elements. */
public external fun FloatArray.copyOf(): FloatArray
/** Returns a resized copy, padding extra elements with zero. */
public external fun FloatArray.copyOf(newSize: Int): FloatArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun FloatArray.copyInto(destination: FloatArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): FloatArray

/** Returns a new array containing the same elements. */
public external fun DoubleArray.copyOf(): DoubleArray
/** Returns a resized copy, padding extra elements with zero. */
public external fun DoubleArray.copyOf(newSize: Int): DoubleArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun DoubleArray.copyInto(destination: DoubleArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): DoubleArray

/** Returns a new array containing the same elements. */
public external fun UByteArray.copyOf(): UByteArray
/** Returns a resized copy, padding extra elements with zero. */
public external fun UByteArray.copyOf(newSize: Int): UByteArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun UByteArray.copyInto(destination: UByteArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): UByteArray

/** Returns a new array containing the same elements. */
public external fun UShortArray.copyOf(): UShortArray
/** Returns a resized copy, padding extra elements with zero. */
public external fun UShortArray.copyOf(newSize: Int): UShortArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun UShortArray.copyInto(destination: UShortArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): UShortArray

/** Returns a new array containing the same elements. */
public external fun UIntArray.copyOf(): UIntArray
/** Returns a resized copy, padding extra elements with zero. */
public external fun UIntArray.copyOf(newSize: Int): UIntArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun UIntArray.copyInto(destination: UIntArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): UIntArray

/** Returns a new array containing the same elements. */
public external fun ULongArray.copyOf(): ULongArray
/** Returns a resized copy, padding extra elements with zero. */
public external fun ULongArray.copyOf(newSize: Int): ULongArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun ULongArray.copyInto(destination: ULongArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): ULongArray

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

import kotlin.Array
import kotlin.Int
import kotlin.IntArray
import kotlin.CharArray

public external fun <T> Array<T>.copyOfRange(fromIndex: Int, toIndex: Int): Array<T>

/** Returns a new array containing the same elements. */
public external fun <T> Array<T>.copyOf(): Array<T>
/** Returns a resized copy, padding extra elements with null. */
public external fun <T> Array<T>.copyOf(newSize: Int): Array<T?>
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun <T> Array<out T>.copyInto(destination: Array<T>, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): Array<T>

/** Returns a new array containing the same elements. */
public external fun IntArray.copyOf(): IntArray
/** Returns a resized copy, padding extra elements with zero. */
public external fun IntArray.copyOf(newSize: Int): IntArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun IntArray.copyInto(destination: IntArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): IntArray

/** Returns a new array containing the same elements. */
public external fun CharArray.copyOf(): CharArray
/** Returns a resized copy, padding extra elements with zero characters. */
public external fun CharArray.copyOf(newSize: Int): CharArray
/** Copies this range into [destination], supporting overlapping ranges, and returns it. */
public external fun CharArray.copyInto(destination: CharArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = size): CharArray

/*
 * The Compukters Developers
 *
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlin.collections

/**
 * Numeric accumulation traverses once in iteration order without an intermediate collection.
 * Empty iterables return positive numeric zero. Integer arithmetic wraps at the result width;
 * Float/Double addition retains ordinary rounding, infinities and NaN. Byte/Short widen to Int;
 * UByte/UShort widen to UInt. Iterator failures propagate unchanged.
 */
public fun Iterable<Byte>.sum(): Int {
    var total = 0
    for (element in this) total += element
    return total
}

public fun Iterable<Short>.sum(): Int {
    var total = 0
    for (element in this) total += element
    return total
}

public fun Iterable<Int>.sum(): Int {
    var total = 0
    for (element in this) total += element
    return total
}

public fun Iterable<Long>.sum(): Long {
    var total = 0L
    for (element in this) total += element
    return total
}

public fun Iterable<Float>.sum(): Float {
    var total = 0.0f
    for (element in this) total += element
    return total
}

public fun Iterable<Double>.sum(): Double {
    var total = 0.0
    for (element in this) total += element
    return total
}

public fun Iterable<UByte>.sum(): UInt {
    var total = 0u
    for (element in this) total += element
    return total
}

public fun Iterable<UShort>.sum(): UInt {
    var total = 0u
    for (element in this) total += element
    return total
}

public fun Iterable<UInt>.sum(): UInt {
    var total = 0u
    for (element in this) total += element
    return total
}

public fun Iterable<ULong>.sum(): ULong {
    var total = 0uL
    for (element in this) total += element
    return total
}

/**
 * Sums [selector] once per element in iteration order, returning Int zero when empty.
 * No collection is allocated; selector/iterator exceptions and inline non-local returns propagate.
 * Addition uses ordinary wrapping Int arithmetic.
 */
@kotlin.OverloadResolutionByLambdaReturnType
public inline fun <T> Iterable<T>.sumOf(selector: (T) -> Int): Int {
    var total = 0
    for (element in this) total += selector(element)
    return total
}

/**
 * Sums [selector] once per element in iteration order, returning Long zero when empty.
 * No collection is allocated; selector/iterator exceptions and inline non-local returns propagate.
 * Addition uses ordinary wrapping Long arithmetic.
 */
@kotlin.OverloadResolutionByLambdaReturnType
public inline fun <T> Iterable<T>.sumOf(selector: (T) -> Long): Long {
    var total = 0L
    for (element in this) total += selector(element)
    return total
}

/**
 * Sums [selector] once per element in iteration order, returning Double zero when empty.
 * No collection is allocated; selector/iterator exceptions and inline non-local returns propagate.
 * Addition retains ordinary Double rounding, infinities and NaN.
 */
@kotlin.OverloadResolutionByLambdaReturnType
public inline fun <T> Iterable<T>.sumOf(selector: (T) -> Double): Double {
    var total = 0.0
    for (element in this) total += selector(element)
    return total
}

/**
 * Sums [selector] once per element in iteration order, returning UInt zero when empty.
 * No collection is allocated; selector/iterator exceptions and inline non-local returns propagate.
 * Addition uses ordinary wrapping UInt arithmetic.
 */
@kotlin.OverloadResolutionByLambdaReturnType
public inline fun <T> Iterable<T>.sumOf(selector: (T) -> UInt): UInt {
    var total = 0u
    for (element in this) total += selector(element)
    return total
}

/**
 * Sums [selector] once per element in iteration order, returning ULong zero when empty.
 * No collection is allocated; selector/iterator exceptions and inline non-local returns propagate.
 * Addition uses ordinary wrapping ULong arithmetic.
 */
@kotlin.OverloadResolutionByLambdaReturnType
public inline fun <T> Iterable<T>.sumOf(selector: (T) -> ULong): ULong {
    var total = 0uL
    for (element in this) total += selector(element)
    return total
}

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

// Specialize to the receiver's read view: Int remains unboxed, while Any/Int? reads use bridges.
internal fun <T> List<T>.listIndexOf(element: T): Int {
    var index = 0
    while (index < size) {
        if (element == this[index]) return index
        index += 1
    }
    return -1
}

internal fun <T> List<T>.listLastIndexOf(element: T): Int {
    var index = size - 1
    while (index >= 0) {
        if (element == this[index]) return index
        index -= 1
    }
    return -1
}

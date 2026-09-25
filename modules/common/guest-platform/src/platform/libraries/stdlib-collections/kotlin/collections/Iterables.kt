/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

/** Checks whether this iterable has an element equal to [element]. */
public operator fun <T> Iterable<T>.contains(element: T): Boolean {
    for (value in this) {
        if (element == value) return true
    }
    return false
}

/** Returns the index of the first equal element, or -1 when no element matches. */
public fun <T> Iterable<T>.indexOf(element: T): Int {
    var index = 0
    for (value in this) {
        require(index >= 0)
        if (element == value) return index
        index += 1
    }
    return -1
}

/** Returns the index of the last equal element, or -1 when no element matches. */
public fun <T> Iterable<T>.lastIndexOf(element: T): Int {
    var index = 0
    var lastIndex = -1
    for (value in this) {
        require(index >= 0)
        if (element == value) lastIndex = index
        index += 1
    }
    return lastIndex
}

/** Returns true when at least one element matches [predicate]. */
public fun <T> Iterable<T>.any(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (predicate(element)) return true
    }
    return false
}

/** Returns true when every element matches [predicate]. */
public fun <T> Iterable<T>.all(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (!predicate(element)) return false
    }
    return true
}

/** Returns true when no element matches [predicate]. */
public fun <T> Iterable<T>.none(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (predicate(element)) return false
    }
    return true
}

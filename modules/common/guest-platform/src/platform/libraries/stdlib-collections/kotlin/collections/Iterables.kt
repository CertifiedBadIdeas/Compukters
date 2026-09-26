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

/** Returns the first element, or null when this iterable is empty. */
public fun <T> Iterable<T>.firstOrNull(): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    return iterator.next()
}

/** Returns the first matching element, or null when no element matches [predicate]. */
public fun <T> Iterable<T>.firstOrNull(predicate: (T) -> Boolean): T? {
    for (element in this) {
        if (predicate(element)) return element
    }
    return null
}

/** Returns the last element, or null when this iterable is empty. */
public fun <T> Iterable<T>.lastOrNull(): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var last = iterator.next()
    while (iterator.hasNext()) last = iterator.next()
    return last
}

/** Returns the last matching element after traversing this iterable in iteration order. */
public fun <T> Iterable<T>.lastOrNull(predicate: (T) -> Boolean): T? {
    var last: T? = null
    for (element in this) {
        if (predicate(element)) last = element
    }
    return last
}

/**
 * Accumulates elements in iteration order, passing the current result and each element to [operation].
 * Returns [initial] unchanged when this iterable is empty.
 */
public fun <T, R> Iterable<T>.fold(initial: R, operation: (R, T) -> R): R {
    var accumulator = initial
    for (element in this) {
        accumulator = operation(accumulator, element)
    }
    return accumulator
}

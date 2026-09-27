/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

/** Creates an [ArrayList] from the given elements and exposes it through the read-only [List] interface. */
public external fun <T> listOf(vararg elements: T): List<T>

/** Creates a new empty [ArrayList] exposed through the read-only [List] interface. */
public external fun <T> emptyList(): List<T>

/** Checks whether this collection has at least one element. */
public fun <T> Collection<T>.isNotEmpty(): Boolean = !isEmpty()

/** Returns the element at [index], or null when the index is outside this list. */
public fun <T> List<T>.getOrNull(index: Int): T? {
    if (index < 0 || index >= size) return null
    return this[index]
}

/** Returns the first element, or null when this list is empty. */
public fun <T> List<T>.firstOrNull(): T? = getOrNull(0)

/** Returns the last element, or null when this list is empty. */
public fun <T> List<T>.lastOrNull(): T? = getOrNull(size - 1)

/** Returns the last matching element, searching from the end until a match is found. */
public inline fun <T> List<T>.lastOrNull(predicate: (T) -> Boolean): T? {
    var index = size - 1
    while (index >= 0) {
        val element = this[index]
        if (predicate(element)) return element
        index -= 1
    }
    return null
}

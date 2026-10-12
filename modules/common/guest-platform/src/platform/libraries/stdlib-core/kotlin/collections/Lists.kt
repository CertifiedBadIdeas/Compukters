/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

/**
 * Creates a new list containing [elements] in argument order, including duplicates and nulls.
 *
 * The result uses [ArrayList] storage exposed through [List]. Read-only access does not make the
 * underlying object immutable. Elements are retained as values or references, not cloned.
 * Guest Kotlin supports direct vararg arguments; spread arguments remain unavailable.
 *
 * @param T element type.
 * @param elements elements to place in the list.
 * @return a fresh list, empty when no elements are supplied.
 * @see emptyList
 */
public external fun <T> listOf(vararg elements: T): List<T>

/**
 * Creates a fresh empty [ArrayList] exposed through the read-only [List] interface.
 *
 * The result is not a shared singleton. Read-only access does not make the underlying object immutable.
 *
 * @param T element type.
 * @return a new list with size zero.
 * @see listOf
 */
public external fun <T> emptyList(): List<T>

/**
 * Checks whether this collection contains at least one element.
 *
 * Delegates to [Collection.isEmpty] without traversing the collection itself.
 *
 * @receiver collection to inspect.
 * @return true when [Collection.isEmpty] returns false.
 */
public fun <T> Collection<T>.isNotEmpty(): Boolean = !isEmpty()

/**
 * Returns the element at [index], or null when the index is outside this list.
 *
 * Negative indexes and indexes at or beyond size return null without calling [List.get].
 * For nullable element types, null can also be a stored element.
 *
 * @receiver list to read.
 * @param index zero-based element index.
 * @return the element at [index], or null when no such index exists.
 * @see List.get
 */
public fun <T> List<T>.getOrNull(index: Int): T? {
    if (index < 0 || index >= size) return null
    return this[index]
}

/**
 * Returns the first element, or null when this list is empty.
 *
 * For nullable element types, null can also be the first stored element.
 *
 * @receiver list to read.
 * @return the element at index zero, or null for an empty list.
 * @see getOrNull
 */
public fun <T> List<T>.firstOrNull(): T? = getOrNull(0)

/**
 * Returns the last element, or null when this list is empty.
 *
 * For nullable element types, null can also be the last stored element.
 *
 * @receiver list to read.
 * @return the element at size minus one, or null for an empty list.
 * @see getOrNull
 */
public fun <T> List<T>.lastOrNull(): T? = getOrNull(size - 1)

/**
 * Returns the last element satisfying [predicate], or null when no element matches.
 *
 * Visits elements from the last index towards zero and stops at the first match. Empty lists
 * never invoke [predicate]. A matching null element is indistinguishable from no match.
 * The callback is inline and supports non-local returns; its exceptions propagate to the caller.
 *
 * @receiver list to search in reverse order.
 * @param predicate test applied to each visited element.
 * @return the last matching element, or null if the list is empty or has no match.
 */
public inline fun <T> List<T>.lastOrNull(predicate: (T) -> Boolean): T? {
    var index = size - 1
    while (index >= 0) {
        val element = this[index]
        if (predicate(element)) return element
        index -= 1
    }
    return null
}

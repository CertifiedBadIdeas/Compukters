/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

/** Calls [action] for each element in iteration order. */
public inline fun <T> Iterable<T>.forEach(action: (T) -> Unit) {
    for (element in this) action(element)
}

/** Calls [action] for each element and its zero-based index in iteration order. */
public inline fun <T> Iterable<T>.forEachIndexed(action: (Int, T) -> Unit) {
    var index = 0
    for (element in this) {
        require(index >= 0)
        action(index, element)
        index += 1
    }
}

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
public inline fun <T> Iterable<T>.any(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (predicate(element)) return true
    }
    return false
}

/** Returns true when every element matches [predicate]. */
public inline fun <T> Iterable<T>.all(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (!predicate(element)) return false
    }
    return true
}

/** Returns true when no element matches [predicate]. */
public inline fun <T> Iterable<T>.none(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (predicate(element)) return false
    }
    return true
}

/** Returns the first element, throwing [NoSuchElementException] when empty. */
public fun <T> Iterable<T>.first(): T {
    val iterator = iterator()
    if (!iterator.hasNext()) throw NoSuchElementException("Collection is empty")
    return iterator.next()
}

/** Returns the first matching element, throwing [NoSuchElementException] when no element matches. */
public inline fun <T> Iterable<T>.first(predicate: (T) -> Boolean): T {
    for (element in this) {
        if (predicate(element)) return element
    }
    throw NoSuchElementException("No element matches the predicate")
}

/** Returns the first matching element, or null when no element matches [predicate]. */
public inline fun <T> Iterable<T>.find(predicate: (T) -> Boolean): T? = firstOrNull(predicate)

/** Returns the first element, or null when this iterable is empty. */
public fun <T> Iterable<T>.firstOrNull(): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    return iterator.next()
}

/** Returns the first matching element, or null when no element matches [predicate]. */
public inline fun <T> Iterable<T>.firstOrNull(predicate: (T) -> Boolean): T? {
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
public inline fun <T> Iterable<T>.lastOrNull(predicate: (T) -> Boolean): T? {
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
public inline fun <T, R> Iterable<T>.fold(initial: R, operation: (R, T) -> R): R {
    var accumulator = initial
    for (element in this) {
        accumulator = operation(accumulator, element)
    }
    return accumulator
}

/** Returns a list containing [transform] applied once to each element in iteration order. */
public inline fun <T, R> Iterable<T>.map(transform: (T) -> R): List<R> {
    val result = ArrayList<R>()
    for (element in this) {
        result.add(transform(element))
    }
    return result
}

/** Maps a collection in iteration order, reserving its known size for the result. */
public inline fun <T, R> Collection<T>.map(transform: (T) -> R): List<R> {
    val result = ArrayList<R>(size)
    val elements: Iterable<T> = this
    for (element in elements) {
        result.add(transform(element))
    }
    return result
}

/** Applies [transform] once per element in iteration order and retains only non-null results. */
public inline fun <T, R : Any> Iterable<T>.mapNotNull(transform: (T) -> R?): List<R> {
    val result = ArrayList<R>()
    for (element in this) {
        val value = transform(element)
        if (value != null) result.add(value)
    }
    return result
}

/** Returns a list containing elements matching [predicate], in their original iteration order. */
public inline fun <T> Iterable<T>.filter(predicate: (T) -> Boolean): List<T> {
    val result = ArrayList<T>()
    for (element in this) {
        if (predicate(element)) result.add(element)
    }
    return result
}

/** Appends transformed elements to [destination] in iteration order and returns that destination. */
public inline fun <T, R, C : MutableCollection<in R>> Iterable<T>.mapTo(destination: C, transform: (T) -> R): C {
    for (element in this) {
        destination.add(transform(element))
    }
    return destination
}

/** Appends matching elements to [destination] in iteration order and returns that destination. */
public inline fun <T, C : MutableCollection<in T>> Iterable<T>.filterTo(destination: C, predicate: (T) -> Boolean): C {
    for (element in this) {
        if (predicate(element)) destination.add(element)
    }
    return destination
}

/** Appends non-null transformed elements to [destination] and returns that destination. */
public inline fun <T, R : Any, C : MutableCollection<in R>> Iterable<T>.mapNotNullTo(destination: C, transform: (T) -> R?): C {
    for (element in this) {
        val value = transform(element)
        if (value != null) destination.add(value)
    }
    return destination
}

/** Returns a list of non-null elements in their original iteration order. */
public fun <T : Any> Iterable<T?>.filterNotNull(): List<T> {
    val result = ArrayList<T>()
    for (element in this) {
        if (element != null) result.add(element)
    }
    return result
}

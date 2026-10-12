/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

/**
 * Calls [action] for each element in iteration order.
 *
 * Calls the action once per element, including null elements. An empty iterable invokes
 * no callbacks. This function does not create a result collection.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param action action receiving the current element.
 */
public inline fun <T> Iterable<T>.forEach(action: (T) -> Unit) {
    for (element in this) action(element)
}

/**
 * Calls [action] for each element and its zero-based index in iteration order.
 *
 * Indexes start at zero. An empty iterable invokes no callbacks.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param action action receiving the index followed by the current element.
 * @throws IllegalArgumentException if an element index would exceed [Int.MAX_VALUE].
 */
public inline fun <T> Iterable<T>.forEachIndexed(action: (Int, T) -> Unit) {
    var index = 0
    for (element in this) {
        require(index >= 0)
        action(index, element)
        index += 1
    }
}

/**
 * Checks whether this iterable has an element equal to [element].
 *
 * Compares using `element == value` and stops at the first match. Null is a valid
 * search value when the element type is nullable.
 * Exceptions from iteration propagate to the caller.
 *
 * @receiver elements to visit in their iteration order.
 * @param element value to search for.
 * @return true on a match, or false when empty or unmatched.
 */
public operator fun <T> Iterable<T>.contains(element: T): Boolean {
    for (value in this) {
        if (element == value) return true
    }
    return false
}

/**
 * Returns the index of the first equal element, or -1 when no element matches.
 *
 * Indexes follow iteration order and start at zero. Equality uses `element == value`.
 * Stops at the first match.
 * Exceptions from iteration propagate to the caller.
 *
 * @receiver elements to visit in their iteration order.
 * @param element value to search for, including null for nullable element types.
 * @return the matching index, or -1 when empty or unmatched.
 * @throws IllegalArgumentException if a visited element index would exceed [Int.MAX_VALUE].
 */
public fun <T> Iterable<T>.indexOf(element: T): Int {
    var index = 0
    for (value in this) {
        require(index >= 0)
        if (element == value) return index
        index += 1
    }
    return -1
}

/**
 * Returns the index of the last equal element, or -1 when no element matches.
 *
 * Indexes follow iteration order and start at zero. Equality uses `element == value`.
 * Traverses the entire iterable to find the last match.
 * Exceptions from iteration propagate to the caller.
 *
 * @receiver elements to visit in their iteration order.
 * @param element value to search for, including null for nullable element types.
 * @return the matching index, or -1 when empty or unmatched.
 * @throws IllegalArgumentException if a visited element index would exceed [Int.MAX_VALUE].
 */
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

/**
 * Returns true when at least one element matches [predicate].
 *
 * Stops at the first true predicate result. Returns false for an empty iterable
 * without invoking the predicate.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param predicate test receiving each visited element.
 * @return true if any element matches, or false otherwise.
 */
public inline fun <T> Iterable<T>.any(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (predicate(element)) return true
    }
    return false
}

/**
 * Returns true when every element matches [predicate].
 *
 * Stops at the first false predicate result. Returns true for an empty iterable
 * without invoking the predicate.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param predicate test receiving each visited element.
 * @return true if all elements match, or false otherwise.
 */
public inline fun <T> Iterable<T>.all(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (!predicate(element)) return false
    }
    return true
}

/**
 * Returns true when no element matches [predicate].
 *
 * Stops at the first true predicate result. Returns true for an empty iterable
 * without invoking the predicate.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param predicate test receiving each visited element.
 * @return true if no elements match, or false otherwise.
 */
public inline fun <T> Iterable<T>.none(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (predicate(element)) return false
    }
    return true
}

/**
 * Returns the first element, throwing [NoSuchElementException] when empty.
 *
 * Reads only the first element. A stored null is returned normally for nullable types.
 * Exceptions from iteration propagate to the caller.
 *
 * @receiver elements to visit in their iteration order.
 * @return the first element in iteration order.
 * @throws NoSuchElementException if this iterable is empty.
 * @see firstOrNull
 */
public fun <T> Iterable<T>.first(): T {
    val iterator = iterator()
    if (!iterator.hasNext()) throw NoSuchElementException("Collection is empty")
    return iterator.next()
}

/**
 * Returns the first matching element, throwing [NoSuchElementException] when no element matches.
 *
 * Stops at the first match. A matching null is returned normally for nullable types.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param predicate test returning true to select an element.
 * @return the first accepted element.
 * @throws NoSuchElementException if empty or no element matches.
 * @see firstOrNull
 */
public inline fun <T> Iterable<T>.first(predicate: (T) -> Boolean): T {
    for (element in this) {
        if (predicate(element)) return element
    }
    throw NoSuchElementException("No element matches the predicate")
}

/**
 * Returns the first matching element, or null when no element matches [predicate].
 *
 * An alias for [firstOrNull] with a predicate; stops at the first match.
 * For nullable elements, a null result may also be a selected null element.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param predicate test returning true to select an element.
 * @return the first accepted element, or null when empty or unmatched.
 * @see firstOrNull
 */
public inline fun <T> Iterable<T>.find(predicate: (T) -> Boolean): T? = firstOrNull(predicate)

/**
 * Returns the first element, or null when this iterable is empty.
 *
 * Reads only the first element.
 * For nullable elements, a null result may also be a selected null element.
 * Exceptions from iteration propagate to the caller.
 *
 * @receiver elements to visit in their iteration order.
 * @return the first element in iteration order, or null when empty.
 * @see first
 */
public fun <T> Iterable<T>.firstOrNull(): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    return iterator.next()
}

/**
 * Returns the first matching element, or null when no element matches [predicate].
 *
 * Stops at the first match.
 * For nullable elements, a null result may also be a selected null element.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param predicate test returning true to select an element.
 * @return the first accepted element, or null when empty or unmatched.
 * @see first
 * @see find
 */
public inline fun <T> Iterable<T>.firstOrNull(predicate: (T) -> Boolean): T? {
    for (element in this) {
        if (predicate(element)) return element
    }
    return null
}

/**
 * Returns the last element, or null when this iterable is empty.
 *
 * Traverses the entire iterable.
 * For nullable elements, a null result may also be a selected null element.
 * Exceptions from iteration propagate to the caller.
 *
 * @receiver elements to visit in their iteration order.
 * @return the last element in iteration order, or null when empty.
 */
public fun <T> Iterable<T>.lastOrNull(): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var last = iterator.next()
    while (iterator.hasNext()) last = iterator.next()
    return last
}

/**
 * Returns the last matching element after traversing this iterable in iteration order.
 *
 * Tests every element and keeps the most recent match.
 * For nullable elements, a null result may also be a selected null element.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param predicate test returning true to select an element.
 * @return the last accepted element, or null when empty or unmatched.
 */
public inline fun <T> Iterable<T>.lastOrNull(predicate: (T) -> Boolean): T? {
    var last: T? = null
    for (element in this) {
        if (predicate(element)) last = element
    }
    return last
}

/**
 * Accumulates elements in iteration order.
 *
 * Passes the current accumulator and each element to [operation], then uses its result
 * as the next accumulator. An empty iterable returns [initial] without invoking the operation.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * ```kotlin
 * val total = listOf(1, 2, 3).fold(0) { sum, value -> sum + value }
 * ```
 *
 * @receiver elements to visit in their iteration order.
 * @param initial starting accumulator value.
 * @param operation callback receiving the accumulator followed by the current element.
 * @return the final accumulator, or [initial] when empty.
 */
public inline fun <T, R> Iterable<T>.fold(
    initial: R,
    operation: (R, T) -> R,
): R {
    var accumulator = initial
    for (element in this) {
        accumulator = operation(accumulator, element)
    }
    return accumulator
}

/**
 * Returns a list containing [transform] applied once to each element in iteration order.
 *
 * Creates a new list and retains all transformed values, including nulls.
 * The result has the same iteration order as the source.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param transform transformation receiving the current element.
 * @return a new read-only list of results; empty when the source is empty.
 * @see mapNotNull
 */
public inline fun <T, R> Iterable<T>.map(transform: (T) -> R): List<R> {
    val result = ArrayList<R>()
    for (element in this) {
        result.add(transform(element))
    }
    return result
}

/**
 * Returns a list containing [transform] applied once to each element in iteration order.
 *
 * Creates a new list and retains all transformed values, including nulls.
 * Reserves the collection size as the initial result capacity.
 * The result has the same iteration order as the source.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param transform transformation receiving the current element.
 * @return a new read-only list of results; empty when the source is empty.
 * @see mapNotNull
 */
public inline fun <T, R> Collection<T>.map(transform: (T) -> R): List<R> {
    val result = ArrayList<R>(size)
    val elements: Iterable<T> = this
    for (element in elements) {
        result.add(transform(element))
    }
    return result
}

/**
 * Applies [transform] once per element in iteration order and retains only non-null results.
 *
 * Creates a new list without changing the source. Accepted results retain their order.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param transform transformation returning a value to include, or null to omit it.
 * @return a new read-only list of non-null results; empty if none are produced.
 * @see map
 */
public inline fun <T, R : Any> Iterable<T>.mapNotNull(transform: (T) -> R?): List<R> {
    val result = ArrayList<R>()
    for (element in this) {
        val value = transform(element)
        if (value != null) result.add(value)
    }
    return result
}

/**
 * Returns a list containing elements matching [predicate], in their original iteration order.
 *
 * Tests every element once and retains the accepted values without copying the values
 * themselves. A nullable element can be retained by the predicate.
 * Exceptions from iteration or the callback propagate to the caller.
 * The inline callback supports non-local returns.
 *
 * @receiver elements to visit in their iteration order.
 * @param predicate test returning true to retain the current element.
 * @return a new read-only list of accepted elements; empty if none match.
 * @see filterNotNull
 */
public inline fun <T> Iterable<T>.filter(predicate: (T) -> Boolean): List<T> {
    val result = ArrayList<T>()
    for (element in this) {
        if (predicate(element)) result.add(element)
    }
    return result
}

/**
 * Appends transformed elements to [destination] in iteration order.
 *
 * Applies [transform] once per element and appends every result, including nulls.
 * Existing destination contents are retained. If iteration, the callback or insertion
 * fails, prior additions remain; the function does not roll them back.
 * The inline callback supports non-local returns. An empty source leaves the destination unchanged.
 *
 * @receiver elements to visit in their iteration order.
 * @param destination mutable collection receiving additions; its insertion rules apply.
 * @param transform transformation receiving the current element.
 * @return the same [destination] instance.
 */
public inline fun <T, R, C : MutableCollection<in R>> Iterable<T>.mapTo(
    destination: C,
    transform: (T) -> R,
): C {
    for (element in this) {
        destination.add(transform(element))
    }
    return destination
}

/**
 * Appends matching elements to [destination] in iteration order.
 *
 * Tests every element once and appends those accepted by [predicate].
 * Existing destination contents are retained. If iteration, the callback or insertion
 * fails, prior additions remain; the function does not roll them back.
 * The inline callback supports non-local returns. An empty source leaves the destination unchanged.
 *
 * @receiver elements to visit in their iteration order.
 * @param destination mutable collection receiving additions; its insertion rules apply.
 * @param predicate test returning true to append the current element.
 * @return the same [destination] instance.
 */
public inline fun <T, C : MutableCollection<in T>> Iterable<T>.filterTo(
    destination: C,
    predicate: (T) -> Boolean,
): C {
    for (element in this) {
        if (predicate(element)) destination.add(element)
    }
    return destination
}

/**
 * Appends non-null transformed elements to [destination] in iteration order.
 *
 * Applies [transform] once per element and skips null results.
 * Existing destination contents are retained. If iteration, the callback or insertion
 * fails, prior additions remain; the function does not roll them back.
 * The inline callback supports non-local returns. An empty source leaves the destination unchanged.
 *
 * @receiver elements to visit in their iteration order.
 * @param destination mutable collection receiving additions; its insertion rules apply.
 * @param transform transformation returning a value to append, or null to omit it.
 * @return the same [destination] instance.
 */
public inline fun <T, R : Any, C : MutableCollection<in R>> Iterable<T>.mapNotNullTo(
    destination: C,
    transform: (T) -> R?,
): C {
    for (element in this) {
        val value = transform(element)
        if (value != null) destination.add(value)
    }
    return destination
}

/**
 * Returns a list of non-null elements in their original iteration order.
 *
 * Creates a new list and narrows the element type to non-null. The retained values
 * are not copied.
 * Exceptions from iteration propagate to the caller.
 *
 * @receiver elements to visit in their iteration order.
 * @return a new read-only list excluding nulls; empty if there are no non-null elements.
 * @see mapNotNull
 */
public fun <T : Any> Iterable<T?>.filterNotNull(): List<T> {
    val result = ArrayList<T>()
    for (element in this) {
        if (element != null) result.add(element)
    }
    return result
}

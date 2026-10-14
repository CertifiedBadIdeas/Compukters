/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.sequences

import kotlin.collections.ArrayList
import kotlin.collections.Iterator
import kotlin.collections.List

/**
 * Produces elements on demand through an iterator, without storing a collection of results.
 *
 * Each traversal asks [iterator] for its own state. Repeatability and mutation behavior follow
 * the source. Iterators, wrappers and captured callbacks use ordinary managed heap quotas.
 * @param T element type, including nullable types.
 */
public interface Sequence<out T> {
    /** Returns an iterator for this traversal; creating it does not request an element. */
    public operator fun iterator(): Iterator<T>
}

/**
 * Transforms elements when the returned iterator's next is called.
 * The callback is retained and may run again on subsequent traversals; exceptions propagate.
 * No intermediate list is created. Unlike eager inline map, non-local returns are unavailable.
 */
public fun <T, R> Sequence<T>.map(transform: (T) -> R): Sequence<R> =
    TransformingSequence<T, R>(this, transform)

/**
 * Transforms elements on demand and skips null results, preserving source order.
 * Repeated hasNext calls reuse the accepted result without invoking [transform] again.
 * The callback is retained; exceptions propagate and non-local returns are unavailable.
 * Nullable primitive results use their ordinary boxes before non-null scalar caching.
 */
public fun <T, R : Any> Sequence<T>.mapNotNull(transform: (T) -> R?): Sequence<R> =
    TransformingSequence<T, R?>(this, transform).filterNotNull()

/**
 * Lazily skips null elements and exposes a non-null element type.
 * Searches only as far as the consumer requests; repeated hasNext calls reuse the cache.
 * Primitive accepted values use the existing scalar cache; reference slots clear on consumption.
 */
public fun <T : Any> Sequence<T?>.filterNotNull(): Sequence<T> = NotNullSequence<T>(this)

/**
 * Retains matching elements in source order, testing only as far as the consumer requests.
 * hasNext may advance the source and invoke the retained predicate. Repeated hasNext calls
 * reuse a cached element, including null. Each traversal has its own cache.
 */
public fun <T> Sequence<T>.filter(predicate: (T) -> Boolean): Sequence<T> =
    FilteringSequence<T>(this, true, predicate)

/** Lazily retains elements for which the retained predicate returns false, in source order. */
public fun <T> Sequence<T>.filterNot(predicate: (T) -> Boolean): Sequence<T> =
    FilteringSequence<T>(this, false, predicate)

/**
 * Returns at most [count] elements. Zero does not even request an upstream iterator.
 * @throws IllegalArgumentException immediately if count is negative.
 */
public fun <T> Sequence<T>.take(count: Int): Sequence<T> {
    require(count >= 0)
    if (count == 0) return EmptySequence<T>()
    return TakingSequence<T>(this, count)
}

/** Returns the first element, including null, or null when empty. Stops after that element. */
public fun <T> Sequence<T>.firstOrNull(): T? {
    val elements = iterator()
    return if (elements.hasNext()) elements.next() else null
}

/**
 * Returns the first matching element, including null, or null when none matches.
 * Stops immediately at a match. The immediate inline predicate admits non-local returns.
 */
public inline fun <T> Sequence<T>.firstOrNull(predicate: (T) -> Boolean): T? {
    for (element in this) {
        if (predicate(element)) return element
    }
    return null
}

/**
 * Consumes the sequence into a new list, preserving order and nullable elements.
 * An unbounded source does not finish and remains subject to execution and heap quotas.
 */
public fun <T> Sequence<T>.toList(): List<T> {
    val result = ArrayList<T>()
    for (element in this) result.add(element)
    return result
}

/**
 * Visits every element in iteration order without constructing a result list.
 * Exceptions propagate; the immediate inline callback admits non-local returns.
 */
public inline fun <T> Sequence<T>.forEach(action: (T) -> Unit) {
    for (element in this) action(element)
}

/**
 * Accumulates elements in order, passing the accumulator before the current element.
 * Returns [initial] for an empty sequence without invoking [operation].
 * Exceptions propagate; the immediate inline callback admits non-local returns.
 */
public inline fun <T, R> Sequence<T>.fold(initial: R, operation: (R, T) -> R): R {
    var accumulator = initial
    for (element in this) accumulator = operation(accumulator, element)
    return accumulator
}

/**
 * Consumes all elements and returns their count, including null elements.
 * @throws ArithmeticException if the count exceeds Int.MAX_VALUE.
 */
public fun <T> Sequence<T>.count(): Int {
    var count = 0
    for (element in this) {
        if (count == Int.MAX_VALUE) throw ArithmeticException("Count overflow has happened.")
        count += 1
    }
    return count
}

/**
 * Counts matches in iteration order, consuming the entire sequence.
 * Exceptions propagate; the immediate inline callback admits non-local returns.
 * @throws ArithmeticException if the match count exceeds Int.MAX_VALUE.
 */
public inline fun <T> Sequence<T>.count(predicate: (T) -> Boolean): Int {
    var count = 0
    for (element in this) {
        if (predicate(element)) {
            if (count == Int.MAX_VALUE) throw ArithmeticException("Count overflow has happened.")
            count += 1
        }
    }
    return count
}

/** Checks availability without reading an element; returns false when empty. */
public fun <T> Sequence<T>.any(): Boolean = iterator().hasNext()

/**
 * Stops at the first match; returns false when empty without invoking [predicate].
 * Exceptions propagate; the immediate inline callback admits non-local returns.
 */
public inline fun <T> Sequence<T>.any(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (predicate(element)) return true
    }
    return false
}

/**
 * Stops at the first failure; returns true when empty without invoking [predicate].
 * Exceptions propagate; the immediate inline callback admits non-local returns.
 */
public inline fun <T> Sequence<T>.all(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (!predicate(element)) return false
    }
    return true
}

/** Checks availability without reading an element; returns true when empty. */
public fun <T> Sequence<T>.none(): Boolean = !iterator().hasNext()

/**
 * Stops at the first match; returns true when empty without invoking [predicate].
 * Exceptions propagate; the immediate inline callback admits non-local returns.
 */
public inline fun <T> Sequence<T>.none(predicate: (T) -> Boolean): Boolean {
    for (element in this) {
        if (predicate(element)) return false
    }
    return true
}

internal class TransformingSequence<T, R>(
    private val source: Sequence<T>,
    private val transform: (T) -> R,
) : Sequence<R> {
    override fun iterator(): Iterator<R> = TransformingIterator<T, R>(source.iterator(), transform)
}

internal class TransformingIterator<T, R>(
    private val source: Iterator<T>,
    private val transform: (T) -> R,
) : Iterator<R> {
    override fun hasNext(): Boolean = source.hasNext()
    override fun next(): R = transform(source.next())
}

internal class FilteringSequence<T>(
    private val source: Sequence<T>,
    private val sendWhen: Boolean,
    private val predicate: (T) -> Boolean,
) : Sequence<T> {
    override fun iterator(): Iterator<T> = FilteringIterator<T>(source.iterator(), sendWhen, predicate)
}

internal class FilteringIterator<T>(
    private val source: Iterator<T>,
    private val sendWhen: Boolean,
    private val predicate: (T) -> Boolean,
) : Iterator<T> {
    // -1: not searched yet; 0: exhausted; 1: cached, even when the cached element is null.
    private var nextState: Int = -1
    private val cache: SequenceCache<T> = sequenceCache<T>()

    private fun calculateNext() {
        while (source.hasNext()) {
            val value = source.next()
            if (predicate(value) == sendWhen) {
                cache.store(value)
                nextState = 1
                return
            }
        }
        nextState = 0
    }

    override fun hasNext(): Boolean {
        if (nextState == -1) calculateNext()
        return nextState == 1
    }

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException("Sequence iterator is exhausted")
        val result = cache.take()
        nextState = -1
        return result
    }
}

internal class TakingSequence<T>(
    private val source: Sequence<T>,
    private val count: Int,
) : Sequence<T> {
    override fun iterator(): Iterator<T> = TakingIterator<T>(source.iterator(), count)
}

internal class TakingIterator<T>(
    private val source: Iterator<T>,
    private var remaining: Int,
) : Iterator<T> {
    override fun hasNext(): Boolean = remaining > 0 && source.hasNext()

    override fun next(): T {
        if (remaining == 0) throw NoSuchElementException("Sequence iterator is exhausted")
        val result = source.next()
        remaining -= 1
        return result
    }
}

internal class EmptySequence<T> : Sequence<T> {
    override fun iterator(): Iterator<T> = EmptySequenceIterator<T>()
}

internal class EmptySequenceIterator<T> : Iterator<T> {
    override fun hasNext(): Boolean = false
    override fun next(): T = throw NoSuchElementException("Sequence iterator is exhausted")
}

internal class NotNullSequence<T : Any>(private val source: Sequence<T?>) : Sequence<T> {
    override fun iterator(): Iterator<T> = NotNullIterator<T>(source.iterator())
}

internal class NotNullIterator<T : Any>(private val source: Iterator<T?>) : Iterator<T> {
    // -1: not searched yet; 0: exhausted; 1: a non-null value is cached.
    private var nextState: Int = -1
    private val cache: SequenceCache<T> = sequenceCache<T>()

    private fun calculateNext() {
        while (source.hasNext()) {
            val value = source.next()
            if (value != null) {
                cache.store(value)
                nextState = 1
                return
            }
        }
        nextState = 0
    }

    override fun hasNext(): Boolean {
        if (nextState == -1) calculateNext()
        return nextState == 1
    }

    override fun next(): T {
        if (nextState == -1) calculateNext()
        if (nextState == 0) throw NoSuchElementException("Sequence iterator is exhausted")
        nextState = -1
        return cache.take()
    }
}

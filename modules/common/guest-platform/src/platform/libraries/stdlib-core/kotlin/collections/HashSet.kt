/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

/**
 * A mutable hash set containing one element from each group of equal elements.
 *
 * Elements must retain stable equality and hash codes while stored. Nullable element types may store
 * null, which hashes to zero. Generic floating equality coalesces NaNs and distinguishes positive
 * and negative zero. User equality and hashing callbacks retain their effects and exceptions.
 *
 * Iteration order is unspecified. Read-only [Set] aliases observe mutations. Adding a new element,
 * removing an existing element or clearing a non-empty set invalidates existing iterators; adding a
 * duplicate does not. Storage and traversal use ordinary Guest heap and instruction budgets.
 *
 * @param T element type.
 * @param initialCapacity requested backing-map bucket capacity, not the initial size or an element limit.
 * @constructor creates an empty set with the requested capacity; the default request is 16.
 * @throws IllegalArgumentException if [initialCapacity] is negative or greater than 1,073,741,824.
 * @see MutableSet
 * @see HashMap
 */
public class HashSet<T>(
    initialCapacity: Int = 16,
) : MutableSet<T> {
    private val map: HashMap<T, Boolean> = HashMap<T, Boolean>(initialCapacity)
    /**
     * Number of distinct elements currently stored in this set.
     */
    public override val size: Int get() = map.size

    /**
     * Checks whether this set contains no elements.
     *
     * @return true when [size] is zero.
     */
    public override fun isEmpty(): Boolean = map.isEmpty()

    /**
     * Checks whether an element equal to [element] belongs to this set.
     *
     * Uses generic equality and hashing. Also supports `element in set` syntax.
     *
     * @param element value to search for, including null for nullable element types.
     * @return true if an equal element is present.
     */
    public override fun contains(element: T): Boolean = map.containsKey(element)

    /**
     * Adds [element] if no equal element is already present.
     *
     * A duplicate retains the original stored element and leaves existing iterators valid. A new element
     * increases [size], may grow the backing table and invalidates existing iterators.
     *
     * @param element value to add, with stable equality and hashing while stored.
     * @return true if a new element was inserted, or false for a duplicate.
     * @throws IllegalArgumentException if inserting a new element exceeds the supported size or bucket capacity.
     */
    public override fun add(element: T): Boolean = map.put(element, true) == null

    /**
     * Removes an element equal to [element], if one exists.
     *
     * Successful removal decreases [size] and invalidates existing iterators. An absent element leaves
     * the set unchanged.
     *
     * @param element value to search for and remove.
     * @return true if an element was removed, or false when no equal element exists.
     */
    public override fun remove(element: T): Boolean = map.remove(element) != null

    /**
     * Removes all elements while retaining backing bucket capacity for reuse.
     *
     * A non-empty clear invalidates existing iterators. Clearing an already empty set leaves them valid.
     * Read-only aliases observe the empty set.
     */
    public override fun clear(): Unit = map.clear()

    /**
     * Creates a new mutable iterator over this set in unspecified order.
     *
     * Iterator removal is allowed once after each successful next call and preserves that iterator's
     * traversal. External structural mutations invalidate next and remove; hasNext does not validate
     * structural changes. Adding an equal existing element does not invalidate the iterator.
     *
     * The iterator throws [NoSuchElementException] from next after exhaustion, and [IllegalStateException]
     * from next or remove after invalidation, or from remove before next or twice after the same next.
     *
     * @return a fresh iterator positioned before the first element.
     */
    public override fun iterator(): MutableIterator<T> = HashMapKeyIterator(map.nodeIterator())

    /** Compares elements using the set's ordering rules and nullable generic equality. */
    public override fun equals(other: Any?): Boolean = collectionSetEquals(this, other)

    /** Combines element hashes with ordinary Int overflow; null contributes zero. */
    public override fun hashCode(): Int = collectionSetHash(this)

    /** Formats elements in brackets; a direct self reference uses `(this Collection)`. */
    public override fun toString(): String = collectionText(this)

    internal fun containsAnyElement(element: Any?): Boolean = map.containsAnyKey(element)

    internal fun iteratorReadOnly(): Iterator<T> = HashMapKeyIterator(map.nodeIterator())
}

/**
 * Creates a fresh empty [HashSet] exposed through the read-only [Set] interface.
 *
 * The result is not a shared singleton. Read-only access does not make the underlying set immutable.
 *
 * @param T element type.
 * @return a new set with size zero.
 * @see setOf
 */
public fun <T> emptySet(): Set<T> = HashSet<T>(0)

/**
 * Creates a fresh read-only set containing the distinct supplied [elements].
 *
 * Uses [HashSet] storage. Equal elements coalesce, retaining the first stored element. Arguments are
 * evaluated once in source order before population performs hashing or equality. Iteration order is
 * unspecified. Read-only access does not make the underlying set immutable.
 * Guest Kotlin supports direct vararg arguments; spread arguments remain unavailable.
 *
 * @param T element type.
 * @param elements values to insert in argument order.
 * @return a new set, empty when no elements are supplied.
 * @see mutableSetOf
 * @see emptySet
 */
public external fun <T> setOf(vararg elements: T): Set<T>

/**
 * Creates a fresh mutable [HashSet] containing the distinct supplied [elements].
 *
 * Equal elements coalesce, retaining the first stored element. Arguments are evaluated once in source
 * order before population performs hashing or equality. Iteration order is unspecified.
 * Guest Kotlin supports direct vararg arguments; spread arguments remain unavailable.
 *
 * @param T element type.
 * @param elements values to insert in argument order.
 * @return a new mutable set, empty when no elements are supplied.
 * @see setOf
 */
public external fun <T> mutableSetOf(vararg elements: T): MutableSet<T>

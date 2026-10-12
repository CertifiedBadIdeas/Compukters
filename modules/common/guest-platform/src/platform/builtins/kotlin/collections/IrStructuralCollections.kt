/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

import kotlin.*

/**
 * A source of elements that can be traversed with an [Iterator].
 *
 * The implementation determines iteration order. This read-only contract does not guarantee
 * that the underlying elements or collection are immutable.
 *
 * @param T element type.
 */
public interface Iterable<out T> {
    /**
     * Returns an iterator over this iterable's elements.
     *
     * @return an iterator positioned before the first element.
     */
    public operator fun iterator(): Iterator<T>
}

/**
 * Visits elements of an [Iterable] one at a time.
 *
 * Exhaustion and modification checks are defined by the implementation. ArrayList iterators
 * use [IllegalArgumentException] for an invalid next call; hash iterators use
 * [NoSuchElementException] for exhaustion and [IllegalStateException] for invalidation.
 *
 * @param T element type.
 */
public interface Iterator<out T> {
    /**
     * Checks whether another element is available without advancing the iterator.
     *
     * @return true if another element remains.
     */
    public operator fun hasNext(): Boolean

    /**
     * Returns the next element and advances the iterator.
     *
     * Call [hasNext] before advancing when exhaustion is possible. The implementation rejects
     * calls after exhaustion or invalidation; the exception type depends on that implementation.
     *
     * @return the next element, including null for nullable element types.
     */
    public operator fun next(): T
}

/**
 * A read-only view of a group of elements with size and membership queries.
 *
 * Read-only access does not guarantee immutability. A view of a mutable collection may observe
 * later changes. Concrete implementations define ordering and whether duplicates are permitted.
 *
 * @param T element type.
 */
public interface Collection<out T> : Iterable<T> {
    /**
     * Number of elements in this collection.
     */
    public val size: Int

    /**
     * Checks whether this collection has no elements.
     *
     * @return true when the collection is empty.
     */
    public fun isEmpty(): Boolean

    /**
     * Checks whether this collection contains an element equal to [element].
     *
     * Also supports `element in collection` syntax.
     *
     * @param element value to search for.
     * @return true if an equal element belongs to this collection.
     */
    public operator fun contains(element: @UnsafeVariance T): Boolean
}

/**
 * A read-only ordered collection with zero-based indexed access.
 *
 * Lists may contain duplicate elements. Read-only access does not make the underlying object
 * immutable; aliases of [ArrayList] observe its mutations. Nullable element types may store null.
 *
 * @param T element type.
 * @see MutableList
 */
public interface List<out T> : Collection<T> {
    /**
     * Returns the element at [index].
     *
     * Valid indexes range from zero through size minus one. Invalid indexes are rejected by the
     * implementation; [ArrayList] throws [IllegalArgumentException].
     *
     * @param index zero-based element index.
     * @return the stored element.
     * @see getOrNull
     */
    public operator fun get(index: Int): T

    /**
     * Returns the index of the first element equal to [element].
     *
     * @param element value to search for.
     * @return the first matching index, or -1 when no match exists.
     */
    public fun indexOf(element: @UnsafeVariance T): Int

    /**
     * Returns the index of the last element equal to [element].
     *
     * @param element value to search for.
     * @return the last matching index, or -1 when no match exists.
     */
    public fun lastIndexOf(element: @UnsafeVariance T): Int
}

public interface Set<out T> : Collection<T>

public interface Map<K, out V> {
    public val size: Int
    public fun isEmpty(): Boolean
    public fun containsKey(key: K): Boolean
    public fun containsValue(value: @UnsafeVariance V): Boolean
    public operator fun get(key: K): V?
    public val keys: Set<K>
    public val values: Collection<V>
    public val entries: Set<Entry<K, V>>

    public interface Entry<out K, out V> {
        public val key: K
        public val value: V
    }
}

internal interface ListIterator<out T> : Iterator<T>

/**
 * An [Iterable] whose iterator supports element removal.
 *
 * @param T element type.
 */
public interface MutableIterable<out T> : Iterable<T> {
    /**
     * Returns an iterator that can remove the most recently returned element.
     *
     * @return a mutable iterator positioned before the first element.
     */
    public override fun iterator(): MutableIterator<T>
}

/**
 * An [Iterator] that can remove the most recently returned element.
 *
 * @param T element type.
 */
public interface MutableIterator<out T> : Iterator<T> {
    /**
     * Removes the element returned by the most recent successful [next] call.
     *
     * At most one removal is allowed per successful next call. Removing through this iterator
     * preserves its traversal. Implementations reject removal before next, repeated removal, or
     * removal after invalidation. ArrayList iterators use [IllegalArgumentException]; hash
     * iterators use [IllegalStateException].
     */
    public fun remove(): Unit
}

/**
 * A collection that supports insertion, removal and clearing.
 *
 * Its implementation determines ordering, duplicate handling and iterator invalidation.
 *
 * @param T element type.
 */
public interface MutableCollection<T> : Collection<T>, MutableIterable<T> {
    /**
     * Adds [element] to this collection.
     *
     * Lists retain duplicates; sets may leave the collection unchanged for an equal element.
     *
     * @param element element to add.
     * @return true if the collection changed.
     */
    public fun add(element: T): Boolean

    /**
     * Removes one element equal to [element], if one exists.
     *
     * For lists, removes the first matching element. An absent element leaves the collection unchanged.
     *
     * @param element value to remove.
     * @return true if an element was removed.
     */
    public fun remove(element: T): Boolean

    /**
     * Removes all elements from this collection.
     *
     * Read-only aliases observe the resulting empty collection. Backing capacity and iterator
     * invalidation follow the concrete implementation's contract.
     */
    public fun clear(): Unit
}

/**
 * A mutable ordered collection with indexed insertion, replacement and removal.
 *
 * Mutations are visible through read-only [List] aliases. Duplicate elements are permitted.
 *
 * @param T element type.
 * @see ArrayList
 */
public interface MutableList<T> : List<T>, MutableCollection<T> {
    /**
     * Inserts [element] at [index], shifting following elements to the right.
     *
     * An index equal to size appends to the list. Invalid positions are rejected by the implementation;
     * [ArrayList] throws [IllegalArgumentException].
     *
     * @param index insertion position, from zero through size inclusive.
     * @param element element to insert.
     */
    public fun add(index: Int, element: T): Unit

    /**
     * Replaces the element at [index] without changing the list's size.
     *
     * Invalid indexes are rejected by the implementation; [ArrayList] throws [IllegalArgumentException].
     *
     * @param index zero-based element index, less than size.
     * @param element replacement element.
     * @return the previous element at [index].
     */
    public operator fun set(index: Int, element: T): T

    /**
     * Removes the element at [index], shifting following elements to the left.
     *
     * Invalid indexes are rejected by the implementation; [ArrayList] throws [IllegalArgumentException].
     *
     * @param index zero-based element index, less than size.
     * @return the removed element.
     */
    public fun removeAt(index: Int): T
}

public interface MutableSet<T> : Set<T>, MutableCollection<T>

public interface MutableMap<K, V> : Map<K, V> {
    public fun put(key: K, value: V): V?
    public fun remove(key: K): V?
    public fun clear(): Unit

    public interface MutableEntry<K, V> : Map.Entry<K, V> {
        public fun setValue(newValue: V): V
    }
}

internal interface MutableListIterator<T> : ListIterator<T>, MutableIterator<T>

public abstract class BooleanIterator : Iterator<Boolean> {
    public abstract override operator fun next(): Boolean

    public abstract fun nextBoolean(): Boolean
}

public abstract class ByteIterator : Iterator<Byte> {
    public abstract override operator fun next(): Byte

    public abstract fun nextByte(): Byte
}

public abstract class CharIterator : Iterator<Char> {
    public abstract override operator fun next(): Char

    public abstract fun nextChar(): Char
}

public abstract class ShortIterator : Iterator<Short> {
    public abstract override operator fun next(): Short

    public abstract fun nextShort(): Short
}

public abstract class IntIterator : Iterator<Int> {
    public abstract override operator fun next(): Int

    public abstract fun nextInt(): Int
}

public abstract class LongIterator : Iterator<Long> {
    public abstract override operator fun next(): Long

    public abstract fun nextLong(): Long
}

public abstract class FloatIterator : Iterator<Float> {
    public abstract override operator fun next(): Float

    public abstract fun nextFloat(): Float
}

public abstract class DoubleIterator : Iterator<Double> {
    public abstract override operator fun next(): Double

    public abstract fun nextDouble(): Double
}

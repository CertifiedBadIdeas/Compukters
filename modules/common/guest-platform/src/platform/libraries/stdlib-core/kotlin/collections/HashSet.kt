/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

/** A mutable hash set with stable-key requirements and ordinary Guest resource accounting. */
public class HashSet<T>(initialCapacity: Int = 16) : MutableSet<T> {
    private val map: HashMap<T, Boolean> = HashMap<T, Boolean>(initialCapacity)
    public override val size: Int get() = map.size
    public override fun isEmpty(): Boolean = map.isEmpty()
    public override fun contains(element: T): Boolean = map.containsKey(element)
    public override fun add(element: T): Boolean = map.put(element, true) == null
    public override fun remove(element: T): Boolean = map.remove(element) != null
    public override fun clear(): Unit = map.clear()
    public override fun iterator(): MutableIterator<T> = HashMapKeyIterator(map.nodeIterator())
    internal fun iteratorReadOnly(): Iterator<T> = HashMapKeyIterator(map.nodeIterator())
}

/** Creates a fresh empty set with a read-only view. */
public fun <T> emptySet(): Set<T> = HashSet<T>(0)

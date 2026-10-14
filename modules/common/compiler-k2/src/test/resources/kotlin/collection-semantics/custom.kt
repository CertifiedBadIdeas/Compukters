/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

private open class ReadList<T>(private val backing: List<T>) : List<T> {
    fun readSize(): Int = -77
    override val size: Int get() = backing.size
    override fun isEmpty(): Boolean = backing.isEmpty()
    override fun get(index: Int): T = backing[index]
    override fun contains(element: T): Boolean = backing.contains(element)
    override fun indexOf(element: T): Int = backing.indexOf(element)
    override fun lastIndexOf(element: T): Int = backing.lastIndexOf(element)
    override fun iterator(): Iterator<T> = ReadIterator(backing.iterator())
}

private class ReadListChild(backing: List<Int>) : ReadList<Int>(backing)

private class ReadIterator<T>(private val backing: Iterator<T>) : Iterator<T> {
    override fun hasNext(): Boolean = backing.hasNext()
    override fun next(): T = backing.next()
}

private class ReadSet<T>(private val backing: Set<T>) : Set<T> {
    override val size: Int get() = backing.size
    override fun isEmpty(): Boolean = backing.isEmpty()
    override fun contains(element: T): Boolean = backing.contains(element)
    override fun iterator(): Iterator<T> = ReadIterator(backing.iterator())
}

private class ReadMap<K, V>(private val backing: Map<K, V>) : Map<K, V> {
    override val size: Int get() = backing.size
    override fun isEmpty(): Boolean = backing.isEmpty()
    override fun containsKey(key: K): Boolean = backing.containsKey(key)
    override fun containsValue(value: V): Boolean = backing.containsValue(value)
    override fun get(key: K): V? = backing[key]
    override val keys: Set<K> get() = backing.keys
    override val values: Collection<V> get() = backing.values
    override val entries: Set<Map.Entry<K, V>> get() = backing.entries
}

private class ReadEntry<K, V>(override val key: K, override val value: V) : Map.Entry<K, V>

fun main() {
    val child = ReadListChild(listOf(1, 2))
    check(child.readSize() == -77)
    check(listOf<Any?>(1, 2) == child)
    check(listOf(1, 2) == ReadList(listOf<Any?>(1, 2)))
    check(listOf<Any?>(1, 2) == ReadList(listOf(1, 2)))
    check(setOf(1, 2) == ReadSet(setOf<Any?>(2, 1)))
    check(setOf<Any?>(1, 2) == ReadSet(setOf(2, 1)))
    check(mapOf("one" to 1, "two" to null) == ReadMap(mapOf<Any?, Any?>("two" to null, "one" to 1)))
    check(mapOf<Any?, Any?>("one" to 1, "two" to null) == ReadMap(mapOf("two" to null, "one" to 1)))
    check(mapOf("x" to 7).entries.iterator().next() == ReadEntry<Any?, Any?>("x", 7))
    check(mapOf("x" to 7).entries.contains(ReadEntry("x", 7)))
    println("collection custom ok")
}

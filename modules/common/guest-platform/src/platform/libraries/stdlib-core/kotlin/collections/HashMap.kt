/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

/**
 * A hash table charged to the ordinary Guest heap and instruction budgets.
 * Keys must retain stable equality and hash codes while stored. Iteration order is unspecified.
 */
public class HashMap<K, V>(initialCapacity: Int = 16) : MutableMap<K, V> {
    private var buckets: ArrayList<HashMapNode<K, V>?> = hashMapBuckets<K, V>(initialCapacity)
    public override var size: Int = 0
        private set
    internal var modificationCount: Int = 0
        private set

    public override fun isEmpty(): Boolean = size == 0
    public override fun containsKey(key: K): Boolean = find(key) != null
    public override fun get(key: K): V? = find(key)?.value

    public override fun containsValue(value: V): Boolean {
        val iterator = nodeIterator()
        while (iterator.hasNext()) {
            if (value == iterator.next().value) return true
        }
        return false
    }

    public override fun put(key: K, value: V): V? {
        val hash = keyHash(key)
        var index = bucketIndex(hash)
        var node = buckets[index]
        while (node != null) {
            if (node.hash == hash && key == node.key) return node.setValue(value)
            node = node.next
        }
        require(size < Int.MAX_VALUE)
        val inserted = HashMapNode(key, value, hash, null)
        if (size >= buckets.size - buckets.size / 4) {
            grow()
            index = bucketIndex(hash)
        }
        inserted.next = buckets[index]
        buckets[index] = inserted
        size += 1
        modificationCount += 1
        return null
    }

    public override fun remove(key: K): V? {
        val hash = keyHash(key)
        val index = bucketIndex(hash)
        var previous: HashMapNode<K, V>? = null
        var node = buckets[index]
        while (node != null) {
            if (node.hash == hash && key == node.key) {
                unlink(index, previous, node)
                return node.value
            }
            previous = node
            node = node.next
        }
        return null
    }

    public override fun clear(): Unit {
        if (size == 0) return
        var index = 0
        while (index < buckets.size) {
            var node = buckets[index]
            while (node != null) {
                val next = node.next
                node.next = null
                node = next
            }
            buckets[index] = null
            index += 1
        }
        size = 0
        modificationCount += 1
    }

    /** Live read-only views; later map mutations are visible through previously acquired views. */
    public override val keys: Set<K> get() = HashMapKeys(this)
    public override val values: Collection<V> get() = HashMapValues(this)
    public override val entries: Set<Map.Entry<K, V>> get() = HashMapEntries(this)

    private fun bucketIndex(hash: Int): Int = hash and (buckets.size - 1)

    private fun keyHash(key: K): Int {
        val hash = key.hashCode()
        return hash xor (hash ushr 16)
    }

    private fun find(key: K): HashMapNode<K, V>? {
        val hash = keyHash(key)
        var node = buckets[bucketIndex(hash)]
        while (node != null) {
            if (node.hash == hash && key == node.key) return node
            node = node.next
        }
        return null
    }

    private fun grow(): Unit {
        require(buckets.size < 1073741824)
        val replacement = hashMapBuckets<K, V>(buckets.size * 2)
        var index = 0
        while (index < buckets.size) {
            var node = buckets[index]
            while (node != null) {
                val next = node.next
                val destination = node.hash and (replacement.size - 1)
                node.next = replacement[destination]
                replacement[destination] = node
                node = next
            }
            index += 1
        }
        buckets = replacement
    }

    private fun unlink(index: Int, previous: HashMapNode<K, V>?, node: HashMapNode<K, V>): Unit {
        if (previous == null) buckets[index] = node.next else previous.next = node.next
        node.next = null
        size -= 1
        modificationCount += 1
    }

    internal fun removeNode(target: HashMapNode<K, V>): Unit {
        val index = bucketIndex(target.hash)
        var previous: HashMapNode<K, V>? = null
        var node = buckets[index]
        while (node != null) {
            if (node === target) {
                unlink(index, previous, node)
                return
            }
            previous = node
            node = node.next
        }
        throw IllegalStateException("Entry is no longer present")
    }

    internal fun nodeIterator(): MutableIterator<HashMapNode<K, V>> = HashMapIterator(this)
    internal fun bucketCount(): Int = buckets.size
    internal fun bucketAt(index: Int): HashMapNode<K, V>? = buckets[index]
}

internal fun <K, V> hashMapBuckets(capacity: Int): ArrayList<HashMapNode<K, V>?> {
    require(capacity >= 0 && capacity <= 1073741824)
    var count = 4
    while (count < capacity) count *= 2
    val buckets = ArrayList<HashMapNode<K, V>?>(count)
    var index = 0
    while (index < count) {
        buckets.add(null)
        index += 1
    }
    return buckets
}

internal class HashMapNode<K, V>(
    public override val key: K,
    value: V,
    internal val hash: Int,
    internal var next: HashMapNode<K, V>?,
) : MutableMap.MutableEntry<K, V> {
    public override var value: V = value
        private set

    public override fun setValue(newValue: V): V {
        val previous = value
        value = newValue
        return previous
    }
}

internal class HashMapIterator<K, V>(private val map: HashMap<K, V>) : MutableIterator<HashMapNode<K, V>> {
    private var bucket: Int = 0
    private var upcoming: HashMapNode<K, V>? = null
    private var returned: HashMapNode<K, V>? = null
    private var expectedModificationCount: Int = map.modificationCount

    private fun prepare(): Unit {
        while (upcoming == null && bucket < map.bucketCount()) {
            upcoming = map.bucketAt(bucket)
            bucket += 1
        }
    }

    public override fun hasNext(): Boolean {
        prepare()
        return upcoming != null
    }

    public override fun next(): HashMapNode<K, V> {
        check(expectedModificationCount == map.modificationCount)
        prepare()
        val node = upcoming ?: throw NoSuchElementException("Iterator is exhausted")
        upcoming = node.next
        returned = node
        return node
    }

    public override fun remove(): Unit {
        check(expectedModificationCount == map.modificationCount)
        val node = returned ?: throw IllegalStateException("Call next before remove")
        map.removeNode(node)
        returned = null
        expectedModificationCount = map.modificationCount
    }
}

internal class HashMapKeys<K, V>(private val map: HashMap<K, V>) : Set<K> {
    public override val size: Int get() = map.size
    public override fun isEmpty(): Boolean = map.isEmpty()
    public override fun contains(element: K): Boolean = map.containsKey(element)
    public override fun iterator(): Iterator<K> = HashMapKeyIterator(map.nodeIterator())
}

internal class HashMapKeyIterator<K, V>(private val iterator: MutableIterator<HashMapNode<K, V>>) : MutableIterator<K> {
    public override fun hasNext(): Boolean = iterator.hasNext()
    public override fun next(): K = iterator.next().key
    public override fun remove(): Unit = iterator.remove()
}

internal class HashMapValues<K, V>(private val map: HashMap<K, V>) : Collection<V> {
    public override val size: Int get() = map.size
    public override fun isEmpty(): Boolean = map.isEmpty()
    public override fun contains(element: V): Boolean = map.containsValue(element)
    public override fun iterator(): Iterator<V> = HashMapValueIterator(map.nodeIterator())
}

internal class HashMapValueIterator<K, V>(private val iterator: MutableIterator<HashMapNode<K, V>>) : Iterator<V> {
    public override fun hasNext(): Boolean = iterator.hasNext()
    public override fun next(): V = iterator.next().value
}

internal class HashMapEntries<K, V>(private val map: HashMap<K, V>) : Set<Map.Entry<K, V>> {
    public override val size: Int get() = map.size
    public override fun isEmpty(): Boolean = map.isEmpty()
    public override fun contains(element: Map.Entry<K, V>): Boolean =
        map.containsKey(element.key) && map[element.key] == element.value
    public override fun iterator(): Iterator<Map.Entry<K, V>> = HashMapEntryIterator(map.nodeIterator())
}

internal class HashMapEntryIterator<K, V>(private val iterator: MutableIterator<HashMapNode<K, V>>) : Iterator<Map.Entry<K, V>> {
    public override fun hasNext(): Boolean = iterator.hasNext()
    public override fun next(): Map.Entry<K, V> = iterator.next()
}

/** Replaces a value, returning the previous value or null when the key was absent. */
public operator fun <K, V> MutableMap<K, V>.set(key: K, value: V): Unit { put(key, value) }

public operator fun <K, V> Map<K, V>.contains(key: K): Boolean = containsKey(key)

public fun <K, V> Map<K, V>.isNotEmpty(): Boolean = !isEmpty()

public operator fun <K, V> Map<K, V>.iterator(): Iterator<Map.Entry<K, V>> = entries.iterator()

public operator fun <K, V> Map.Entry<K, V>.component1(): K = key

public operator fun <K, V> Map.Entry<K, V>.component2(): V = value

/** Creates a fresh empty map with a read-only view. */
public fun <K, V> emptyMap(): Map<K, V> = HashMap<K, V>(0)

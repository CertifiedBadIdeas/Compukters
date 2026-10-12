/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

/**
 * A mutable hash map associating each distinct key with one value.
 *
 * Keys must retain stable equality and hash codes while stored. Nullable key and value types may
 * store null; null keys hash to zero. Generic floating equality coalesces NaNs and distinguishes
 * positive and negative zero. User equality and hashing callbacks retain their effects and exceptions.
 *
 * Iteration order is unspecified. Read-only aliases and views observe later mutations. Inserting a new
 * key, removing an existing key or clearing a non-empty map invalidates iterators; replacing a value
 * does not. Storage, entries, views and iterators use the ordinary Guest heap and instruction budgets.
 *
 * @param K key type.
 * @param V value type.
 * @param initialCapacity requested bucket capacity, not the initial size or an element limit.
 * @constructor creates an empty map, rounding bucket capacity up to a power of two with a minimum of four; the default request is 16.
 * @throws IllegalArgumentException if [initialCapacity] is negative or greater than 1,073,741,824.
 * @see MutableMap
 */
public class HashMap<K, V>(
    initialCapacity: Int = 16,
) : MutableMap<K, V> {
    private var buckets: ArrayList<HashMapNode<K, V>?> = hashMapBuckets<K, V>(initialCapacity)
    /**
     * Number of key-value associations currently stored in this map.
     */
    public override var size: Int = 0
        private set
    internal var modificationCount: Int = 0
        private set

    /**
     * Checks whether this map contains no associations.
     *
     * @return true when [size] is zero.
     */
    public override fun isEmpty(): Boolean = size == 0

    /**
     * Checks whether an association exists for [key], even if its value is null.
     *
     * Uses key hashing and generic equality. The key's equality and hash code must remain stable while stored.
     *
     * @param key key to search for.
     * @return true if an equal key is present.
     * @see get
     */
    public override fun containsKey(key: K): Boolean = find(key) != null

    /**
     * Returns the value associated with [key], or null when the key is absent.
     *
     * Also supports `map[key]`. A stored null produces the same result as an absent key;
     * use [containsKey] to distinguish them.
     *
     * @param key key to look up.
     * @return the associated value, or null if no association exists.
     */
    public override fun get(key: K): V? = find(key)?.value

    /**
     * Checks whether any stored value equals [value].
     *
     * Traverses values in unspecified bucket order and stops at the first match, using generic equality.
     * Nullable value types may search for null.
     *
     * @param value value to search for.
     * @return true if at least one stored value is equal.
     */
    public override fun containsValue(value: V): Boolean {
        val iterator = nodeIterator()
        while (iterator.hasNext()) {
            if (value == iterator.next().value) return true
        }
        return false
    }

    /**
     * Associates [key] with [value], replacing the value for an equal existing key.
     *
     * Replacement retains the original stored key and does not invalidate iterators. Inserting a new key
     * increases [size], may grow the table and invalidates existing iterators. A null result can also mean
     * that the previous value was null; use [containsKey] before mutation to distinguish absence.
     *
     * @param key key to insert or update, with stable equality and hashing while stored.
     * @param value value to associate with the key.
     * @return the previous value, or null when the key was absent.
     * @throws IllegalArgumentException if inserting a new key exceeds the supported size or bucket capacity.
     */
    public override fun put(
        key: K,
        value: V,
    ): V? {
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

    /**
     * Removes the association for [key], if one exists.
     *
     * Removing an existing key decreases [size] and invalidates iterators. An absent key leaves the map
     * unchanged. A null result can also mean that a null value was removed.
     *
     * @param key key whose association should be removed.
     * @return the removed value, or null when the key was absent.
     */
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

    /**
     * Removes all associations while retaining bucket capacity for reuse.
     *
     * Releases the table's references to removed entries. A non-empty clear invalidates existing iterators;
     * clearing an already empty map leaves them valid. Previously acquired views become empty.
     */
    public override fun clear() {
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

    /**
     * Live read-only set of this map's keys in unspecified iteration order.
     *
     * The view reflects later insertions, removals and clearing. It exposes no collection mutation methods.
     * Structural map changes invalidate its iterator's next call; replacing a value does not.
     * Iterator exhaustion throws [NoSuchElementException], and invalidation throws [IllegalStateException].
     */
    public override val keys: Set<K> get() = HashMapKeys(this)
    /**
     * Live read-only collection of this map's values, including duplicates and nullable values.
     *
     * The view reflects later insertions, replacements, removals and clearing, in unspecified iteration order.
     * It exposes no collection mutation methods. Structural changes invalidate its iterator's next call;
     * value replacement does not. Exhaustion throws [NoSuchElementException], and invalidation throws [IllegalStateException].
     */
    public override val values: Collection<V> get() = HashMapValues(this)
    /**
     * Live read-only set of this map's key-value entries in unspecified iteration order.
     *
     * The view reflects later map changes. Returned entries refer to stored nodes, so replacing a value
     * updates the corresponding entry. A removed entry is detached: retaining it does not retain its
     * association in the map, and changing its value no longer updates the map.
     *
     * Entries may be cast to the matching [MutableMap.MutableEntry] type to call setValue; the view itself
     * exposes no collection mutation methods. Structural changes invalidate iterator next calls;
     * value replacement does not. Exhaustion throws [NoSuchElementException], and invalidation throws [IllegalStateException].
     */
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

    private fun grow() {
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

    private fun unlink(
        index: Int,
        previous: HashMapNode<K, V>?,
        node: HashMapNode<K, V>,
    ) {
        if (previous == null) buckets[index] = node.next else previous.next = node.next
        node.next = null
        size -= 1
        modificationCount += 1
    }

    internal fun removeNode(target: HashMapNode<K, V>) {
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

internal class HashMapIterator<K, V>(
    private val map: HashMap<K, V>,
) : MutableIterator<HashMapNode<K, V>> {
    private var bucket: Int = 0
    private var upcoming: HashMapNode<K, V>? = null
    private var returned: HashMapNode<K, V>? = null
    private var expectedModificationCount: Int = map.modificationCount

    private fun prepare() {
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

    public override fun remove() {
        check(expectedModificationCount == map.modificationCount)
        val node = returned ?: throw IllegalStateException("Call next before remove")
        map.removeNode(node)
        returned = null
        expectedModificationCount = map.modificationCount
    }
}

internal class HashMapKeys<K, V>(
    private val map: HashMap<K, V>,
) : Set<K> {
    public override val size: Int get() = map.size

    public override fun isEmpty(): Boolean = map.isEmpty()

    public override fun contains(element: K): Boolean = map.containsKey(element)

    public override fun iterator(): Iterator<K> = HashMapKeyIterator(map.nodeIterator())
}

internal class HashMapKeyIterator<K, V>(
    private val iterator: MutableIterator<HashMapNode<K, V>>,
) : MutableIterator<K> {
    public override fun hasNext(): Boolean = iterator.hasNext()

    public override fun next(): K = iterator.next().key

    public override fun remove(): Unit = iterator.remove()
}

internal class HashMapValues<K, V>(
    private val map: HashMap<K, V>,
) : Collection<V> {
    public override val size: Int get() = map.size

    public override fun isEmpty(): Boolean = map.isEmpty()

    public override fun contains(element: V): Boolean = map.containsValue(element)

    public override fun iterator(): Iterator<V> = HashMapValueIterator(map.nodeIterator())
}

internal class HashMapValueIterator<K, V>(
    private val iterator: MutableIterator<HashMapNode<K, V>>,
) : Iterator<V> {
    public override fun hasNext(): Boolean = iterator.hasNext()

    public override fun next(): V = iterator.next().value
}

internal class HashMapEntries<K, V>(
    private val map: HashMap<K, V>,
) : Set<Map.Entry<K, V>> {
    public override val size: Int get() = map.size

    public override fun isEmpty(): Boolean = map.isEmpty()

    public override fun contains(element: Map.Entry<K, V>): Boolean = map.containsKey(element.key) && map[element.key] == element.value

    public override fun iterator(): Iterator<Map.Entry<K, V>> = HashMapEntryIterator(map.nodeIterator())
}

internal class HashMapEntryIterator<K, V>(
    private val iterator: MutableIterator<HashMapNode<K, V>>,
) : Iterator<Map.Entry<K, V>> {
    public override fun hasNext(): Boolean = iterator.hasNext()

    public override fun next(): Map.Entry<K, V> = iterator.next()
}

/**
 * Associates [key] with [value] using indexed assignment, discarding the previous value.
 *
 * Delegates to [MutableMap.put]. Use put directly when the previous value is needed.
 *
 * @receiver mutable map to update.
 * @param key key to insert or update.
 * @param value value to associate with the key.
 * @see MutableMap.put
 */
public operator fun <K, V> MutableMap<K, V>.set(
    key: K,
    value: V,
) {
    put(key, value)
}

/**
 * Checks key membership using `key in map` syntax.
 *
 * A key associated with null still belongs to the map. Delegates to [Map.containsKey].
 *
 * @receiver map to inspect.
 * @param key key to search for.
 * @return true if an association exists for the key.
 */
public operator fun <K, V> Map<K, V>.contains(key: K): Boolean = containsKey(key)

/**
 * Checks whether this map contains at least one association.
 *
 * Delegates to [Map.isEmpty] without traversing the map itself.
 *
 * @receiver map to inspect.
 * @return true when isEmpty returns false.
 */
public fun <K, V> Map<K, V>.isNotEmpty(): Boolean = !isEmpty()

/**
 * Returns an iterator over this map's entries, enabling `for ((key, value) in map)`.
 *
 * Delegates to [Map.entries].iterator; ordering and invalidation follow the concrete map's contract.
 *
 * @receiver map to traverse.
 * @return an entry iterator positioned before the first entry.
 */
public operator fun <K, V> Map<K, V>.iterator(): Iterator<Map.Entry<K, V>> = entries.iterator()

/**
 * Returns this entry's key for destructuring.
 *
 * @receiver entry to read.
 * @return the key, including null for nullable key types.
 * @see component2
 */
public operator fun <K, V> Map.Entry<K, V>.component1(): K = key

/**
 * Returns this entry's current value for destructuring.
 *
 * @receiver entry to read.
 * @return the value, including null for nullable value types.
 * @see component1
 */
public operator fun <K, V> Map.Entry<K, V>.component2(): V = value

/**
 * Creates a fresh empty [HashMap] exposed through the read-only [Map] interface.
 *
 * The result is not a shared singleton. Read-only access does not make the underlying map immutable.
 *
 * @param K key type.
 * @param V value type.
 * @return a new map with size zero.
 * @see mapOf
 */
public fun <K, V> emptyMap(): Map<K, V> = HashMap<K, V>(0)

/**
 * Creates a fresh read-only map containing the supplied key-value [pairs].
 *
 * Uses [HashMap] storage. Later pairs with equal keys replace earlier values while retaining the first
 * stored key. Arguments are evaluated once in source order before population performs hashing or equality.
 * Iteration order is unspecified. Read-only access does not make the underlying map immutable.
 * Guest Kotlin supports direct vararg arguments; spread arguments remain unavailable.
 *
 * @param K key type.
 * @param V value type.
 * @param pairs associations to insert in argument order.
 * @return a new map, empty when no pairs are supplied.
 * @see mutableMapOf
 * @see emptyMap
 */
public external fun <K, V> mapOf(vararg pairs: Pair<K, V>): Map<K, V>

/**
 * Creates a fresh mutable [HashMap] containing the supplied key-value [pairs].
 *
 * Later pairs with equal keys replace earlier values while retaining the first stored key. Arguments
 * are evaluated once in source order before population performs hashing or equality. Iteration order
 * is unspecified. Guest Kotlin supports direct vararg arguments; spread arguments remain unavailable.
 *
 * @param K key type.
 * @param V value type.
 * @param pairs associations to insert in argument order.
 * @return a new mutable map, empty when no pairs are supplied.
 * @see mapOf
 */
public external fun <K, V> mutableMapOf(vararg pairs: Pair<K, V>): MutableMap<K, V>

/**
 * Returns a non-null value for [key], or computes and stores a default value.
 *
 * For a non-null existing value, defaultValue is not invoked. An absent key or a stored null invokes
 * [defaultValue] once, then stores its result, including null. A null result is computed again on a
 * later call. If the callback inserts this key itself, its returned default replaces that value.
 *
 * The callback is inline and supports non-local returns. Exceptions and non-local returns skip the
 * implicit insertion; explicit callback mutations retain their effects. This operation is not atomic.
 *
 * @receiver mutable map to read and possibly update.
 * @param key key to look up or insert.
 * @param defaultValue callback supplying a value for an absent key or an existing null value.
 * @return the existing non-null value, or the computed and stored default, possibly null.
 * @see MutableMap.put
 */
public inline fun <K, V> MutableMap<K, V>.getOrPut(
    key: K,
    defaultValue: () -> V,
): V {
    val value = get(key)
    if (value != null) return value
    val answer = defaultValue()
    put(key, answer)
    return answer
}

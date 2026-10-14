/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

// Compiler-generated read bridges implement these internal protocols. They never permit writes.
internal interface CollectionIteratorRead {
    fun readHasNext(): Boolean
    fun readNext(): Any?
}

internal interface ListRead {
    fun readSize(): Int
    fun readIterator(): CollectionIteratorRead
}

internal interface SetRead {
    fun readSize(): Int
    fun readContains(element: Any?): Boolean
}

internal interface MapRead {
    fun readSize(): Int
    fun readContainsKey(key: Any?): Boolean
    fun readGet(key: Any?): Any?
}

internal interface EntryRead {
    fun readKey(): Any?
    fun readValue(): Any?
}

internal fun <T> collectionReadContains(set: Set<T>, element: Any?): Boolean {
    val iterator = set.iterator()
    while (iterator.hasNext()) {
        if (element == iterator.next()) return true
    }
    return false
}

internal fun <K, V> collectionMapReadContainsKey(map: Map<K, V>, key: Any?): Boolean {
    val iterator = map.entries.iterator()
    while (iterator.hasNext()) {
        if (key == iterator.next().key) return true
    }
    return false
}

internal fun <K, V> collectionMapReadGet(map: Map<K, V>, key: Any?): Any? {
    val iterator = map.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        if (key == entry.key) return entry.value
    }
    return null
}

internal fun <T> collectionListEquals(list: List<T>, other: Any?): Boolean {
    if (list === other) return true
    if (!(other is ListRead) || list.size != other.readSize()) return false
    val left = list.iterator()
    val right = other.readIterator()
    while (left.hasNext()) {
        if (!right.readHasNext() || left.next() != right.readNext()) return false
    }
    return !right.readHasNext()
}

internal fun <T> collectionListHash(list: List<T>): Int {
    var hash = 1
    val iterator = list.iterator()
    while (iterator.hasNext()) hash = 31 * hash + iterator.next().hashCode()
    return hash
}

internal fun <T> collectionSetEquals(set: Set<T>, other: Any?): Boolean {
    if (set === other) return true
    if (!(other is SetRead) || set.size != other.readSize()) return false
    val iterator = set.iterator()
    while (iterator.hasNext()) {
        if (!other.readContains(iterator.next())) return false
    }
    return true
}

internal fun <T> collectionSetHash(set: Set<T>): Int {
    var hash = 0
    val iterator = set.iterator()
    while (iterator.hasNext()) hash += iterator.next().hashCode()
    return hash
}

internal fun <T> collectionText(collection: Collection<T>): String {
    val builder = StringBuilder().append('[')
    val iterator = collection.iterator()
    var first = true
    while (iterator.hasNext()) {
        if (!first) builder.append(", ")
        first = false
        val element: Any? = iterator.next()
        if (element === collection) builder.append("(this Collection)") else builder.append(element)
    }
    return builder.append(']').toString()
}

internal fun <K, V> collectionMapEquals(map: Map<K, V>, other: Any?): Boolean {
    if (map === other) return true
    if (!(other is MapRead) || map.size != other.readSize()) return false
    val iterator = map.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        val key: Any? = entry.key
        val value: Any? = entry.value
        if (value != other.readGet(key)) return false
        if (value == null && !other.readContainsKey(key)) return false
    }
    return true
}

internal fun <K, V> collectionMapHash(map: Map<K, V>): Int {
    var hash = 0
    val iterator = map.entries.iterator()
    while (iterator.hasNext()) {
        val entry = iterator.next()
        hash += entry.key.hashCode() xor entry.value.hashCode()
    }
    return hash
}

internal fun <K, V> collectionMapText(map: Map<K, V>): String {
    val builder = StringBuilder().append('{')
    val iterator = map.entries.iterator()
    var first = true
    while (iterator.hasNext()) {
        if (!first) builder.append(", ")
        first = false
        val entry = iterator.next()
        val key: Any? = entry.key
        val value: Any? = entry.value
        if (key === map) builder.append("(this Map)") else builder.append(key)
        builder.append('=')
        if (value === map) builder.append("(this Map)") else builder.append(value)
    }
    return builder.append('}').toString()
}

internal fun <K, V> collectionEntryEquals(entry: Map.Entry<K, V>, other: Any?): Boolean =
    entry === other || (other is EntryRead && entry.key == other.readKey() && entry.value == other.readValue())

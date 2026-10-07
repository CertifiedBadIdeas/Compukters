/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

import kotlin.*

public interface Iterable<out T> {
    public operator fun iterator(): Iterator<T>
}

public interface Iterator<out T> {
    public operator fun hasNext(): Boolean

    public operator fun next(): T
}

public interface Collection<out T> : Iterable<T> {
    public val size: Int

    public fun isEmpty(): Boolean

    public operator fun contains(element: @UnsafeVariance T): Boolean
}

public interface List<out T> : Collection<T> {
    public operator fun get(index: Int): T

    public fun indexOf(element: @UnsafeVariance T): Int

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

public interface MutableIterable<out T> : Iterable<T> {
    public override fun iterator(): MutableIterator<T>
}

public interface MutableIterator<out T> : Iterator<T> {
    public fun remove(): Unit
}

public interface MutableCollection<T> : Collection<T>, MutableIterable<T> {
    public fun add(element: T): Boolean

    public fun remove(element: T): Boolean

    public fun clear(): Unit
}

public interface MutableList<T> : List<T>, MutableCollection<T> {
    public fun add(index: Int, element: T): Unit

    public operator fun set(index: Int, element: T): T

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

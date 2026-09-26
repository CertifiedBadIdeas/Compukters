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

internal interface Set<out T> : Collection<T>

internal interface Map<K, out V> {
    interface Entry<out K, out V>
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

internal interface MutableSet<T> : Set<T>, MutableCollection<T>

internal interface MutableMap<K, V> : Map<K, V> {
    interface MutableEntry<K, V> : Map.Entry<K, V>
}

internal interface MutableListIterator<T> : ListIterator<T>, MutableIterator<T>

internal abstract class BooleanIterator : Iterator<Boolean>

internal abstract class ByteIterator : Iterator<Byte>

internal abstract class CharIterator : Iterator<Char>

internal abstract class ShortIterator : Iterator<Short>

public abstract class IntIterator : Iterator<Int> {
    public abstract override operator fun next(): Int

    public abstract fun nextInt(): Int
}

internal abstract class LongIterator : Iterator<Long>

internal abstract class FloatIterator : Iterator<Float>

internal abstract class DoubleIterator : Iterator<Double>

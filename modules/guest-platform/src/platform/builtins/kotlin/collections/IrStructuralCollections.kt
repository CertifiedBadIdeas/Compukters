/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

import kotlin.*

internal interface Iterable<out T>

internal interface Iterator<out T>

internal interface Collection<out T> : Iterable<T>

internal interface List<out T> : Collection<T>

internal interface Set<out T> : Collection<T>

internal interface Map<K, out V> {
    interface Entry<out K, out V>
}

internal interface ListIterator<out T> : Iterator<T>

internal interface MutableIterable<out T> : Iterable<T>

internal interface MutableIterator<out T> : Iterator<T>

internal interface MutableCollection<T> : Collection<T>, MutableIterable<T>

internal interface MutableList<T> : List<T>, MutableCollection<T>

internal interface MutableSet<T> : Set<T>, MutableCollection<T>

internal interface MutableMap<K, V> : Map<K, V> {
    interface MutableEntry<K, V> : Map.Entry<K, V>
}

internal interface MutableListIterator<T> : ListIterator<T>, MutableIterator<T>

internal abstract class BooleanIterator : Iterator<Boolean>

internal abstract class ByteIterator : Iterator<Byte>

internal abstract class CharIterator : Iterator<Char>

internal abstract class ShortIterator : Iterator<Short>

internal abstract class IntIterator : Iterator<Int>

internal abstract class LongIterator : Iterator<Long>

internal abstract class FloatIterator : Iterator<Float>

internal abstract class DoubleIterator : Iterator<Double>

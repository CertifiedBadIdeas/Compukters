/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.enums

import kotlin.Enum
import kotlin.collections.Iterator
import kotlin.collections.List

/** Immutable enum singleton entries in declaration order, shared by each enum type. */
public sealed interface EnumEntries<E : Enum<E>> : List<E>

/** Compiler-owned backing array; no mutable collection or array alias is exposed. */
internal class EnumEntriesList<T : Enum<T>>(private val elements: Array<T>) : EnumEntries<T> {
    override val size: Int get() = elements.size
    override fun isEmpty(): Boolean = size == 0
    override fun get(index: Int): T = elements[index]
    override fun contains(element: T): Boolean = indexOf(element) >= 0
    override fun indexOf(element: T): Int {
        var index = 0
        while (index < size) {
            if (elements[index] === element) return index
            index += 1
        }
        return -1
    }
    override fun lastIndexOf(element: T): Int = indexOf(element)
    override fun iterator(): Iterator<T> = EnumEntriesIterator(elements)

    internal fun getNullableElement(index: Int): T? = get(index)
    internal fun getAny(index: Int): Any = get(index)
    internal fun getAnyNullable(index: Int): Any? = get(index)
    internal fun indexOfNullableElement(element: T?): Int = indexOfAnyNullable(element)
    internal fun indexOfAny(element: Any): Int = indexOfAnyNullable(element)
    internal fun indexOfAnyNullable(element: Any?): Int {
        var index = 0
        while (index < size) {
            if ((elements[index] as Any) === element) return index
            index += 1
        }
        return -1
    }
    internal fun lastIndexOfNullableElement(element: T?): Int = indexOfAnyNullable(element)
    internal fun lastIndexOfAny(element: Any): Int = indexOfAnyNullable(element)
    internal fun lastIndexOfAnyNullable(element: Any?): Int = indexOfAnyNullable(element)
    internal fun containsNullableElement(element: T?): Boolean = indexOfAnyNullable(element) >= 0
    internal fun containsAny(element: Any): Boolean = indexOfAnyNullable(element) >= 0
    internal fun containsAnyNullable(element: Any?): Boolean = indexOfAnyNullable(element) >= 0
    internal fun iteratorNullableElement(): Iterator<T?> = EnumEntriesIterator(elements)
    internal fun iteratorAny(): Iterator<Any> = EnumEntriesIterator(elements)
    internal fun iteratorAnyNullable(): Iterator<Any?> = EnumEntriesIterator(elements)
}

internal class EnumEntriesIterator<T : Enum<T>>(private val elements: Array<T>) : Iterator<T> {
    private var index: Int = 0
    override fun hasNext(): Boolean = index < elements.size
    override fun next(): T {
        if (!hasNext()) throw kotlin.NoSuchElementException("Enum entries iterator is exhausted")
        val element = elements[index]
        index += 1
        return element
    }
    internal fun nextNullableElement(): T? = next()
    internal fun nextAny(): Any = next()
    internal fun nextAnyNullable(): Any? = next()
}

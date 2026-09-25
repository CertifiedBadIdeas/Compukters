/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

import kotlin.Array
import kotlin.Int
import kotlin.IntArray

/** Creates a read-only list from the given elements. */
public external fun <T> listOf(vararg elements: T): List<T>

/** Creates an empty read-only list with the requested element type. */
public external fun <T> emptyList(): List<T>

/** Checks whether this collection has at least one element. */
public fun <T> Collection<T>.isNotEmpty(): Boolean = !isEmpty()

/** Returns the index of the first equal element, or -1 when no element matches. */
public fun <T> List<T>.indexOf(element: T): Int {
    var index = 0
    while (index < size) {
        if (element == this[index]) return index
        index += 1
    }
    return -1
}

/** Returns the index of the last equal element, or -1 when no element matches. */
public fun <T> List<T>.lastIndexOf(element: T): Int {
    var index = size - 1
    while (index >= 0) {
        if (element == this[index]) return index
        index -= 1
    }
    return -1
}

internal class IntArrayBackedList(private val values: IntArray) : List<Int> {
    override val size: Int get() = values.size

    override fun isEmpty(): Boolean = size == 0

    override fun contains(element: Int): Boolean = indexOf(element) >= 0

    internal fun containsAny(element: Any): Boolean {
        var index = 0
        while (index < size) {
            if (element == values[index]) return true
            index += 1
        }
        return false
    }

    override fun get(index: Int): Int = values[index]

    override fun iterator(): Iterator<Int> = IntArrayBackedListIterator(this)

    internal fun getAny(index: Int): Any = values[index]

    internal fun iteratorAny(): Iterator<Any> = IntArrayBackedListIterator(this)
}

internal class IntArrayBackedListIterator(private val list: IntArrayBackedList) : Iterator<Int> {
    private var index: Int = 0

    override fun hasNext(): Boolean = index < list.size

    override fun next(): Int {
        val value = list[index]
        index += 1
        return value
    }

    internal fun nextAny(): Any = next()
}

internal class ArrayBackedList<T : Any>(private val values: Array<T>) : List<T> {
    override val size: Int get() = values.size

    override fun isEmpty(): Boolean = size == 0

    override fun contains(element: T): Boolean = indexOf(element) >= 0

    internal fun containsAny(element: Any): Boolean {
        var index = 0
        while (index < size) {
            if (element == values[index]) return true
            index += 1
        }
        return false
    }

    override fun get(index: Int): T = values[index]

    override fun iterator(): Iterator<T> = ArrayBackedListIterator(this)

    internal fun getAny(index: Int): Any = values[index]

    internal fun iteratorAny(): Iterator<Any> = ArrayBackedListIterator(this)
}

internal class ArrayBackedListIterator<T : Any>(private val list: ArrayBackedList<T>) : Iterator<T> {
    private var index: Int = 0

    override fun hasNext(): Boolean = index < list.size

    override fun next(): T {
        val value = list[index]
        index += 1
        return value
    }

    internal fun nextAny(): Any = next()
}

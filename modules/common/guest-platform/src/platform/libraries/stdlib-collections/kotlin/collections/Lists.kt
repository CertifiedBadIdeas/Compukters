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

internal class IntArrayBackedList(private val values: IntArray) : List<Int> {
    override val size: Int get() = values.size

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

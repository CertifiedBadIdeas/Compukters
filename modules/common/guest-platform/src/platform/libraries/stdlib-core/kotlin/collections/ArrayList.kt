/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

/**
 * A mutable ordered list backed by an array that grows as elements are added.
 *
 * Duplicates are retained. Nullable element types may store null. A [List] view refers to the
 * same object and observes later mutations. Removed storage slots are cleared; [clear] retains
 * backing capacity for reuse. Insertion and removal shift following elements.
 *
 * Adding, removing, or calling [clear] invalidates existing iterators, except for removal through
 * that iterator itself. [set] replaces an element without changing size or invalidating iterators.
 * Allocation uses the VM heap budget.
 *
 * @param T element type.
 * @param initialCapacity initial backing capacity, not the list's initial size.
 * @constructor creates an empty list with the requested non-negative capacity; the default is 10.
 * @throws IllegalArgumentException if [initialCapacity] is negative.
 * @see MutableList
 */
public class ArrayList<T>(
    initialCapacity: Int = 10,
) : MutableList<T> {
    private var storage: MutableListStorage<T> = mutableListStorage<T>(initialCapacity)
    /**
     * Number of elements currently stored in this list.
     */
    public override var size: Int = 0
        private set
    internal var modificationCount: Int = 0
        private set

    /**
     * Checks whether this list has no elements.
     *
     * @return true when [size] is zero.
     */
    public override fun isEmpty(): Boolean = size == 0

    /**
     * Returns the element at [index] without removing it.
     *
     * @param index zero-based element index, less than [size].
     * @return the stored element, including null for nullable element types.
     * @throws IllegalArgumentException if [index] is negative or at least [size].
     * @see getOrNull
     */
    public override fun get(index: Int): T {
        require(index >= 0 && index < size)
        return storage[index]
    }

    /**
     * Replaces the element at [index] and returns the previous element.
     *
     * Does not change [size], shift other elements, or invalidate iterators.
     *
     * @param index zero-based element index, less than [size].
     * @param element replacement element.
     * @return the element previously stored at [index].
     * @throws IllegalArgumentException if [index] is negative or at least [size].
     */
    public override fun set(
        index: Int,
        element: T,
    ): T {
        val previous = get(index)
        storage[index] = element
        return previous
    }

    /**
     * Appends [element] to the end of this list, growing the backing array when needed.
     *
     * Duplicates are allowed. Successful insertion invalidates existing iterators.
     *
     * @param element element to append.
     * @return true after the element has been added.
     * @throws IllegalArgumentException if [size] is already [Int.MAX_VALUE].
     */
    public override fun add(element: T): Boolean {
        add(size, element)
        return true
    }

    /**
     * Inserts [element] at [index], shifting following elements one position to the right.
     *
     * An index equal to [size] appends to the list. Successful insertion invalidates existing iterators.
     *
     * @param index insertion position, from zero through [size] inclusive.
     * @param element element to insert.
     * @throws IllegalArgumentException if [index] is outside the insertion range or [size] is [Int.MAX_VALUE].
     */
    public override fun add(
        index: Int,
        element: T,
    ) {
        require(index >= 0 && index <= size)
        require(size < Int.MAX_VALUE)
        storage.ensureCapacity(size + 1)
        var position = size
        while (position > index) {
            storage[position] = storage[position - 1]
            position -= 1
        }
        storage[index] = element
        size += 1
        modificationCount += 1
    }

    /**
     * Removes the element at [index] and shifts following elements one position to the left.
     *
     * Decreases [size], clears the unused storage slot and invalidates existing iterators.
     *
     * @param index zero-based element index, less than [size].
     * @return the removed element.
     * @throws IllegalArgumentException if [index] is negative or at least [size].
     */
    public override fun removeAt(index: Int): T {
        val previous = get(index)
        var position = index
        while (position < size - 1) {
            storage[position] = storage[position + 1]
            position += 1
        }
        size -= 1
        storage.clearSlot(size)
        modificationCount += 1
        return previous
    }

    /**
     * Removes the first element equal to [element].
     *
     * Uses the same equality as [indexOf]. Removing a match shifts following elements to the left
     * and invalidates existing iterators. An absent element leaves the list unchanged.
     *
     * @param element value to search for and remove.
     * @return true if an element was removed, or false when no equal element exists.
     */
    public override fun remove(element: T): Boolean {
        val index = indexOf(element)
        if (index < 0) return false
        removeAt(index)
        return true
    }

    /**
     * Removes all elements while retaining backing capacity for reuse.
     *
     * Clears the occupied storage slots and sets [size] to zero. Every call invalidates existing
     * iterators, including calls on an already empty list.
     */
    public override fun clear() {
        var index = 0
        while (index < size) {
            storage.clearSlot(index)
            index += 1
        }
        size = 0
        modificationCount += 1
    }

    /**
     * Checks whether this list contains an element equal to [element].
     *
     * @param element value to search for.
     * @return true if [indexOf] finds a match.
     */
    public override fun contains(element: T): Boolean = indexOf(element) >= 0

    /**
     * Returns the first index whose element equals [element], or -1 when no match exists.
     *
     * Searches from index zero and stops at the first match, using generic element equality.
     *
     * @param element value to search for.
     * @return the first matching index, or -1.
     */
    public override fun indexOf(element: T): Int = listIndexOf<T>(element)

    /**
     * Returns the last index whose element equals [element], or -1 when no match exists.
     *
     * Searches backwards from the last index and stops at the first match, using generic element equality.
     *
     * @param element value to search for.
     * @return the last matching index, or -1.
     */
    public override fun lastIndexOf(element: T): Int = listLastIndexOf<T>(element)

    /**
     * Creates a new mutable iterator that visits this list in index order.
     *
     * [MutableIterator.remove] removes the most recently returned element once and preserves the
     * iterator's traversal position. External insertion, removal or any [clear] invalidates later
     * [Iterator.next] and [MutableIterator.remove] calls; [set] does not. [Iterator.hasNext] only
     * checks whether an element remains and does not validate structural changes.
     *
     * This iterator throws [IllegalArgumentException] from next after exhaustion or invalidation,
     * or from remove after invalidation, before next, or twice after the same successful next.
     *
     * @return a fresh iterator positioned before the first element.
     */
    public override fun iterator(): MutableIterator<T> = ArrayListIterator(this)

    internal fun iteratorReadOnly(): Iterator<T> = ArrayListIterator(this)

    internal fun getNullableElement(index: Int): T? = get(index)

    internal fun indexOfNullableElement(element: T?): Int = listIndexOf<T?>(element)

    internal fun lastIndexOfNullableElement(element: T?): Int = listLastIndexOf<T?>(element)

    internal fun containsNullableElement(element: T?): Boolean = indexOfNullableElement(element) >= 0

    internal fun iteratorNullableElement(): Iterator<T?> = ArrayListIterator(this)

    /** Compares elements using the list's ordering rules and nullable generic equality. */
    public override fun equals(other: Any?): Boolean = collectionListEquals(this, other)

    /** Combines element hashes with ordinary Int overflow; null contributes zero. */
    public override fun hashCode(): Int = collectionListHash(this)

    /** Formats elements in brackets; a direct self reference uses `(this Collection)`. */
    public override fun toString(): String = collectionText(this)

    internal fun getAny(index: Int): Any = get(index) as Any

    internal fun getAnyNullable(index: Int): Any? = get(index)

    internal fun containsAny(element: Any): Boolean = indexOfAny(element) >= 0

    internal fun containsAnyNullable(element: Any?): Boolean = indexOfAnyNullable(element) >= 0

    internal fun indexOfAny(element: Any): Int = indexOfAnyNullable(element)

    internal fun lastIndexOfAny(element: Any): Int = lastIndexOfAnyNullable(element)

    internal fun indexOfAnyNullable(element: Any?): Int = listIndexOf<Any?>(element)

    internal fun lastIndexOfAnyNullable(element: Any?): Int = listLastIndexOf<Any?>(element)

    internal fun iteratorAny(): Iterator<Any> = ArrayListAnyIterator(this)

    internal fun iteratorAnyNullable(): Iterator<Any?> = ArrayListIterator(this)
}

// Primitive read views stay unboxed; nullable and Any reads use nominal boxing bridges.
internal fun <T> List<T>.listIndexOf(element: T): Int {
    var index = 0
    while (index < size) {
        if (element == this[index]) return index
        index += 1
    }
    return -1
}

internal fun <T> List<T>.listLastIndexOf(element: T): Int {
    var index = size - 1
    while (index >= 0) {
        if (element == this[index]) return index
        index -= 1
    }
    return -1
}

internal class ArrayListIterator<T>(
    private val list: ArrayList<T>,
) : MutableIterator<T> {
    private var index: Int = 0
    private var lastReturned: Int = -1
    private var expectedModificationCount: Int = list.modificationCount

    override fun hasNext(): Boolean = index < list.size

    override fun next(): T {
        require(expectedModificationCount == list.modificationCount)
        val value = list[index]
        lastReturned = index
        index += 1
        return value
    }

    override fun remove() {
        require(expectedModificationCount == list.modificationCount)
        require(lastReturned >= 0)
        list.removeAt(lastReturned)
        index = lastReturned
        lastReturned = -1
        expectedModificationCount = list.modificationCount
    }

    internal fun nextNullableElement(): T? = next()

    internal fun nextAny(): Any = next() as Any

    internal fun nextAnyNullable(): Any? = next()
}

internal class ArrayListAnyIterator<T>(
    private val list: ArrayList<T>,
) : Iterator<Any> {
    private val iterator: MutableIterator<T> = list.iterator()

    override fun hasNext(): Boolean = iterator.hasNext()

    override fun next(): Any = iterator.next() as Any

    internal fun nextAnyNullable(): Any? = iterator.next()
}

internal interface MutableListStorage<T> {
    operator fun get(index: Int): T

    operator fun set(
        index: Int,
        element: T,
    ): Unit

    fun ensureCapacity(minimum: Int): Unit

    fun clearSlot(index: Int): Unit
}

internal external fun <T> mutableListStorage(capacity: Int): MutableListStorage<T>

internal fun checkedListCapacity(capacity: Int): Int {
    require(capacity >= 0)
    return capacity
}

internal fun grownListCapacity(
    current: Int,
    minimum: Int,
): Int {
    require(minimum >= 0)
    val increment = current / 2 + 1
    val grown = if (current > Int.MAX_VALUE - increment) Int.MAX_VALUE else current + increment
    return if (grown < minimum) minimum else grown
}

internal class BooleanMutableListStorage(
    capacity: Int,
) : MutableListStorage<Boolean> {
    private var values: BooleanArray = BooleanArray(checkedListCapacity(capacity))

    override fun get(index: Int): Boolean = values[index]

    override fun set(
        index: Int,
        element: Boolean,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = false
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class ByteMutableListStorage(
    capacity: Int,
) : MutableListStorage<Byte> {
    private var values: ByteArray = ByteArray(checkedListCapacity(capacity))

    override fun get(index: Int): Byte = values[index]

    override fun set(
        index: Int,
        element: Byte,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = 0.toByte()
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class ShortMutableListStorage(
    capacity: Int,
) : MutableListStorage<Short> {
    private var values: ShortArray = ShortArray(checkedListCapacity(capacity))

    override fun get(index: Int): Short = values[index]

    override fun set(
        index: Int,
        element: Short,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = 0.toShort()
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class CharMutableListStorage(
    capacity: Int,
) : MutableListStorage<Char> {
    private var values: CharArray = CharArray(checkedListCapacity(capacity))

    override fun get(index: Int): Char = values[index]

    override fun set(
        index: Int,
        element: Char,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = '\u0000'
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class IntMutableListStorage(
    capacity: Int,
) : MutableListStorage<Int> {
    private var values: IntArray = IntArray(checkedListCapacity(capacity))

    override fun get(index: Int): Int = values[index]

    override fun set(
        index: Int,
        element: Int,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = 0
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class LongMutableListStorage(
    capacity: Int,
) : MutableListStorage<Long> {
    private var values: LongArray = LongArray(checkedListCapacity(capacity))

    override fun get(index: Int): Long = values[index]

    override fun set(
        index: Int,
        element: Long,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = 0L
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class FloatMutableListStorage(
    capacity: Int,
) : MutableListStorage<Float> {
    private var values: FloatArray = FloatArray(checkedListCapacity(capacity))

    override fun get(index: Int): Float = values[index]

    override fun set(
        index: Int,
        element: Float,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = 0.0f
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class DoubleMutableListStorage(
    capacity: Int,
) : MutableListStorage<Double> {
    private var values: DoubleArray = DoubleArray(checkedListCapacity(capacity))

    override fun get(index: Int): Double = values[index]

    override fun set(
        index: Int,
        element: Double,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = 0.0
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class UByteMutableListStorage(
    capacity: Int,
) : MutableListStorage<UByte> {
    private var values: UByteArray = UByteArray(checkedListCapacity(capacity))

    override fun get(index: Int): UByte = values[index]

    override fun set(
        index: Int,
        element: UByte,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = 0u.toUByte()
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class UShortMutableListStorage(
    capacity: Int,
) : MutableListStorage<UShort> {
    private var values: UShortArray = UShortArray(checkedListCapacity(capacity))

    override fun get(index: Int): UShort = values[index]

    override fun set(
        index: Int,
        element: UShort,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = 0u.toUShort()
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class UIntMutableListStorage(
    capacity: Int,
) : MutableListStorage<UInt> {
    private var values: UIntArray = UIntArray(checkedListCapacity(capacity))

    override fun get(index: Int): UInt = values[index]

    override fun set(
        index: Int,
        element: UInt,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = 0u
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class ULongMutableListStorage(
    capacity: Int,
) : MutableListStorage<ULong> {
    private var values: ULongArray = ULongArray(checkedListCapacity(capacity))

    override fun get(index: Int): ULong = values[index]

    override fun set(
        index: Int,
        element: ULong,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = 0uL
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class ReferenceMutableListStorage<T>(
    capacity: Int,
) : MutableListStorage<T> {
    private var values: Array<T?> = arrayOfNulls<T>(checkedListCapacity(capacity))

    @Suppress("UNCHECKED_CAST")
    override fun get(index: Int): T = values[index] as T

    override fun set(
        index: Int,
        element: T,
    ) {
        values[index] = element
    }

    override fun clearSlot(index: Int) {
        values[index] = null
    }

    override fun ensureCapacity(minimum: Int) {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

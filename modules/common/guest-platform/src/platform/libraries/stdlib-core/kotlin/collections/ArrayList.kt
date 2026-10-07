/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.collections

/** A mutable list backed by an array whose capacity grows as elements are added. */
public class ArrayList<T>(initialCapacity: Int = 10) : MutableList<T> {
    private var storage: MutableListStorage<T> = mutableListStorage<T>(initialCapacity)
    public override var size: Int = 0
        private set
    internal var modificationCount: Int = 0
        private set

    public override fun isEmpty(): Boolean = size == 0

    public override fun get(index: Int): T {
        require(index >= 0 && index < size)
        return storage[index]
    }

    public override fun set(index: Int, element: T): T {
        val previous = get(index)
        storage[index] = element
        return previous
    }

    public override fun add(element: T): Boolean {
        add(size, element)
        return true
    }

    public override fun add(index: Int, element: T): Unit {
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

    public override fun remove(element: T): Boolean {
        val index = indexOf(element)
        if (index < 0) return false
        removeAt(index)
        return true
    }

    public override fun clear(): Unit {
        var index = 0
        while (index < size) {
            storage.clearSlot(index)
            index += 1
        }
        size = 0
        modificationCount += 1
    }

    public override fun contains(element: T): Boolean = indexOf(element) >= 0

    public override fun indexOf(element: T): Int = listIndexOf<T>(element)

    public override fun lastIndexOf(element: T): Int = listLastIndexOf<T>(element)

    public override fun iterator(): MutableIterator<T> = ArrayListIterator(this)

    internal fun iteratorReadOnly(): Iterator<T> = ArrayListIterator(this)

    internal fun getNullableElement(index: Int): T? = get(index)

    internal fun indexOfNullableElement(element: T?): Int = listIndexOf<T?>(element)

    internal fun lastIndexOfNullableElement(element: T?): Int = listLastIndexOf<T?>(element)

    internal fun containsNullableElement(element: T?): Boolean = indexOfNullableElement(element) >= 0

    internal fun iteratorNullableElement(): Iterator<T?> = ArrayListIterator(this)

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

internal class ArrayListIterator<T>(private val list: ArrayList<T>) : MutableIterator<T> {
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

    override fun remove(): Unit {
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

internal class ArrayListAnyIterator<T>(private val list: ArrayList<T>) : Iterator<Any> {
    private val iterator: MutableIterator<T> = list.iterator()
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): Any = iterator.next() as Any
    internal fun nextAnyNullable(): Any? = iterator.next()
}

internal interface MutableListStorage<T> {
    operator fun get(index: Int): T
    operator fun set(index: Int, element: T): Unit
    fun ensureCapacity(minimum: Int): Unit
    fun clearSlot(index: Int): Unit
}

internal external fun <T> mutableListStorage(capacity: Int): MutableListStorage<T>

internal fun checkedListCapacity(capacity: Int): Int {
    require(capacity >= 0)
    return capacity
}

internal fun grownListCapacity(current: Int, minimum: Int): Int {
    require(minimum >= 0)
    val increment = current / 2 + 1
    val grown = if (current > Int.MAX_VALUE - increment) Int.MAX_VALUE else current + increment
    return if (grown < minimum) minimum else grown
}

internal class BooleanMutableListStorage(capacity: Int) : MutableListStorage<Boolean> {
    private var values: BooleanArray = BooleanArray(checkedListCapacity(capacity))
    override fun get(index: Int): Boolean = values[index]
    override fun set(index: Int, element: Boolean): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = false }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class ByteMutableListStorage(capacity: Int) : MutableListStorage<Byte> {
    private var values: ByteArray = ByteArray(checkedListCapacity(capacity))
    override fun get(index: Int): Byte = values[index]
    override fun set(index: Int, element: Byte): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = 0.toByte() }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class ShortMutableListStorage(capacity: Int) : MutableListStorage<Short> {
    private var values: ShortArray = ShortArray(checkedListCapacity(capacity))
    override fun get(index: Int): Short = values[index]
    override fun set(index: Int, element: Short): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = 0.toShort() }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class CharMutableListStorage(capacity: Int) : MutableListStorage<Char> {
    private var values: CharArray = CharArray(checkedListCapacity(capacity))
    override fun get(index: Int): Char = values[index]
    override fun set(index: Int, element: Char): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = '\u0000' }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class IntMutableListStorage(capacity: Int) : MutableListStorage<Int> {
    private var values: IntArray = IntArray(checkedListCapacity(capacity))
    override fun get(index: Int): Int = values[index]
    override fun set(index: Int, element: Int): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = 0 }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class LongMutableListStorage(capacity: Int) : MutableListStorage<Long> {
    private var values: LongArray = LongArray(checkedListCapacity(capacity))
    override fun get(index: Int): Long = values[index]
    override fun set(index: Int, element: Long): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = 0L }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class FloatMutableListStorage(capacity: Int) : MutableListStorage<Float> {
    private var values: FloatArray = FloatArray(checkedListCapacity(capacity))
    override fun get(index: Int): Float = values[index]
    override fun set(index: Int, element: Float): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = 0.0f }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class DoubleMutableListStorage(capacity: Int) : MutableListStorage<Double> {
    private var values: DoubleArray = DoubleArray(checkedListCapacity(capacity))
    override fun get(index: Int): Double = values[index]
    override fun set(index: Int, element: Double): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = 0.0 }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class UByteMutableListStorage(capacity: Int) : MutableListStorage<UByte> {
    private var values: UByteArray = UByteArray(checkedListCapacity(capacity))
    override fun get(index: Int): UByte = values[index]
    override fun set(index: Int, element: UByte): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = 0u.toUByte() }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class UShortMutableListStorage(capacity: Int) : MutableListStorage<UShort> {
    private var values: UShortArray = UShortArray(checkedListCapacity(capacity))
    override fun get(index: Int): UShort = values[index]
    override fun set(index: Int, element: UShort): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = 0u.toUShort() }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class UIntMutableListStorage(capacity: Int) : MutableListStorage<UInt> {
    private var values: UIntArray = UIntArray(checkedListCapacity(capacity))
    override fun get(index: Int): UInt = values[index]
    override fun set(index: Int, element: UInt): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = 0u }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class ULongMutableListStorage(capacity: Int) : MutableListStorage<ULong> {
    private var values: ULongArray = ULongArray(checkedListCapacity(capacity))
    override fun get(index: Int): ULong = values[index]
    override fun set(index: Int, element: ULong): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = 0uL }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

internal class ReferenceMutableListStorage<T>(capacity: Int) : MutableListStorage<T> {
    private var values: Array<T?> = arrayOfNulls<T>(checkedListCapacity(capacity))
    @Suppress("UNCHECKED_CAST")
    override fun get(index: Int): T = values[index] as T
    override fun set(index: Int, element: T): Unit { values[index] = element }
    override fun clearSlot(index: Int): Unit { values[index] = null }
    override fun ensureCapacity(minimum: Int): Unit {
        if (minimum <= values.size) return
        values = values.copyOf(grownListCapacity(values.size, minimum))
    }
}

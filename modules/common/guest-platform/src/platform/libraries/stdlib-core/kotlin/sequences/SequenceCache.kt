/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.sequences

// Occupancy belongs to FilteringIterator; a nullable cached value may itself be an element.
internal interface SequenceCache<T> {
    fun store(value: T): Unit
    fun take(): T
}

internal external fun <T> sequenceCache(): SequenceCache<T>

internal class IntSequenceCache : SequenceCache<Int> {
    private var value: Int = 0
    override fun store(value: Int) { this.value = value }
    override fun take(): Int = value
}

internal class BooleanSequenceCache : SequenceCache<Boolean> {
    private var value: Boolean = false
    override fun store(value: Boolean) { this.value = value }
    override fun take(): Boolean = value
}

internal class LongSequenceCache : SequenceCache<Long> {
    private var value: Long = 0L
    override fun store(value: Long) { this.value = value }
    override fun take(): Long = value
}

internal class FloatSequenceCache : SequenceCache<Float> {
    private var value: Float = 0.0f
    override fun store(value: Float) { this.value = value }
    override fun take(): Float = value
}

internal class DoubleSequenceCache : SequenceCache<Double> {
    private var value: Double = 0.0
    override fun store(value: Double) { this.value = value }
    override fun take(): Double = value
}

internal class CharSequenceCache : SequenceCache<Char> {
    private var value: Char = '\u0000'
    override fun store(value: Char) { this.value = value }
    override fun take(): Char = value
}

internal class ByteSequenceCache : SequenceCache<Byte> {
    private var value: Byte = 0
    override fun store(value: Byte) { this.value = value }
    override fun take(): Byte = value
}

internal class ShortSequenceCache : SequenceCache<Short> {
    private var value: Short = 0
    override fun store(value: Short) { this.value = value }
    override fun take(): Short = value
}

internal class UByteSequenceCache : SequenceCache<UByte> {
    private var value: UByte = 0u
    override fun store(value: UByte) { this.value = value }
    override fun take(): UByte = value
}

internal class UShortSequenceCache : SequenceCache<UShort> {
    private var value: UShort = 0u
    override fun store(value: UShort) { this.value = value }
    override fun take(): UShort = value
}

internal class UIntSequenceCache : SequenceCache<UInt> {
    private var value: UInt = 0u
    override fun store(value: UInt) { this.value = value }
    override fun take(): UInt = value
}

internal class ULongSequenceCache : SequenceCache<ULong> {
    private var value: ULong = 0uL
    override fun store(value: ULong) { this.value = value }
    override fun take(): ULong = value
}

internal class ReferenceSequenceCache<T> : SequenceCache<T> {
    private var value: T? = null

    override fun store(value: T) { this.value = value }

    @Suppress("UNCHECKED_CAST")
    override fun take(): T {
        val result = value as T
        value = null
        return result
    }
}

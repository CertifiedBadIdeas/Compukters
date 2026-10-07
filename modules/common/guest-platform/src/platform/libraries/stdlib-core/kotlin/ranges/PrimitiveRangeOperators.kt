/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.ranges

public operator fun Byte.rangeTo(other: Byte): IntRange = IntRange(this.toInt(), other.toInt())
public infix fun Byte.until(other: Byte): IntRange {
    val end = other.toInt()
    return if (end == Int.MIN_VALUE) IntRange(1, 0) else IntRange(this.toInt(), end - 1)
}
public operator fun Byte.rangeUntil(other: Byte): IntRange = this until other
public infix fun Byte.downTo(other: Byte): IntProgression = IntProgression(this.toInt(), other.toInt(), -1)
public operator fun Byte.rangeTo(other: Short): IntRange = IntRange(this.toInt(), other.toInt())
public infix fun Byte.until(other: Short): IntRange {
    val end = other.toInt()
    return if (end == Int.MIN_VALUE) IntRange(1, 0) else IntRange(this.toInt(), end - 1)
}
public operator fun Byte.rangeUntil(other: Short): IntRange = this until other
public infix fun Byte.downTo(other: Short): IntProgression = IntProgression(this.toInt(), other.toInt(), -1)
public operator fun Byte.rangeTo(other: Int): IntRange = IntRange(this.toInt(), other)
public infix fun Byte.until(other: Int): IntRange {
    val end = other
    return if (end == Int.MIN_VALUE) IntRange(1, 0) else IntRange(this.toInt(), end - 1)
}
public operator fun Byte.rangeUntil(other: Int): IntRange = this until other
public infix fun Byte.downTo(other: Int): IntProgression = IntProgression(this.toInt(), other, -1)
public operator fun Byte.rangeTo(other: Long): LongRange = LongRange(this.toLong(), other)
public infix fun Byte.until(other: Long): LongRange {
    val end = other
    return if (end == Long.MIN_VALUE) LongRange(1L, 0L) else LongRange(this.toLong(), end - 1L)
}
public operator fun Byte.rangeUntil(other: Long): LongRange = this until other
public infix fun Byte.downTo(other: Long): LongProgression = LongProgression(this.toLong(), other, -1L)
public operator fun Short.rangeTo(other: Byte): IntRange = IntRange(this.toInt(), other.toInt())
public infix fun Short.until(other: Byte): IntRange {
    val end = other.toInt()
    return if (end == Int.MIN_VALUE) IntRange(1, 0) else IntRange(this.toInt(), end - 1)
}
public operator fun Short.rangeUntil(other: Byte): IntRange = this until other
public infix fun Short.downTo(other: Byte): IntProgression = IntProgression(this.toInt(), other.toInt(), -1)
public operator fun Short.rangeTo(other: Short): IntRange = IntRange(this.toInt(), other.toInt())
public infix fun Short.until(other: Short): IntRange {
    val end = other.toInt()
    return if (end == Int.MIN_VALUE) IntRange(1, 0) else IntRange(this.toInt(), end - 1)
}
public operator fun Short.rangeUntil(other: Short): IntRange = this until other
public infix fun Short.downTo(other: Short): IntProgression = IntProgression(this.toInt(), other.toInt(), -1)
public operator fun Short.rangeTo(other: Int): IntRange = IntRange(this.toInt(), other)
public infix fun Short.until(other: Int): IntRange {
    val end = other
    return if (end == Int.MIN_VALUE) IntRange(1, 0) else IntRange(this.toInt(), end - 1)
}
public operator fun Short.rangeUntil(other: Int): IntRange = this until other
public infix fun Short.downTo(other: Int): IntProgression = IntProgression(this.toInt(), other, -1)
public operator fun Short.rangeTo(other: Long): LongRange = LongRange(this.toLong(), other)
public infix fun Short.until(other: Long): LongRange {
    val end = other
    return if (end == Long.MIN_VALUE) LongRange(1L, 0L) else LongRange(this.toLong(), end - 1L)
}
public operator fun Short.rangeUntil(other: Long): LongRange = this until other
public infix fun Short.downTo(other: Long): LongProgression = LongProgression(this.toLong(), other, -1L)
public operator fun Int.rangeTo(other: Byte): IntRange = IntRange(this, other.toInt())
public infix fun Int.until(other: Byte): IntRange {
    val end = other.toInt()
    return if (end == Int.MIN_VALUE) IntRange(1, 0) else IntRange(this, end - 1)
}
public operator fun Int.rangeUntil(other: Byte): IntRange = this until other
public infix fun Int.downTo(other: Byte): IntProgression = IntProgression(this, other.toInt(), -1)
public operator fun Int.rangeTo(other: Short): IntRange = IntRange(this, other.toInt())
public infix fun Int.until(other: Short): IntRange {
    val end = other.toInt()
    return if (end == Int.MIN_VALUE) IntRange(1, 0) else IntRange(this, end - 1)
}
public operator fun Int.rangeUntil(other: Short): IntRange = this until other
public infix fun Int.downTo(other: Short): IntProgression = IntProgression(this, other.toInt(), -1)
public operator fun Int.rangeTo(other: Int): IntRange = IntRange(this, other)
public infix fun Int.until(other: Int): IntRange {
    val end = other
    return if (end == Int.MIN_VALUE) IntRange(1, 0) else IntRange(this, end - 1)
}
public operator fun Int.rangeUntil(other: Int): IntRange = this until other
public operator fun Int.rangeTo(other: Long): LongRange = LongRange(this.toLong(), other)
public infix fun Int.until(other: Long): LongRange {
    val end = other
    return if (end == Long.MIN_VALUE) LongRange(1L, 0L) else LongRange(this.toLong(), end - 1L)
}
public operator fun Int.rangeUntil(other: Long): LongRange = this until other
public infix fun Int.downTo(other: Long): LongProgression = LongProgression(this.toLong(), other, -1L)
public operator fun Long.rangeTo(other: Byte): LongRange = LongRange(this, other.toLong())
public infix fun Long.until(other: Byte): LongRange {
    val end = other.toLong()
    return if (end == Long.MIN_VALUE) LongRange(1L, 0L) else LongRange(this, end - 1L)
}
public operator fun Long.rangeUntil(other: Byte): LongRange = this until other
public infix fun Long.downTo(other: Byte): LongProgression = LongProgression(this, other.toLong(), -1L)
public operator fun Long.rangeTo(other: Short): LongRange = LongRange(this, other.toLong())
public infix fun Long.until(other: Short): LongRange {
    val end = other.toLong()
    return if (end == Long.MIN_VALUE) LongRange(1L, 0L) else LongRange(this, end - 1L)
}
public operator fun Long.rangeUntil(other: Short): LongRange = this until other
public infix fun Long.downTo(other: Short): LongProgression = LongProgression(this, other.toLong(), -1L)
public operator fun Long.rangeTo(other: Int): LongRange = LongRange(this, other.toLong())
public infix fun Long.until(other: Int): LongRange {
    val end = other.toLong()
    return if (end == Long.MIN_VALUE) LongRange(1L, 0L) else LongRange(this, end - 1L)
}
public operator fun Long.rangeUntil(other: Int): LongRange = this until other
public infix fun Long.downTo(other: Int): LongProgression = LongProgression(this, other.toLong(), -1L)
public operator fun Long.rangeTo(other: Long): LongRange = LongRange(this, other)
public infix fun Long.until(other: Long): LongRange {
    val end = other
    return if (end == Long.MIN_VALUE) LongRange(1L, 0L) else LongRange(this, end - 1L)
}
public operator fun Long.rangeUntil(other: Long): LongRange = this until other
public operator fun UByte.rangeTo(other: UByte): UIntRange = UIntRange(this.toUInt(), other.toUInt())
public infix fun UByte.until(other: UByte): UIntRange {
    val end = other.toUInt()
    return if (end == 0u) UIntRange(1u, 0u) else UIntRange(this.toUInt(), end - 1u)
}
public operator fun UByte.rangeUntil(other: UByte): UIntRange = this until other
public infix fun UByte.downTo(other: UByte): UIntProgression = UIntProgression(this.toUInt(), other.toUInt(), -1)
public operator fun UByte.rangeTo(other: UShort): UIntRange = UIntRange(this.toUInt(), other.toUInt())
public infix fun UByte.until(other: UShort): UIntRange {
    val end = other.toUInt()
    return if (end == 0u) UIntRange(1u, 0u) else UIntRange(this.toUInt(), end - 1u)
}
public operator fun UByte.rangeUntil(other: UShort): UIntRange = this until other
public infix fun UByte.downTo(other: UShort): UIntProgression = UIntProgression(this.toUInt(), other.toUInt(), -1)
public operator fun UByte.rangeTo(other: UInt): UIntRange = UIntRange(this.toUInt(), other)
public infix fun UByte.until(other: UInt): UIntRange {
    val end = other
    return if (end == 0u) UIntRange(1u, 0u) else UIntRange(this.toUInt(), end - 1u)
}
public operator fun UByte.rangeUntil(other: UInt): UIntRange = this until other
public infix fun UByte.downTo(other: UInt): UIntProgression = UIntProgression(this.toUInt(), other, -1)
public operator fun UByte.rangeTo(other: ULong): ULongRange = ULongRange(this.toULong(), other)
public infix fun UByte.until(other: ULong): ULongRange {
    val end = other
    return if (end == 0uL) ULongRange(1uL, 0uL) else ULongRange(this.toULong(), end - 1uL)
}
public operator fun UByte.rangeUntil(other: ULong): ULongRange = this until other
public infix fun UByte.downTo(other: ULong): ULongProgression = ULongProgression(this.toULong(), other, -1L)
public operator fun UShort.rangeTo(other: UByte): UIntRange = UIntRange(this.toUInt(), other.toUInt())
public infix fun UShort.until(other: UByte): UIntRange {
    val end = other.toUInt()
    return if (end == 0u) UIntRange(1u, 0u) else UIntRange(this.toUInt(), end - 1u)
}
public operator fun UShort.rangeUntil(other: UByte): UIntRange = this until other
public infix fun UShort.downTo(other: UByte): UIntProgression = UIntProgression(this.toUInt(), other.toUInt(), -1)
public operator fun UShort.rangeTo(other: UShort): UIntRange = UIntRange(this.toUInt(), other.toUInt())
public infix fun UShort.until(other: UShort): UIntRange {
    val end = other.toUInt()
    return if (end == 0u) UIntRange(1u, 0u) else UIntRange(this.toUInt(), end - 1u)
}
public operator fun UShort.rangeUntil(other: UShort): UIntRange = this until other
public infix fun UShort.downTo(other: UShort): UIntProgression = UIntProgression(this.toUInt(), other.toUInt(), -1)
public operator fun UShort.rangeTo(other: UInt): UIntRange = UIntRange(this.toUInt(), other)
public infix fun UShort.until(other: UInt): UIntRange {
    val end = other
    return if (end == 0u) UIntRange(1u, 0u) else UIntRange(this.toUInt(), end - 1u)
}
public operator fun UShort.rangeUntil(other: UInt): UIntRange = this until other
public infix fun UShort.downTo(other: UInt): UIntProgression = UIntProgression(this.toUInt(), other, -1)
public operator fun UShort.rangeTo(other: ULong): ULongRange = ULongRange(this.toULong(), other)
public infix fun UShort.until(other: ULong): ULongRange {
    val end = other
    return if (end == 0uL) ULongRange(1uL, 0uL) else ULongRange(this.toULong(), end - 1uL)
}
public operator fun UShort.rangeUntil(other: ULong): ULongRange = this until other
public infix fun UShort.downTo(other: ULong): ULongProgression = ULongProgression(this.toULong(), other, -1L)
public operator fun UInt.rangeTo(other: UByte): UIntRange = UIntRange(this, other.toUInt())
public infix fun UInt.until(other: UByte): UIntRange {
    val end = other.toUInt()
    return if (end == 0u) UIntRange(1u, 0u) else UIntRange(this, end - 1u)
}
public operator fun UInt.rangeUntil(other: UByte): UIntRange = this until other
public infix fun UInt.downTo(other: UByte): UIntProgression = UIntProgression(this, other.toUInt(), -1)
public operator fun UInt.rangeTo(other: UShort): UIntRange = UIntRange(this, other.toUInt())
public infix fun UInt.until(other: UShort): UIntRange {
    val end = other.toUInt()
    return if (end == 0u) UIntRange(1u, 0u) else UIntRange(this, end - 1u)
}
public operator fun UInt.rangeUntil(other: UShort): UIntRange = this until other
public infix fun UInt.downTo(other: UShort): UIntProgression = UIntProgression(this, other.toUInt(), -1)
public operator fun UInt.rangeTo(other: UInt): UIntRange = UIntRange(this, other)
public infix fun UInt.until(other: UInt): UIntRange {
    val end = other
    return if (end == 0u) UIntRange(1u, 0u) else UIntRange(this, end - 1u)
}
public operator fun UInt.rangeUntil(other: UInt): UIntRange = this until other
public operator fun UInt.rangeTo(other: ULong): ULongRange = ULongRange(this.toULong(), other)
public infix fun UInt.until(other: ULong): ULongRange {
    val end = other
    return if (end == 0uL) ULongRange(1uL, 0uL) else ULongRange(this.toULong(), end - 1uL)
}
public operator fun UInt.rangeUntil(other: ULong): ULongRange = this until other
public infix fun UInt.downTo(other: ULong): ULongProgression = ULongProgression(this.toULong(), other, -1L)
public operator fun ULong.rangeTo(other: UByte): ULongRange = ULongRange(this, other.toULong())
public infix fun ULong.until(other: UByte): ULongRange {
    val end = other.toULong()
    return if (end == 0uL) ULongRange(1uL, 0uL) else ULongRange(this, end - 1uL)
}
public operator fun ULong.rangeUntil(other: UByte): ULongRange = this until other
public infix fun ULong.downTo(other: UByte): ULongProgression = ULongProgression(this, other.toULong(), -1L)
public operator fun ULong.rangeTo(other: UShort): ULongRange = ULongRange(this, other.toULong())
public infix fun ULong.until(other: UShort): ULongRange {
    val end = other.toULong()
    return if (end == 0uL) ULongRange(1uL, 0uL) else ULongRange(this, end - 1uL)
}
public operator fun ULong.rangeUntil(other: UShort): ULongRange = this until other
public infix fun ULong.downTo(other: UShort): ULongProgression = ULongProgression(this, other.toULong(), -1L)
public operator fun ULong.rangeTo(other: UInt): ULongRange = ULongRange(this, other.toULong())
public infix fun ULong.until(other: UInt): ULongRange {
    val end = other.toULong()
    return if (end == 0uL) ULongRange(1uL, 0uL) else ULongRange(this, end - 1uL)
}
public operator fun ULong.rangeUntil(other: UInt): ULongRange = this until other
public infix fun ULong.downTo(other: UInt): ULongProgression = ULongProgression(this, other.toULong(), -1L)
public operator fun ULong.rangeTo(other: ULong): ULongRange = ULongRange(this, other)
public infix fun ULong.until(other: ULong): ULongRange {
    val end = other
    return if (end == 0uL) ULongRange(1uL, 0uL) else ULongRange(this, end - 1uL)
}
public operator fun ULong.rangeUntil(other: ULong): ULongRange = this until other
public operator fun Char.rangeTo(other: Char): CharRange = CharRange(this, other)
public infix fun Char.until(other: Char): CharRange = if (other.code == 0) CharRange(1.toChar(), 0.toChar()) else CharRange(this, other - 1)
public operator fun Char.rangeUntil(other: Char): CharRange = this until other

/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.text

/** Guest parsing deliberately accepts ASCII digits and letters, without whitespace or separators. */
private fun textDigit(character: Char): Int =
    when (character) {
        in '0'..'9' -> character.code - '0'.code
        in 'a'..'z' -> character.code - 'a'.code + 10
        in 'A'..'Z' -> character.code - 'A'.code + 10
        else -> -1
    }

private fun checkTextRadix(radix: Int) {
    require(radix in 2..36)
}

public fun String.toIntOrNull(): Int? = toIntOrNull(10)

public fun String.toIntOrNull(radix: Int): Int? {
    checkTextRadix(radix)
    if (length == 0) return null
    val negative = this[0] == '-'
    var index = if (negative || this[0] == '+') 1 else 0
    if (index == length) return null
    val limit = if (negative) Int.MIN_VALUE else -Int.MAX_VALUE
    val cutoff = limit / radix
    var result = 0
    while (index < length) {
        val digit = textDigit(this[index])
        if (digit < 0 || digit >= radix || result < cutoff) return null
        result *= radix
        if (result < limit + digit) return null
        result -= digit
        index += 1
    }
    return if (negative) result else -result
}

public fun String.toLongOrNull(): Long? = toLongOrNull(10)

public fun String.toLongOrNull(radix: Int): Long? {
    checkTextRadix(radix)
    if (length == 0) return null
    val negative = this[0] == '-'
    var index = if (negative || this[0] == '+') 1 else 0
    if (index == length) return null
    val limit = if (negative) Long.MIN_VALUE else -Long.MAX_VALUE
    val cutoff = limit / radix.toLong()
    var result = 0L
    while (index < length) {
        val digit = textDigit(this[index])
        if (digit < 0 || digit >= radix || result < cutoff) return null
        result *= radix.toLong()
        if (result < limit + digit.toLong()) return null
        result -= digit.toLong()
        index += 1
    }
    return if (negative) result else -result
}

public fun String.toULongOrNull(): ULong? = toULongOrNull(10)

public fun String.toULongOrNull(radix: Int): ULong? {
    checkTextRadix(radix)
    if (length == 0) return null
    var index = if (this[0] == '+') 1 else 0
    if (index == length) return null
    val base = radix.toULong()
    val cutoff = ULong.MAX_VALUE / base
    val remainder = ULong.MAX_VALUE % base
    var result = 0uL
    while (index < length) {
        val digit = textDigit(this[index])
        if (digit < 0 || digit >= radix) return null
        val unsigned = digit.toULong()
        if (result > cutoff || (result == cutoff && unsigned > remainder)) return null
        result = result * base + unsigned
        index += 1
    }
    return result
}

public fun String.toByteOrNull(): Byte? = toByteOrNull(10)

public fun String.toByteOrNull(radix: Int): Byte? {
    val value = toIntOrNull(radix) ?: return null
    if (value < Byte.MIN_VALUE.toInt() || value > Byte.MAX_VALUE.toInt()) return null
    return value.toByte()
}

public fun String.toShortOrNull(): Short? = toShortOrNull(10)

public fun String.toShortOrNull(radix: Int): Short? {
    val value = toIntOrNull(radix) ?: return null
    if (value < Short.MIN_VALUE.toInt() || value > Short.MAX_VALUE.toInt()) return null
    return value.toShort()
}

public fun String.toUIntOrNull(): UInt? = toUIntOrNull(10)

public fun String.toUIntOrNull(radix: Int): UInt? {
    val value = toULongOrNull(radix) ?: return null
    if (value > UInt.MAX_VALUE.toULong()) return null
    return value.toUInt()
}

public fun String.toUByteOrNull(): UByte? = toUByteOrNull(10)

public fun String.toUByteOrNull(radix: Int): UByte? {
    val value = toULongOrNull(radix) ?: return null
    if (value > UByte.MAX_VALUE.toULong()) return null
    return value.toUByte()
}

public fun String.toUShortOrNull(): UShort? = toUShortOrNull(10)

public fun String.toUShortOrNull(radix: Int): UShort? {
    val value = toULongOrNull(radix) ?: return null
    if (value > UShort.MAX_VALUE.toULong()) return null
    return value.toUShort()
}

public fun String.toByte(): Byte = toByte(10)

public fun String.toByte(radix: Int): Byte = toByteOrNull(radix) ?: throw NumberFormatException("Invalid Byte text")

public fun String.toShort(): Short = toShort(10)

public fun String.toShort(radix: Int): Short = toShortOrNull(radix) ?: throw NumberFormatException("Invalid Short text")

public fun String.toInt(): Int = toInt(10)

public fun String.toInt(radix: Int): Int = toIntOrNull(radix) ?: throw NumberFormatException("Invalid Int text")

public fun String.toLong(): Long = toLong(10)

public fun String.toLong(radix: Int): Long = toLongOrNull(radix) ?: throw NumberFormatException("Invalid Long text")

public fun String.toUByte(): UByte = toUByte(10)

public fun String.toUByte(radix: Int): UByte = toUByteOrNull(radix) ?: throw NumberFormatException("Invalid UByte text")

public fun String.toUShort(): UShort = toUShort(10)

public fun String.toUShort(radix: Int): UShort = toUShortOrNull(radix) ?: throw NumberFormatException("Invalid UShort text")

public fun String.toUInt(): UInt = toUInt(10)

public fun String.toUInt(radix: Int): UInt = toUIntOrNull(radix) ?: throw NumberFormatException("Invalid UInt text")

public fun String.toULong(): ULong = toULong(10)

public fun String.toULong(radix: Int): ULong = toULongOrNull(radix) ?: throw NumberFormatException("Invalid ULong text")

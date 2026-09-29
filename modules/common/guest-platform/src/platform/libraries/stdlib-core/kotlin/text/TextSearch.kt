/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.text

public fun String.startsWith(prefix: String): Boolean {
    if (prefix.length > length) return false
    var index = 0
    while (index < prefix.length) {
        if (this[index] != prefix[index]) return false
        index = index + 1
    }
    return true
}

public fun String.endsWith(suffix: String): Boolean {
    val offset = length - suffix.length
    if (offset < 0) return false
    var index = 0
    while (index < suffix.length) {
        if (this[offset + index] != suffix[index]) return false
        index = index + 1
    }
    return true
}

public fun String.contains(other: String): Boolean = indexOf(other) >= 0

public fun String.indexOf(other: String, startIndex: Int = 0): Int {
    var candidate = if (startIndex < 0) 0 else startIndex
    if (other.length == 0) return if (candidate > length) length else candidate
    val lastCandidate = length - other.length
    while (candidate <= lastCandidate) {
        var index = 0
        while (index < other.length && this[candidate + index] == other[index]) {
            index = index + 1
        }
        if (index == other.length) return candidate
        if (candidate == lastCandidate) return -1
        candidate = candidate + 1
    }
    return -1
}

/** Parses a signed decimal Int, or returns null for invalid input or overflow. */
public fun String.toIntOrNull(): Int? {
    if (length == 0) return null

    var index = 0
    val negative = this[0] == '-'
    if (negative || this[0] == '+') {
        index = 1
        if (length == 1) return null
    }

    val limit = if (negative) Int.MIN_VALUE else -Int.MAX_VALUE
    val multiplyLimit = limit / 10
    var result = 0
    while (index < length) {
        val digit =
            when (this[index]) {
                '0' -> 0
                '1' -> 1
                '2' -> 2
                '3' -> 3
                '4' -> 4
                '5' -> 5
                '6' -> 6
                '7' -> 7
                '8' -> 8
                '9' -> 9
                else -> return null
            }
        if (result < multiplyLimit) return null
        result *= 10
        if (result < limit + digit) return null
        result -= digit
        index += 1
    }
    return if (negative) result else -result
}

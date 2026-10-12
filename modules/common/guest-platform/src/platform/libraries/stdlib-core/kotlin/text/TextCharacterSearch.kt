/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.text

/** Finds the first matching character at or after startIndex, clamped to zero. */
public fun CharSequence.indexOf(
    char: Char,
    startIndex: Int = 0,
): Int {
    var index = if (startIndex < 0) 0 else startIndex
    while (index < length) {
        if (this[index] == char) return index
        index += 1
    }
    return -1
}

/** Returns whether this string contains char. */
public fun CharSequence.contains(char: Char): Boolean = indexOf(char) >= 0

/** Finds the last matching character. */
public fun CharSequence.lastIndexOf(char: Char): Int = lastIndexOf(char, length - 1)

/** Searches backwards from startIndex for char. */
public fun CharSequence.lastIndexOf(
    char: Char,
    startIndex: Int,
): Int {
    var index = if (startIndex >= length) length - 1 else startIndex
    while (index >= 0) {
        if (this[index] == char) return index
        index -= 1
    }
    return -1
}

/** Finds the last matching substring, starting at the last character. */
public fun CharSequence.lastIndexOf(string: String): Int = lastIndexOf(string, length - 1)

/** Searches backwards from startIndex for string, comparing UTF-16 code units. */
public fun CharSequence.lastIndexOf(
    string: String,
    startIndex: Int,
): Int {
    val lastCandidate = length - string.length
    var candidate = if (startIndex > lastCandidate) lastCandidate else startIndex
    while (candidate >= 0) {
        var index = 0
        while (index < string.length && this[candidate + index] == string[index]) index += 1
        if (index == string.length) return candidate
        candidate -= 1
    }
    return -1
}

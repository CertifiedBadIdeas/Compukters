/*
 * The Compukters Developers
 * Copyright 2026 Vsevolod Petrov (lazyhat)
 * Licensed under the Apache License, Version 2.0.
 */

package kotlin.text

/** Returns whether this UTF-16 character is Kotlin whitespace. */
public fun Char.isWhitespace(): Boolean {
    return (this >= '\u0009' && this <= '\u000d') || (this >= '\u001c' && this <= ' ') ||
        this == '\u00a0' || this == '\u1680' || (this >= '\u2000' && this <= '\u200a') ||
        this == '\u2028' || this == '\u2029' || this == '\u202f' || this == '\u205f' || this == '\u3000'
}

/** Returns whether this string contains no characters. */
public fun String.isEmpty(): Boolean = length == 0

/** Returns whether this string contains at least one character. */
public fun String.isNotEmpty(): Boolean = length != 0

/** Returns whether this string is empty or contains only whitespace. */
public fun String.isBlank(): Boolean {
    var index = 0
    while (index < length) {
        if (!this[index].isWhitespace()) return false
        index += 1
    }
    return true
}

/** Returns whether this string contains a non-whitespace character. */
public fun String.isNotBlank(): Boolean = !isBlank()

/** Removes leading and trailing whitespace. */
public fun String.trim(): String {
    var start = 0
    var end = length
    while (start < end && this[start].isWhitespace()) start += 1
    while (end > start && this[end - 1].isWhitespace()) end -= 1
    return substring(start, end)
}

/** Removes leading whitespace. */
public fun String.trimStart(): String {
    var start = 0
    while (start < length && this[start].isWhitespace()) start += 1
    return substring(start, length)
}

/** Removes trailing whitespace. */
public fun String.trimEnd(): String {
    var end = length
    while (end > 0 && this[end - 1].isWhitespace()) end -= 1
    return substring(0, end)
}
